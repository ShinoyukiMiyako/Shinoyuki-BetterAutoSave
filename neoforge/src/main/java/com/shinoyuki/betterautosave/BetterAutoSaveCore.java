package com.shinoyuki.betterautosave;

import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.ChunkLatencyTracker;
import com.shinoyuki.betterautosave.diagnostic.DiagnosticLogger;
import com.shinoyuki.betterautosave.diagnostic.PrometheusExporter;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;

public final class BetterAutoSaveCore {

    private static volatile SaveMetrics METRICS;
    private static volatile SaveScheduler SCHEDULER;
    private static volatile SnapshotPipeline PIPELINE;
    private static volatile AsyncIoBridge IO_BRIDGE;
    private static volatile DiagnosticLogger DIAGNOSTIC_LOGGER;
    private static volatile PrometheusExporter EXPORTER;
    private static volatile ChunkLatencyTracker LATENCY_TRACKER;

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
        EXPORTER = null;
        LATENCY_TRACKER = null;
    }

    /**
     * v0.9: 单独 setter 跟 install 解耦. exporter 是可选组件
     * (config.prometheusEnabled=false 时不实例化), 启动失败时也仅 setter
     * 不调用. install 签名保持 5 参数稳定.
     */
    public static void setExporter(PrometheusExporter exporter) {
        EXPORTER = exporter;
    }

    public static PrometheusExporter exporter() {
        return EXPORTER;
    }

    /**
     * v0.9: ChunkLatencyTracker 给 hottest-chunks 命令提供数据源.
     * 永远启用 (内存可控 ~MB), 用 setter 注入跟 exporter 一致风格.
     */
    public static void setLatencyTracker(ChunkLatencyTracker tracker) {
        LATENCY_TRACKER = tracker;
    }

    public static ChunkLatencyTracker latencyTracker() {
        return LATENCY_TRACKER;
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
