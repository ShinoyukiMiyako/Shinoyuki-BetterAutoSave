package com.shinoyuki.betterautosave.core.dispatch;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.core.scheduler.ChunkSavePriority;
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

public final class SaveDispatcher implements SnapshotPipeline.ChunkResolutionHook, SnapshotPipeline.EntityResolutionHook {

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
            metrics.recordChunkFailed();
            LOGGER.error("[BetterAutoSave] async dispatch failed for {} dim={}, falling back",
                    pos, priority.dimensionId(), t);
        }
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
