package com.shinoyuki.betterautosave.diagnostic;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import org.slf4j.Logger;

public final class DiagnosticLogger {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final SaveMetrics metrics;
    private int tickCounter;
    private long lastSubmittedSeen;
    private long lastChunkMapSaveAsyncSeen;
    private long lastEntitiesSubmittedSeen;
    private long lastSavedDataSubmittedSeen;

    public DiagnosticLogger(SaveMetrics metrics) {
        this.metrics = metrics;
    }

    public void onServerTick() {
        if (!BetterAutoSaveConfig.diagnosticLogging()) {
            tickCounter = 0;
            return;
        }
        int interval = BetterAutoSaveConfig.diagnosticLogIntervalTicks();
        if (++tickCounter < interval) {
            return;
        }
        tickCounter = 0;
        emit();
    }

    public void emit() {
        SaveMetrics.Snapshot snap = metrics.snapshot();
        boolean idle = snap.chunksSubmitted() == lastSubmittedSeen
                && snap.chunkMapSaveAsync() == lastChunkMapSaveAsyncSeen
                && snap.entitiesSubmitted() == lastEntitiesSubmittedSeen
                && snap.savedDataSubmitted() == lastSavedDataSubmittedSeen
                && snap.workerQueueDepth() == 0L
                && snap.entityQueueDepth() == 0L
                && snap.inFlightSerializing() == 0L
                && snap.inFlightIoPending() == 0L
                && snap.mustDrainPending() == 0L;
        if (idle) {
            return;
        }
        lastSubmittedSeen = snap.chunksSubmitted();
        lastChunkMapSaveAsyncSeen = snap.chunkMapSaveAsync();
        lastEntitiesSubmittedSeen = snap.entitiesSubmitted();
        lastSavedDataSubmittedSeen = snap.savedDataSubmitted();
        LOGGER.info("[BetterAutoSave] metrics");
        LOGGER.info("[BetterAutoSave]   |- chunks: submitted={} completed={} failed={} retried={} fallback={}",
                snap.chunksSubmitted(),
                snap.chunksCompleted(),
                snap.chunksFailed(),
                snap.chunksRetried(),
                snap.chunksFallback());
        LOGGER.info("[BetterAutoSave]   |- chunkMapSave: async={} fallback={} bypass={} mustDrainPending={}",
                snap.chunkMapSaveAsync(),
                snap.chunkMapSaveFallback(),
                snap.chunkMapSaveBypass(),
                snap.mustDrainPending());
        if (snap.entitiesSubmitted() > 0L || snap.entityQueueDepth() > 0L) {
            LOGGER.info("[BetterAutoSave]   |- entities: submitted={} completed={} failed={} retried={} fallback={}",
                    snap.entitiesSubmitted(),
                    snap.entitiesCompleted(),
                    snap.entitiesFailed(),
                    snap.entitiesRetried(),
                    snap.entitiesFallback());
        }
        if (snap.savedDataSubmitted() > 0L) {
            LOGGER.info("[BetterAutoSave]   |- savedData: submitted={} completed={} failed={} fallback={}",
                    snap.savedDataSubmitted(),
                    snap.savedDataCompleted(),
                    snap.savedDataFailed(),
                    snap.savedDataFallback());
        }
        LOGGER.info("[BetterAutoSave]   |- queue: chunkDepth={} entityDepth={}",
                snap.workerQueueDepth(),
                snap.entityQueueDepth());
        LOGGER.info("[BetterAutoSave]   |- inflight: serializing={} ioPending={}",
                snap.inFlightSerializing(),
                snap.inFlightIoPending());
        LOGGER.info("[BetterAutoSave]   `- latency p50/p99 (us): capture={}/{} worker={}/{} io={}/{}",
                SaveMetrics.formatLatencyUs(snap.mainThreadCapture().p50Ns()),
                SaveMetrics.formatLatencyUs(snap.mainThreadCapture().p99Ns()),
                SaveMetrics.formatLatencyUs(snap.workerNbtBuild().p50Ns()),
                SaveMetrics.formatLatencyUs(snap.workerNbtBuild().p99Ns()),
                SaveMetrics.formatLatencyUs(snap.ioStore().p50Ns()),
                SaveMetrics.formatLatencyUs(snap.ioStore().p99Ns()));
    }
}
