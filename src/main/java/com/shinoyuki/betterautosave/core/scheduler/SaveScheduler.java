package com.shinoyuki.betterautosave.core.scheduler;

import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class SaveScheduler {

    private final SaveQueue chunkQueue = new SaveQueue();
    private final AdaptiveThrottle chunkThrottle;
    private final SaveMetrics metrics;
    private final AtomicLong enqueueSequence = new AtomicLong();
    private final BooleanSupplier adaptiveEnabled;

    private volatile ChunkSubmissionSink sink;
    private volatile boolean shutdownMode;

    public SaveScheduler(SaveMetrics metrics, int chunksPerTickBase, int deadlineGuardSeconds,
                         BooleanSupplier adaptiveEnabled) {
        this.metrics = metrics;
        this.adaptiveEnabled = adaptiveEnabled;
        this.chunkThrottle = new AdaptiveThrottle(chunksPerTickBase, deadlineGuardSeconds);
    }

    public void attachSink(ChunkSubmissionSink sink) {
        this.sink = sink;
    }

    public long nextEnqueueSequence() {
        return enqueueSequence.incrementAndGet();
    }

    public boolean enqueueChunk(ChunkSavePriority priority) {
        boolean accepted = chunkQueue.offer(priority);
        metrics.setWorkerQueueDepth(chunkQueue.size());
        return accepted;
    }

    public void onServerTick(double avgTickMs, int remainingSecondsInCycle) {
        ChunkSubmissionSink localSink = sink;
        if (localSink == null) {
            return;
        }
        boolean adaptive = adaptiveEnabled.getAsBoolean();

        int chunkBudget = chunkThrottle.adjust(avgTickMs, remainingSecondsInCycle, adaptive, shutdownMode);
        for (int i = 0; i < chunkBudget; i++) {
            ChunkSavePriority p = chunkQueue.poll();
            if (p == null) {
                break;
            }
            localSink.submitChunk(p);
        }
        metrics.setWorkerQueueDepth(chunkQueue.size());
    }

    public void enterShutdownMode() {
        shutdownMode = true;
    }

    public boolean isShutdownMode() {
        return shutdownMode;
    }

    public int chunkQueueSize() {
        return chunkQueue.size();
    }

    public SaveQueue chunkQueueRef() {
        return chunkQueue;
    }
}
