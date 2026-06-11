package com.shinoyuki.betterautosave.core.dispatch;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.core.scheduler.ChunkSavePriority;
import com.shinoyuki.betterautosave.core.snapshot.ChunkRecoveryQueue;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.state.ChunkSaveStateAccess;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.mixin.accessor.ChunkMapAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SaveDispatcher implements SnapshotPipeline.ChunkResolutionHook {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final SnapshotPipeline pipeline;
    private final SaveMetrics metrics;
    private final AtomicBoolean firstSuccessLogged = new AtomicBoolean(false);

    public SaveDispatcher(SnapshotPipeline pipeline, SaveMetrics metrics) {
        this.pipeline = pipeline;
        this.metrics = metrics;
    }

    @Override
    public void onPriorityDrained(ChunkSavePriority priority) {
        metrics.recordChunkSubmitted();

        MinecraftServer server = pipeline.server();
        if (server == null) {
            metrics.recordChunkFallback();
            return;
        }
        ServerLevel target = findLevel(server, priority.dimensionId());
        if (target == null) {
            metrics.recordChunkFallback();
            return;
        }
        ChunkMap chunkMap = target.getChunkSource().chunkMap;
        ChunkPos pos = new ChunkPos(priority.packedPos());
        ChunkHolder holder = ((ChunkMapAccessor) chunkMap).betterautosave$getVisibleChunkMap().get(pos.toLong());
        if (holder == null) {
            metrics.recordChunkFallback();
            return;
        }
        ChunkAccess chunk = holder.getLastAvailable();
        if (!(chunk instanceof LevelChunk levelChunk)) {
            metrics.recordChunkFallback();
            return;
        }
        ChunkSaveState state = ((ChunkSaveStateAccess) chunk).betterautosave$getState();
        if (state == null) {
            metrics.recordChunkFallback();
            return;
        }
        if (!chunk.isUnsaved()) {
            metrics.recordChunkFallback();
            return;
        }

        try {
            boolean dispatched = pipeline.captureAndDispatchChunk(levelChunk, target, state);
            if (!dispatched) {
                metrics.recordChunkFallback();
                return;
            }
            if (firstSuccessLogged.compareAndSet(false, true)) {
                LOGGER.info("[BetterAutoSave] async pipeline verified: first chunk dispatched [{}, {}] @ {}",
                        pos.x, pos.z, priority.dimensionId());
            }
        } catch (Throwable t) {
            // capture 第一行 setUnsaved(false) + enterSerializing 已把 chunk 推到
            // unsaved=false + phase=SERIALIZING. catch 不复位会让该 chunk 永远走
            // 三条重入门 (isUnsaved gate + 非 DIRTY/FAILED phase) 的早 return 路径,
            // 既不入 BAS worker 也不走 vanilla 同步, markDirty 的 CAS(CLEAN->DIRTY) 从
            // SERIALIZING 无法自愈 — 数据永久丢失. 对齐 ChunkMapSaveMixin M3 修复:
            // resetAfterFallback 归零状态机 + setUnsaved(true) 还原 vanilla 重入门,
            // 让下一轮 autosave / vanilla 同步路径接管. onPriorityDrained 跑在主线程
            // (SaveScheduler.onServerTick -> submitChunk -> 本回调), setUnsaved 安全.
            //
            // capture 中途若已 markMustDrain (经 mixin tryMarkMustDrain), 这里不配平 gauge:
            // 本路径的 dispatch 由 SaveDispatcher 直接发起, 未经 mixin 的 mustDrain 标记,
            // mustDrain 配平责任在 mixin catch (ChunkMapSaveMixin:144), 不在此处.
            recoverAfterDispatchFailure(state, levelChunk::setUnsaved);
            metrics.recordChunkFailed();
            LOGGER.error("[BetterAutoSave] async dispatch failed for {} dim={}, falling back",
                    pos, priority.dimensionId(), t);
        }
    }

    /**
     * dispatch 异常后把 chunk 还原成可被任一重入门重新接管的状态.
     * resetAfterFallback 把状态机从 SERIALIZING/SNAPSHOTTING 归零到 CLEAN,
     * setUnsaved(true) 还原 vanilla isUnsaved 重入门 (并经 ChunkAccessMixin
     * 触发 markDirty 把 phase 推回 DIRTY). 两步缺一不可: 只复位 phase 不还原
     * isUnsaved -> vanilla autosave 仍跳过; 只 setUnsaved 不复位 phase ->
     * phase 卡在 SERIALIZING, markDirty 的 CAS(CLEAN->DIRTY) 失败, BAS 重入门跳过.
     *
     * <p>复用 {@link ChunkRecoveryQueue.UnsavedSetter} 抽象 LevelChunk.setUnsaved 以便单测.
     */
    public static void recoverAfterDispatchFailure(ChunkSaveState state, ChunkRecoveryQueue.UnsavedSetter chunk) {
        state.resetAfterFallback();
        chunk.setUnsaved(true);
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimensionId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimensionId)) {
                return level;
            }
        }
        return null;
    }
}
