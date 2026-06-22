package com.shinoyuki.betterautosave.config;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

public final class BetterAutoSaveConfig {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private static volatile boolean enabled;
    private static volatile int chunksPerTickBase;
    private static volatile int entityChunksPerTickBase;
    private static volatile int workerThreads;
    private static volatile int entityWorkerThreads;
    private static volatile int savedDataWorkerThreads;
    private static volatile boolean adaptiveEnabled;
    private static volatile int shutdownTimeoutSeconds;
    private static volatile int deadlineGuardSeconds;
    private static volatile int maxRetries;
    private static volatile int savedDataMaxFileSizeMB;
    private static volatile ConfigSpec.EventCompatMode eventCompatMode;
    private static volatile boolean diagnosticLogging;
    private static volatile int diagnosticLogIntervalTicks;
    private static volatile boolean prometheusEnabled;
    private static volatile String prometheusBindAddress;
    private static volatile int prometheusPort;
    private static volatile int hottestChunksWindowSize;
    private static volatile int hottestChunksTrackLimit;

    public static boolean enabled() {
        return enabled;
    }

    public static int chunksPerTickBase() {
        return chunksPerTickBase;
    }

    public static int entityChunksPerTickBase() {
        return entityChunksPerTickBase;
    }

    public static int workerThreads() {
        return workerThreads;
    }

    public static int entityWorkerThreads() {
        return entityWorkerThreads;
    }

    public static int savedDataWorkerThreads() {
        return savedDataWorkerThreads;
    }

    public static boolean adaptiveEnabled() {
        return adaptiveEnabled;
    }

    public static int shutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public static int deadlineGuardSeconds() {
        return deadlineGuardSeconds;
    }

    public static int maxRetries() {
        return maxRetries;
    }

    public static int savedDataMaxFileSizeMB() {
        return savedDataMaxFileSizeMB;
    }

    public static ConfigSpec.EventCompatMode eventCompatMode() {
        return eventCompatMode;
    }

    public static boolean diagnosticLogging() {
        return diagnosticLogging;
    }

    public static int diagnosticLogIntervalTicks() {
        return diagnosticLogIntervalTicks;
    }

    public static boolean prometheusEnabled() {
        return prometheusEnabled;
    }

    public static String prometheusBindAddress() {
        return prometheusBindAddress;
    }

    public static int prometheusPort() {
        return prometheusPort;
    }

    public static int hottestChunksWindowSize() {
        return hottestChunksWindowSize;
    }

    public static int hottestChunksTrackLimit() {
        return hottestChunksTrackLimit;
    }

    public static void onLoad(ModConfigEvent.Loading event) {
        refresh();
        LOGGER.info("[BetterAutoSave] config loaded enabled={} chunksPerTickBase={} workers={} eventCompat={}",
                enabled, chunksPerTickBase, workerThreads, eventCompatMode);
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        refresh();
        LOGGER.info("[BetterAutoSave] config reloaded enabled={} chunksPerTickBase={} workers={} eventCompat={}",
                enabled, chunksPerTickBase, workerThreads, eventCompatMode);
    }

    private static void refresh() {
        enabled = ConfigSpec.ENABLED.get();
        chunksPerTickBase = ConfigSpec.CHUNKS_PER_TICK_BASE.get();
        entityChunksPerTickBase = ConfigSpec.ENTITY_CHUNKS_PER_TICK_BASE.get();
        workerThreads = ConfigSpec.WORKER_THREADS.get();
        entityWorkerThreads = ConfigSpec.ENTITY_WORKER_THREADS.get();
        savedDataWorkerThreads = ConfigSpec.SAVED_DATA_WORKER_THREADS.get();
        adaptiveEnabled = ConfigSpec.ADAPTIVE_ENABLED.get();
        shutdownTimeoutSeconds = ConfigSpec.SHUTDOWN_TIMEOUT_SECONDS.get();
        deadlineGuardSeconds = ConfigSpec.DEADLINE_GUARD_SECONDS.get();
        maxRetries = ConfigSpec.MAX_RETRIES.get();
        savedDataMaxFileSizeMB = ConfigSpec.SAVED_DATA_MAX_FILE_SIZE_MB.get();
        eventCompatMode = ConfigSpec.EVENT_COMPAT_MODE.get();
        diagnosticLogging = ConfigSpec.DIAGNOSTIC_LOGGING.get();
        diagnosticLogIntervalTicks = ConfigSpec.DIAGNOSTIC_LOG_INTERVAL_TICKS.get();
        prometheusEnabled = ConfigSpec.PROMETHEUS_ENABLED.get();
        prometheusBindAddress = ConfigSpec.PROMETHEUS_BIND_ADDRESS.get();
        prometheusPort = ConfigSpec.PROMETHEUS_PORT.get();
        hottestChunksWindowSize = ConfigSpec.HOTTEST_CHUNKS_WINDOW_SIZE.get();
        hottestChunksTrackLimit = ConfigSpec.HOTTEST_CHUNKS_TRACK_LIMIT.get();
    }

    private BetterAutoSaveConfig() {
    }
}
