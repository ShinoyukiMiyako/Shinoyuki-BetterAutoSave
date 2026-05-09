package com.shinoyuki.betterautosave.diagnostic;

import com.shinoyuki.betterautosave.diagnostic.SaveMetrics.HistogramSnapshot;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics.Snapshot;

/**
 * v0.9: 把 {@link SaveMetrics.Snapshot} 转成 Prometheus exposition format
 * (https://prometheus.io/docs/instrumenting/exposition_formats/), 给
 * {@link PrometheusExporter} HTTP /metrics 端点直接返回字符串.
 *
 * <p>所有 metric 用 {@code bas_} 前缀避免跟其他 Shinoyuki 系列 mod
 * (BetterBackup 等) 共享同一 Prometheus 实例时命名冲突.
 *
 * <p><b>Histogram 累加转换</b>: {@link SaveMetrics.Histogram} 内部
 * bucketCounts 是 per-bucket 计数 (落在该 bucket 区间的样本数),
 * Prometheus {@code le="x"} 标签语义是 cumulative (≤ x 的所有样本数),
 * formatter 必须做累加. {@code Long.MAX_VALUE} 上界 bucket 输出
 * {@code +Inf} 标签.
 *
 * <p><b>纳秒转秒</b>: BAS 内部 Histogram 单位是 ns, Prometheus 标准
 * histogram unit 是秒. 用 {@link Double#toString} 输出, Java 在
 * 极小/极大值会自动切换 E notation, Prometheus 接受 Go strconv 'g'
 * verb 等价格式 (E notation 兼容).
 */
public final class PrometheusFormatter {

    private PrometheusFormatter() {
    }

    public static String format(Snapshot snap) {
        StringBuilder sb = new StringBuilder(8192);

        // chunk 路径 counter (v0.2 起)
        counter(sb, "bas_chunks_submitted_total",
                "Chunks submitted to BAS pipeline (cumulative)", snap.chunksSubmitted());
        counter(sb, "bas_chunks_completed_total",
                "Chunks landed on disk (cumulative)", snap.chunksCompleted());
        counter(sb, "bas_chunks_failed_total",
                "Chunks parked in FAILED state after retries exhausted", snap.chunksFailed());
        counter(sb, "bas_chunks_retried_total",
                "Chunk re-queue events", snap.chunksRetried());
        counter(sb, "bas_chunks_fallback_total",
                "Chunks routed to vanilla synchronous fallback", snap.chunksFallback());

        // ChunkMap.save 接管 (v0.4) counter
        counter(sb, "bas_chunk_map_save_async_total",
                "ChunkMap.save calls intercepted to async path", snap.chunkMapSaveAsync());
        counter(sb, "bas_chunk_map_save_fallback_total",
                "ChunkMap.save fallbacks to vanilla", snap.chunkMapSaveFallback());
        counter(sb, "bas_chunk_map_save_bypass_total",
                "ChunkMap.save calls bypassed (chunk already clean)", snap.chunkMapSaveBypass());

        // Entity 路径 counter (v0.6)
        counter(sb, "bas_entities_submitted_total",
                "Entity chunk sections submitted to BAS pipeline", snap.entitiesSubmitted());
        counter(sb, "bas_entities_completed_total",
                "Entity chunk sections landed", snap.entitiesCompleted());
        counter(sb, "bas_entities_failed_total",
                "Entity chunk sections in FAILED state", snap.entitiesFailed());
        counter(sb, "bas_entities_retried_total",
                "Entity chunk section retries", snap.entitiesRetried());
        counter(sb, "bas_entities_fallback_total",
                "Entity chunk sections routed to vanilla", snap.entitiesFallback());

        // SavedData 路径 counter (v0.7)
        counter(sb, "bas_saved_data_submitted_total",
                "SavedData files submitted to async write", snap.savedDataSubmitted());
        counter(sb, "bas_saved_data_completed_total",
                "SavedData files landed", snap.savedDataCompleted());
        counter(sb, "bas_saved_data_failed_total",
                "SavedData IO failures (re-marked dirty for retry)", snap.savedDataFailed());
        counter(sb, "bas_saved_data_fallback_total",
                "SavedData routed to vanilla synchronous (file too large or mod serialization threw)",
                snap.savedDataFallback());

        // gauge
        gauge(sb, "bas_must_drain_pending",
                "Number of chunks pending mustDrain (v0.4 unload/eager save)", snap.mustDrainPending());
        gauge(sb, "bas_worker_queue_depth",
                "Chunk worker queue depth", snap.workerQueueDepth());
        gauge(sb, "bas_entity_queue_depth",
                "Entity worker queue depth", snap.entityQueueDepth());
        gauge(sb, "bas_in_flight_serializing",
                "Snapshots currently in SERIALIZING state", snap.inFlightSerializing());
        gauge(sb, "bas_in_flight_io_pending",
                "Snapshots currently in IO_PENDING state", snap.inFlightIoPending());

        // histogram
        histogram(sb, "bas_main_thread_capture_seconds",
                "Main thread snapshot capture latency", snap.mainThreadCapture());
        histogram(sb, "bas_worker_nbt_build_seconds",
                "Worker NBT assembly latency", snap.workerNbtBuild());
        histogram(sb, "bas_io_store_seconds",
                "Vanilla IOWorker store latency (region file write)", snap.ioStore());
        histogram(sb, "bas_event_dispatch_seconds",
                "ChunkDataEvent.Save dispatch latency", snap.eventDispatch());

        return sb.toString();
    }

    private static void counter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void histogram(StringBuilder sb, String name, String help, HistogramSnapshot h) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" histogram\n");
        long[] bounds = SaveMetrics.bucketUpperBoundsNs();
        long[] counts = h.bucketCounts();
        long cumulative = 0;
        for (int i = 0; i < bounds.length; i++) {
            cumulative += counts[i];
            sb.append(name).append("_bucket{le=\"");
            if (bounds[i] == SaveMetrics.OVERFLOW_BUCKET_UPPER_NS) {
                sb.append("+Inf");
            } else {
                sb.append(secondsString(bounds[i]));
            }
            sb.append("\"} ").append(cumulative).append('\n');
        }
        sb.append(name).append("_sum ").append(secondsString(h.sumNs())).append('\n');
        sb.append(name).append("_count ").append(h.count()).append('\n');
    }

    private static String secondsString(long nanos) {
        return Double.toString(nanos / 1_000_000_000.0);
    }
}
