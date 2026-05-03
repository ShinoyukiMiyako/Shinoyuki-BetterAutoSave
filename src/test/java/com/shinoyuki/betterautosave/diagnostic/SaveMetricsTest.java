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
    void max_tracks_outlier_while_p99_reflects_majority() {
        SaveMetrics m = new SaveMetrics();
        for (int i = 0; i < 99; i++) {
            m.recordWorkerBuildNs(50_000L);
        }
        m.recordWorkerBuildNs(800_000_000L);

        SaveMetrics.HistogramSnapshot h = m.snapshot().workerNbtBuild();
        assertEquals(100, h.count());
        assertEquals(800_000_000L, h.maxNs(),
                "max must reflect the single outlier exactly");
        assertTrue(h.p99Ns() <= 1_000_000L,
                "1 percent outlier in 100 samples must not pull p99 into a high bucket; got " + h.p99Ns());
    }

    @Test
    void p99_reaches_outlier_bucket_when_outliers_exceed_one_percent() {
        SaveMetrics m = new SaveMetrics();
        for (int i = 0; i < 90; i++) {
            m.recordWorkerBuildNs(50_000L);
        }
        for (int i = 0; i < 10; i++) {
            m.recordWorkerBuildNs(800_000_000L);
        }

        SaveMetrics.HistogramSnapshot h = m.snapshot().workerNbtBuild();
        assertEquals(100, h.count());
        assertTrue(h.p99Ns() >= 500_000_000L,
                "10 of 100 samples in the 1s bucket must lift p99 to that bucket; got " + h.p99Ns());
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
