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
            metrics.recordChunkMapSaveBypass();
            return;
        }
        if (!chunk.isUnsaved()) {
            metrics.recordChunkMapSaveBypass();
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
            metrics.recordChunkMapSaveFallback();
            LOGGER.error("[BetterAutoSave] ChunkMap.save async dispatch failed for {} dim={}, falling back to vanilla",
                    chunk.getPos(), dimensionId, t);
        }
    }
}
