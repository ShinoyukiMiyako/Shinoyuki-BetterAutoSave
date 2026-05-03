package com.shinoyuki.betterautosave.diagnostic;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import org.slf4j.Logger;

public final class DiagnosticLogger {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final SaveMetrics metrics;
    private int tickCounter;
    private long lastSubmittedSeen;

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
                && snap.workerQueueDepth() == 0L
                && snap.inFlightSerializing() == 0L
                && snap.inFlightIoPending() == 0L;
        if (idle) {
            return;
        }
        lastSubmittedSeen = snap.chunksSubmitted();
        LOGGER.info(
                "BetterAutoSave metrics: submitted={} completed={} failed={} retried={} fallback={} workerQueue={} inFlight={}/{} captureP99={}us workerP99={}us ioP99={}us",
                snap.chunksSubmitted(),
                snap.chunksCompleted(),
                snap.chunksFailed(),
                snap.chunksRetried(),
                snap.chunksFallback(),
                snap.workerQueueDepth(),
                snap.inFlightSerializing(),
                snap.inFlightIoPending(),
                snap.mainThreadCapture().p99Ns() / 1000,
                snap.workerNbtBuild().p99Ns() / 1000,
                snap.ioStore().p99Ns() / 1000
        );
    }
}
