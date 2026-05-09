package com.shinoyuki.betterautosave.diagnostic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusFormatterTest {

    @Test
    void empty_metrics_emit_zero_valued_counters_gauges_histograms() {
        SaveMetrics m = new SaveMetrics();
        String out = PrometheusFormatter.format(m.snapshot());

        assertTrue(out.contains("# TYPE bas_chunks_submitted_total counter\n"),
                "counter TYPE 行缺失:\n" + out);
        assertTrue(out.contains("\nbas_chunks_submitted_total 0\n"),
                "空 counter 应输出 0:\n" + out);
        assertTrue(out.contains("# TYPE bas_must_drain_pending gauge\n"));
        assertTrue(out.contains("\nbas_must_drain_pending 0\n"));
        assertTrue(out.contains("# TYPE bas_io_store_seconds histogram\n"));
        assertTrue(out.contains("\nbas_io_store_seconds_count 0\n"));
        assertTrue(out.contains("\nbas_io_store_seconds_sum 0.0\n"),
                "空 histogram sum 应为 0.0 而不是 NaN:\n" + out);
        assertTrue(out.contains("bas_io_store_seconds_bucket{le=\"+Inf\"} 0\n"));
    }

    @Test
    void counters_reflect_recorded_values() {
        SaveMetrics m = new SaveMetrics();
        m.recordChunkSubmitted();
        m.recordChunkSubmitted();
        m.recordChunkSubmitted();
        m.recordChunkCompleted();
        m.recordSavedDataFallback();
        m.recordEntityFailed();
        m.recordEntityFailed();

        String out = PrometheusFormatter.format(m.snapshot());
        assertTrue(out.contains("\nbas_chunks_submitted_total 3\n"));
        assertTrue(out.contains("\nbas_chunks_completed_total 1\n"));
        assertTrue(out.contains("\nbas_saved_data_fallback_total 1\n"));
        assertTrue(out.contains("\nbas_entities_failed_total 2\n"));
        // 未触发的 counter 仍出现且为 0 (Prometheus 习惯: 暴露所有 metric 即使 0)
        assertTrue(out.contains("\nbas_chunks_failed_total 0\n"));
    }

    @Test
    void gauges_reflect_inflight_and_queue_state() {
        SaveMetrics m = new SaveMetrics();
        m.incInFlightSerializing();
        m.incInFlightSerializing();
        m.incInFlightIoPending();
        m.setWorkerQueueDepth(7L);
        m.setEntityQueueDepth(3L);
        m.incMustDrainPending();

        String out = PrometheusFormatter.format(m.snapshot());
        assertTrue(out.contains("\nbas_in_flight_serializing 2\n"));
        assertTrue(out.contains("\nbas_in_flight_io_pending 1\n"));
        assertTrue(out.contains("\nbas_worker_queue_depth 7\n"));
        assertTrue(out.contains("\nbas_entity_queue_depth 3\n"));
        assertTrue(out.contains("\nbas_must_drain_pending 1\n"));
    }

    @Test
    void histogram_buckets_are_cumulative_not_per_bucket() {
        SaveMetrics m = new SaveMetrics();
        // 3 个样本各落在不同 bucket: 100us / 1ms / 50ms
        m.recordCaptureNs(100_000L);
        m.recordCaptureNs(1_000_000L);
        m.recordCaptureNs(50_000_000L);

        String out = PrometheusFormatter.format(m.snapshot());

        // 100us bucket cumulative = 1 (100us 自己)
        assertTrue(out.contains("bas_main_thread_capture_seconds_bucket{le=\"1.0E-4\"} 1\n"),
                "100us bucket cumulative 应为 1:\n" + out);
        // 1ms bucket cumulative = 2 (100us + 1ms)
        assertTrue(out.contains("bas_main_thread_capture_seconds_bucket{le=\"0.001\"} 2\n"),
                "1ms bucket cumulative 应为 2:\n" + out);
        // 50ms bucket cumulative = 3
        assertTrue(out.contains("bas_main_thread_capture_seconds_bucket{le=\"0.05\"} 3\n"));
        // +Inf 总数必须 = count
        assertTrue(out.contains("bas_main_thread_capture_seconds_bucket{le=\"+Inf\"} 3\n"));
        assertTrue(out.contains("bas_main_thread_capture_seconds_count 3\n"));
        // sum = (100_000 + 1_000_000 + 50_000_000) / 1e9 = 0.0511
        assertTrue(out.contains("bas_main_thread_capture_seconds_sum 0.0511\n"),
                "sum 应转秒输出 0.0511:\n" + out);
    }

    @Test
    void histogram_overflow_bucket_renders_plus_inf_not_long_max_value() {
        SaveMetrics m = new SaveMetrics();
        // 120s 超过 60s 上界, 落到 Long.MAX_VALUE bucket
        m.recordIoStoreNs(120_000_000_000L);

        String out = PrometheusFormatter.format(m.snapshot());
        // 60s bucket cumulative = 0 (没样本 ≤60s)
        assertTrue(out.contains("bas_io_store_seconds_bucket{le=\"60.0\"} 0\n"),
                "60s bucket 不该计入 120s 样本:\n" + out);
        // +Inf bucket = 1
        assertTrue(out.contains("bas_io_store_seconds_bucket{le=\"+Inf\"} 1\n"));
        // 不能 leak Long.MAX_VALUE 字面量到输出
        assertFalse(out.contains("9223372036854775807"),
                "exposition 不能 leak Long.MAX_VALUE 数值:\n" + out);
        assertFalse(out.contains("9.223372036854776E18"));
    }

    @Test
    void all_metric_names_use_bas_prefix() {
        SaveMetrics m = new SaveMetrics();
        String out = PrometheusFormatter.format(m.snapshot());
        int typeCount = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith("# TYPE ")) {
                typeCount++;
                String name = line.substring("# TYPE ".length()).split(" ")[0];
                assertTrue(name.startsWith("bas_"),
                        "所有 metric 名必须以 bas_ 开头, 违反: " + name);
            }
        }
        // 17 counter + 5 gauge + 4 histogram = 26 个 # TYPE 声明, 防漏报
        assertEquals(26, typeCount,
                "应输出 17+5+4=26 个 # TYPE 行, actual=" + typeCount);
    }

    @Test
    void each_metric_has_help_then_type_then_value() {
        SaveMetrics m = new SaveMetrics();
        m.recordChunkSubmitted();
        String out = PrometheusFormatter.format(m.snapshot());
        int helpIdx = out.indexOf("# HELP bas_chunks_submitted_total");
        int typeIdx = out.indexOf("# TYPE bas_chunks_submitted_total");
        int valueIdx = out.indexOf("\nbas_chunks_submitted_total 1\n");
        assertTrue(helpIdx >= 0, "缺 HELP");
        assertTrue(typeIdx > helpIdx, "TYPE 必须在 HELP 之后");
        assertTrue(valueIdx > typeIdx, "value 必须在 TYPE 之后");
    }
}
