package com.shinoyuki.betterautosave.diagnostic;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class SaveMetrics {

    private static final long[] BUCKET_UPPER_BOUNDS_NS = new long[]{
            10_000L,
            100_000L,
            500_000L,
            1_000_000L,
            5_000_000L,
            10_000_000L,
            50_000_000L,
            100_000_000L,
            500_000_000L,
            1_000_000_000L,
            5_000_000_000L,
            Long.MAX_VALUE
    };

    private final LongAdder chunksSubmitted = new LongAdder();
    private final LongAdder chunksCompleted = new LongAdder();
    private final LongAdder chunksFailed = new LongAdder();
    private final LongAdder chunksRetried = new LongAdder();
    private final LongAdder chunksFallback = new LongAdder();

    private final LongAdder chunkMapSaveAsync = new LongAdder();
    private final LongAdder chunkMapSaveFallback = new LongAdder();
    private final LongAdder chunkMapSaveBypass = new LongAdder();
    private final AtomicLong mustDrainPending = new AtomicLong();

    private final LongAdder entitiesSubmitted = new LongAdder();
    private final LongAdder entitiesCompleted = new LongAdder();
    private final LongAdder entitiesFailed = new LongAdder();

    private final Histogram mainThreadCaptureNs = new Histogram();
    private final Histogram workerNbtBuildNs = new Histogram();
    private final Histogram ioStoreLatencyNs = new Histogram();
    private final Histogram eventDispatchNs = new Histogram();

    private final AtomicLong workerQueueDepth = new AtomicLong();
    private final AtomicLong entityQueueDepth = new AtomicLong();
    private final AtomicLong inFlightSerializing = new AtomicLong();
    private final AtomicLong inFlightIoPending = new AtomicLong();

    public void recordChunkSubmitted() {
        chunksSubmitted.increment();
    }

    public void recordChunkCompleted() {
        chunksCompleted.increment();
    }

    public void recordChunkFailed() {
        chunksFailed.increment();
    }

    public void recordChunkRetried() {
        chunksRetried.increment();
    }

    public void recordChunkFallback() {
        chunksFallback.increment();
    }

    public void recordChunkMapSaveAsync() {
        chunkMapSaveAsync.increment();
    }

    public void recordChunkMapSaveFallback() {
        chunkMapSaveFallback.increment();
    }

    public void recordChunkMapSaveBypass() {
        chunkMapSaveBypass.increment();
    }

    public void incMustDrainPending() {
        mustDrainPending.incrementAndGet();
    }

    public void decMustDrainPending() {
        mustDrainPending.decrementAndGet();
    }

    public void recordEntitySubmitted() {
        entitiesSubmitted.increment();
    }

    public void recordEntityCompleted() {
        entitiesCompleted.increment();
    }

    public void recordEntityFailed() {
        entitiesFailed.increment();
    }

    public void recordCaptureNs(long nanos) {
        mainThreadCaptureNs.add(nanos);
    }

    public void recordWorkerBuildNs(long nanos) {
        workerNbtBuildNs.add(nanos);
    }

    public void recordIoStoreNs(long nanos) {
        ioStoreLatencyNs.add(nanos);
    }

    public void recordEventDispatchNs(long nanos) {
        eventDispatchNs.add(nanos);
    }

    public void setWorkerQueueDepth(long depth) {
        workerQueueDepth.set(depth);
    }

    public void setEntityQueueDepth(long depth) {
        entityQueueDepth.set(depth);
    }

    public void incInFlightSerializing() {
        inFlightSerializing.incrementAndGet();
    }

    public void decInFlightSerializing() {
        inFlightSerializing.decrementAndGet();
    }

    public void incInFlightIoPending() {
        inFlightIoPending.incrementAndGet();
    }

    public void decInFlightIoPending() {
        inFlightIoPending.decrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                chunksSubmitted.sum(),
                chunksCompleted.sum(),
                chunksFailed.sum(),
                chunksRetried.sum(),
                chunksFallback.sum(),
                chunkMapSaveAsync.sum(),
                chunkMapSaveFallback.sum(),
                chunkMapSaveBypass.sum(),
                mustDrainPending.get(),
                entitiesSubmitted.sum(),
                entitiesCompleted.sum(),
                entitiesFailed.sum(),
                mainThreadCaptureNs.snapshot(),
                workerNbtBuildNs.snapshot(),
                ioStoreLatencyNs.snapshot(),
                eventDispatchNs.snapshot(),
                workerQueueDepth.get(),
                entityQueueDepth.get(),
                inFlightSerializing.get(),
                inFlightIoPending.get()
        );
    }

    public static final class Histogram {
        private final LongAdder[] buckets = new LongAdder[BUCKET_UPPER_BOUNDS_NS.length];
        private final LongAdder count = new LongAdder();
        private final LongAdder sumNs = new LongAdder();
        private final AtomicLong maxNs = new AtomicLong();

        public Histogram() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }

        public void add(long nanos) {
            count.increment();
            sumNs.add(nanos);
            for (int i = 0; i < BUCKET_UPPER_BOUNDS_NS.length; i++) {
                if (nanos <= BUCKET_UPPER_BOUNDS_NS[i]) {
                    buckets[i].increment();
                    break;
                }
            }
            long prevMax;
            do {
                prevMax = maxNs.get();
                if (nanos <= prevMax) {
                    return;
                }
            } while (!maxNs.compareAndSet(prevMax, nanos));
        }

        public HistogramSnapshot snapshot() {
            long total = count.sum();
            long[] bucketCounts = new long[buckets.length];
            for (int i = 0; i < buckets.length; i++) {
                bucketCounts[i] = buckets[i].sum();
            }
            long avg = total > 0 ? sumNs.sum() / total : 0;
            return new HistogramSnapshot(total, avg, maxNs.get(), bucketCounts, percentile(bucketCounts, total, 0.5),
                    percentile(bucketCounts, total, 0.99));
        }

        private static long percentile(long[] bucketCounts, long total, double p) {
            if (total <= 0) {
                return 0;
            }
            long target = (long) Math.ceil(total * p);
            long running = 0;
            for (int i = 0; i < bucketCounts.length; i++) {
                running += bucketCounts[i];
                if (running >= target) {
                    return BUCKET_UPPER_BOUNDS_NS[i];
                }
            }
            return BUCKET_UPPER_BOUNDS_NS[BUCKET_UPPER_BOUNDS_NS.length - 1];
        }
    }

    public record HistogramSnapshot(long count, long avgNs, long maxNs, long[] bucketCounts, long p50Ns, long p99Ns) {
    }

    public record Snapshot(
            long chunksSubmitted,
            long chunksCompleted,
            long chunksFailed,
            long chunksRetried,
            long chunksFallback,
            long chunkMapSaveAsync,
            long chunkMapSaveFallback,
            long chunkMapSaveBypass,
            long mustDrainPending,
            long entitiesSubmitted,
            long entitiesCompleted,
            long entitiesFailed,
            HistogramSnapshot mainThreadCapture,
            HistogramSnapshot workerNbtBuild,
            HistogramSnapshot ioStore,
            HistogramSnapshot eventDispatch,
            long workerQueueDepth,
            long entityQueueDepth,
            long inFlightSerializing,
            long inFlightIoPending
    ) {
    }
}
