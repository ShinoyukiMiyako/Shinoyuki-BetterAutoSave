package com.shinoyuki.betterautosave;

import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.DiagnosticLogger;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;

public final class BetterAutoSaveCore {

    private static volatile SaveMetrics METRICS;
    private static volatile SaveScheduler SCHEDULER;
    private static volatile SnapshotPipeline PIPELINE;
    private static volatile AsyncIoBridge IO_BRIDGE;
    private static volatile DiagnosticLogger DIAGNOSTIC_LOGGER;

    public static void install(SaveMetrics metrics,
                               SaveScheduler scheduler,
                               SnapshotPipeline pipeline,
                               AsyncIoBridge ioBridge,
                               DiagnosticLogger diagnosticLogger) {
        METRICS = metrics;
        SCHEDULER = scheduler;
        PIPELINE = pipeline;
        IO_BRIDGE = ioBridge;
        DIAGNOSTIC_LOGGER = diagnosticLogger;
    }

    public static void uninstall() {
        METRICS = null;
        SCHEDULER = null;
        PIPELINE = null;
        IO_BRIDGE = null;
        DIAGNOSTIC_LOGGER = null;
    }

    public static SaveMetrics metrics() {
        return METRICS;
    }

    public static SaveScheduler scheduler() {
        return SCHEDULER;
    }

    public static SnapshotPipeline pipeline() {
        return PIPELINE;
    }

    public static AsyncIoBridge ioBridge() {
        return IO_BRIDGE;
    }

    public static DiagnosticLogger diagnosticLogger() {
        return DIAGNOSTIC_LOGGER;
    }

    public static boolean isInstalled() {
        return PIPELINE != null;
    }

    private BetterAutoSaveCore() {
    }
}
