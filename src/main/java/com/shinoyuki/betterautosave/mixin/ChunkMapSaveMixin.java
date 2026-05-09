package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.state.ChunkSaveStateAccess;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v0.4: 拦 vanilla {@code ChunkMap.save(ChunkAccess)} HEAD,把 unload 路径
 * (scheduleUnload 内 lambda) 与 eager save 路径 (saveChunkIfNeeded 内 cooldown
 * 触发) 一并接管走 {@link SnapshotPipeline} 异步管线, 主线程 NBT 编码消除。
 *
 * <p>注意 vanilla 还有第三个调用点: {@code saveAllChunks(true)} (关服 flush)
 * 中的 {@code .filter(this::save)}. 该路径必须走 vanilla 同步, 由
 * {@link SaveScheduler#isShutdownMode()} 守卫避开 — 关服已在
 * {@code BetterAutoSaveMod.onServerStopping} 调 {@code enterShutdownMode()}.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSaveMixin {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private PoiManager poiManager;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void betterautosave$interceptSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }
        if (!BetterAutoSaveConfig.enabled()) {
            return;
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline == null || pipeline.isDegraded()) {
            return;
        }
        SaveScheduler scheduler = BetterAutoSaveCore.scheduler();
        if (scheduler == null || scheduler.isShutdownMode()) {
            return;
        }

        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        if (metrics == null) {
            return;
        }

        if (!(chunk instanceof LevelChunk levelChunk)) {
            // 非 LevelChunk (ProtoChunk / ImposterProtoChunk) 不接管, 让 vanilla
            // 自己处理 (POI flush + isUnsaved 检查 + 可能的 ChunkSerializer.write).
            metrics.recordChunkMapSaveBypass();
            return;
        }
        if (!chunk.isUnsaved()) {
            // chunk 已 clean (BAS 异步路径已 save 过, 或 vanilla 上次已 save).
            // 关键优化 (v0.5.1): vanilla saveChunkIfNeeded 内的 cooldown 仅在 save
            // 返 true 时才更新; 我们 return 不 cancel 让 vanilla 第二行返 false,
            // cooldown 不更新 -> 下 tick 又被检查 -> mixin bypass 暴涨 (生产 ~100k/s).
            //
            // 修复: 手动 flush POI (与 vanilla 行为等价, vanilla save 第一行就 flush
            // POI 不论 isUnsaved), 然后 cancel + setReturnValue(true), 让
            // saveChunkIfNeeded 把 cooldown 设到 10s 后, 该 chunk 安静一段时间.
            //
            // 副作用: setReturnValue(true) 让 saveChunkIfNeeded 的 l 计数器累加,
            // 更快达到 20 跳出循环, 减少 visibleChunkMap 全扫成本.
            poiManager.flush(chunk.getPos());
            metrics.recordChunkMapSaveBypass();
            cir.setReturnValue(true);
            return;
        }

        long packed = chunk.getPos().toLong();
        String dimensionId = level.dimension().location().toString();
        long sequence = scheduler.nextEnqueueSequence();
        ChunkSaveState state = ((ChunkSaveStateAccess) chunk).betterautosave$getOrCreateState(
                packed, dimensionId, sequence);

        ChunkSaveState.Phase currentPhase = state.phase();
        if (currentPhase == ChunkSaveState.Phase.CLEAN) {
            state.markDirty();
        } else if (currentPhase != ChunkSaveState.Phase.DIRTY
                && currentPhase != ChunkSaveState.Phase.FAILED) {
            // SNAPSHOTTING / SERIALIZING / IO_PENDING — async pipeline 已在跑,
            // 不再重复 dispatch, 但确保 mustDrain 标记好让关服 join 知道还要等。
            if (state.tryMarkMustDrain()) {
                metrics.incMustDrainPending();
            }
            metrics.recordChunkMapSaveAsync();
            cir.setReturnValue(true);
            return;
        } else if (currentPhase == ChunkSaveState.Phase.FAILED) {
            // 之前用尽重试, vanilla 同步 save 兜底, 不接管。
            metrics.recordChunkMapSaveFallback();
            return;
        }

        if (state.tryMarkMustDrain()) {
            metrics.incMustDrainPending();
        }

        try {
            boolean dispatched = pipeline.captureAndDispatchChunk(levelChunk, level, state);
            if (dispatched) {
                metrics.recordChunkMapSaveAsync();
                cir.setReturnValue(true);
                return;
            }
            // trySnapshot CAS 失败: 已被另一线程接管, 复用其结果。
            metrics.recordChunkMapSaveAsync();
            cir.setReturnValue(true);
        } catch (Throwable t) {
            // dispatch 异常: 清回 mustDrain 让 vanilla 同步路径处理, 不挂 gauge。
            if (state.compareAndClearMustDrain()) {
                metrics.decMustDrainPending();
            }
            // v0.7.1 修复 (M3): capture 抛后 phase 已被 enterSerializing 推到 SERIALIZING,
            // 或 trySnapshot 已推到 SNAPSHOTTING. catch 不复位 phase 会让该 chunk 后续永远走
            // mixin line 104-115 早 return 路径 (phase 非 DIRTY/FAILED), 既不入 BAS worker 也不
            // 走 vanilla 同步, 数据永久丢失而无 telemetry. resetAfterFallback 把 phase 归零到
            // CLEAN + 清 retryCount, 配合 vanilla 同步路径接管把状态重置干净.
            state.resetAfterFallback();
            metrics.recordChunkMapSaveFallback();
            LOGGER.error("[BetterAutoSave] ChunkMap.save async dispatch failed for {} dim={}, falling back to vanilla",
                    chunk.getPos(), dimensionId, t);
        }
    }
}
