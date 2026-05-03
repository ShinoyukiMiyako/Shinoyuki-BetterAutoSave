package com.shinoyuki.betterautosave.diagnostic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveMetricsTest {

    @Test
    void counters_accumulate_independently() {
        SaveMetrics m = new SaveMetrics();
        m.recordChunkSubmitted();
        m.recordChunkSubmitted();
        m.recordChunkCompleted();
        m.recordChunkFailed();

        SaveMetrics.Snapshot snap = m.snapshot();
        assertEquals(2, snap.chunksSubmitted());
        assertEquals(1, snap.chunksCompleted());
        assertEquals(1, snap.chunksFailed());
        assertEquals(0, snap.chunksRetried());
    }

    @Test
    void histogram_records_max_and_average() {
        SaveMetrics m = new SaveMetrics();
        m.recordCaptureNs(100_000L);
        m.recordCaptureNs(200_000L);
        m.recordCaptureNs(900_000L);

        SaveMetrics.HistogramSnapshot h = m.snapshot().mainThreadCapture();
        assertEquals(3, h.count());
        assertEquals(900_000L, h.maxNs());
        assertEquals(400_000L, h.avgNs());
    }

    @Test
    void p99_falls_into_largest_observed_bucket() {
        SaveMetrics m = new SaveMetrics();
        for (int i = 0; i < 99; i++) {
            m.recordWorkerBuildNs(50_000L);
        }
        m.recordWorkerBuildNs(800_000_000L);

        SaveMetrics.HistogramSnapshot h = m.snapshot().workerNbtBuild();
        assertEquals(100, h.count());
        assertTrue(h.p99Ns() >= 100_000_000L,
                "p99 should reach the bucket of the 800ms outlier, got " + h.p99Ns());
    }

    @Test
    void in_flight_gauges_track_inc_dec() {
        SaveMetrics m = new SaveMetrics();
        m.incInFlightSerializing();
        m.incInFlightSerializing();
        m.decInFlightSerializing();
        m.incInFlightIoPending();

        SaveMetrics.Snapshot snap = m.snapshot();
        assertEquals(1, snap.inFlightSerializing());
        assertEquals(1, snap.inFlightIoPending());
    }

    @Test
    void empty_histogram_yields_zero_avg_and_zero_p99() {
        SaveMetrics m = new SaveMetrics();
        SaveMetrics.HistogramSnapshot h = m.snapshot().eventDispatch();
        assertEquals(0, h.count());
        assertEquals(0, h.avgNs());
        assertEquals(0, h.p99Ns());
    }
}
