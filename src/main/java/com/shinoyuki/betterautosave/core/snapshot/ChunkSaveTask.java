package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

public final class ChunkSaveTask implements SaveTask {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final ChunkSnapshot snapshot;
    private final ServerLevel level;
    private final AsyncIoBridge ioBridge;
    private final SaveMetrics metrics;

    public ChunkSaveTask(ChunkSnapshot snapshot, ServerLevel level, AsyncIoBridge ioBridge, SaveMetrics metrics) {
        this.snapshot = snapshot;
        this.level = level;
        this.ioBridge = ioBridge;
        this.metrics = metrics;
    }

    @Override
    public String taskName() {
        return "chunk@" + snapshot.pos() + "/" + snapshot.dimension().location();
    }

    @Override
    public void execute() {
        CompoundTag tag = ChunkNbtAssembler.assemble(snapshot);
        metrics.decInFlightSerializing();

        ChunkSaveState state = snapshot.state();
        state.enterIoPending();
        metrics.incInFlightIoPending();
        long submitNs = System.nanoTime();
        CompletableFuture<Void> future = ioBridge.storeChunk(level, snapshot.pos(), tag);
        future.whenComplete((ignored, error) -> {
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            boolean wasDraining = state.mustDrain();
            if (error != null) {
                LOGGER.error("[BetterAutoSave] IO store failed for chunk {} dim={}", snapshot.pos(), snapshot.dimension().location(), error);
                ChunkSaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
                if (wasDraining && !state.mustDrain()) {
                    metrics.decMustDrainPending();
                }
                if (outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL) {
                    metrics.recordChunkFailed();
                } else {
                    metrics.recordChunkRetried();
                }
                return;
            }
            ChunkSaveState.IoOutcome outcome = state.ioCompletedSuccessfully();
            if (wasDraining && !state.mustDrain()) {
                metrics.decMustDrainPending();
            }
            if (outcome == ChunkSaveState.IoOutcome.CLEAN_LANDED) {
                metrics.recordChunkCompleted();
            } else {
                metrics.recordChunkRetried();
            }
        });
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        ChunkSaveState state = snapshot.state();
        boolean wasDraining = state.mustDrain();
        ChunkSaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
        if (wasDraining && !state.mustDrain()) {
            metrics.decMustDrainPending();
        }
        if (outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL) {
            metrics.recordChunkFailed();
        } else {
            metrics.recordChunkRetried();
        }
        LOGGER.error("[BetterAutoSave] worker uncaught for {}", taskName(), cause);
    }
}
