package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.dispatch.SaveDispatcher;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.ChunkCaptureProcedure;
import com.shinoyuki.betterautosave.core.snapshot.ChunkSnapshot;
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
            // SNAPSHOTTING / SERIALIZING / IO_PENDING — async pipeline 已在跑某代 IO。
            // mustDrain 必须先于接力快照登记置位 (gauge 不变式: pendingSnapshot 非空 -> mustDrain 恒真),
            // 关服 join 才会等接力链落地。
            if (state.tryMarkMustDrain()) {
                metrics.incMustDrainPending();
            }
            // v0.10.2 修复 (C-chunk-unload-collision): 在飞期间该 chunk 又被编辑 (generation 前进)
            // 又触发 save (典型: 卸载序列调 ChunkMap.save)。旧逻辑只 setReturnValue(true) 信任在飞的旧代
            // 快照, 而卸载随即把 chunk 驱逐出内存 —— 编辑后那代增量永久静默丢失。现在对最新内存做一次纯
            // capture (不碰在飞那代的 phase/inFlightGeneration, 见 capturePending) 登记进接力槽; 在飞那代
            // IO 落地判 REQUEUE_DIRTY 时回调取出重投, 把最新代落盘。隐角 A: 多代碰撞链每次命中都登记更新代
            // (隐角 B 最新者胜, 旧 pending 作废), inFlightGeneration 随接力 dispatch 推进。
            if (state.generation() != state.inFlightGeneration()) {
                try {
                    ChunkSnapshot pending = ChunkCaptureProcedure.capturePending(
                            levelChunk, level, state, BetterAutoSaveConfig.eventCompatMode());
                    state.registerPendingSnapshot(pending);
                } catch (Throwable t) {
                    // 纯 capture 抛 (mod BE/section 序列化异常等): 不污染在飞 task, 退回信任在飞旧代,
                    // 与修复前行为等价 (本次最新代增量按旧路径丢失或靠下轮接管), 但记录可见。
                    LOGGER.error("[BetterAutoSave] pending relay capture failed for in-flight chunk {} dim={}; "
                                    + "trusting in-flight snapshot (latest-generation increment may be lost)",
                            chunk.getPos(), dimensionId, t);
                }
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

        // v0.7.1 修复 (M1): 复制 vanilla ChunkMap.save 第一行 poiManager.flush 副作用.
        // vanilla ChunkMap.java:825 无条件在 save 第一行调 flush(pos) 把该 chunk pos
        // 上 dirty 的 PoiSection 推到 PoiManager IOWorker mailbox. v0.4 异步 dispatch
        // 路径漏掉这步, 破坏 vanilla "POI 不晚于 chunk 进 IOWorker" 顺序保证.
        // 实际不丢数据 (POI 可由 chunk 重建, vanilla PoiManager 是 lazy load), 但顺序
        // 破坏导致崩溃后 POI region file 滞后一个 cycle, 短暂不一致.
        poiManager.flush(chunk.getPos());

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
            // 走 vanilla 同步, 数据永久丢失而无 telemetry.
            //
            // v0.10.2 修复 (C1): capture 第一行已 setUnsaved(false). 这里不 cancel cir,
            // vanilla ChunkMap.save 方法体续跑会撞 isUnsaved 门 (此刻 false) -> 直接 return false
            // 跳过本次同步序列化 -> 本次数据丢失; 该 chunk 此后不再编辑则 unload 与关服 flush
            // 同样按 isUnsaved 门跳过, 永久丢失. 只 resetAfterFallback 归零 phase 不够 (vanilla
            // 重入门看的是 isUnsaved 而非 phase). 改用 recoverAfterDispatchFailure 同时还原
            // isUnsaved=true, 让续跑的 vanilla 方法体过门 -> 当场同步写盘救回本次数据. 与
            // SaveDispatcher.onPriorityDrained catch 的修复同源 (v0.10.1 修 SaveDispatcher 时漏了这里).
            // catch 内已有的 compareAndClearMustDrain 保留: recoverAfterDispatchFailure 不碰 mustDrain, 无重复.
            SaveDispatcher.recoverAfterDispatchFailure(state, levelChunk::setUnsaved);
            metrics.recordChunkMapSaveFallback();
            LOGGER.error("[BetterAutoSave] ChunkMap.save async dispatch failed for {} dim={}, falling back to vanilla",
                    chunk.getPos(), dimensionId, t);
        }
    }
}
