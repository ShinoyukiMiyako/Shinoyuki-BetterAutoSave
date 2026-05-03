package com.shinoyuki.betterautosave.core.scheduler;

import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;

import java.util.concurrent.atomic.AtomicLong;

public final class SaveScheduler {

    private final SaveQueue chunkQueue = new SaveQueue();
    private final SaveQueue entityQueue = new SaveQueue();
    private final AdaptiveThrottle chunkThrottle;
    private final AdaptiveThrottle entityThrottle;
    private final SaveMetrics metrics;
    private final AtomicLong enqueueSequence = new AtomicLong();

    private volatile ChunkSubmissionSink sink;
    private volatile boolean shutdownMode;

    public SaveScheduler(SaveMetrics metrics) {
        this.metrics = metrics;
        this.chunkThrottle = new AdaptiveThrottle(
                BetterAutoSaveConfig.chunksPerTickBase(),
                BetterAutoSaveConfig.deadlineGuardSeconds());
        this.entityThrottle = new AdaptiveThrottle(
                BetterAutoSaveConfig.entityChunksPerTickBase(),
                BetterAutoSaveConfig.deadlineGuardSeconds());
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

    public boolean enqueueEntity(ChunkSavePriority priority) {
        boolean accepted = entityQueue.offer(priority);
        metrics.setEntityQueueDepth(entityQueue.size());
        return accepted;
    }

    public void onServerTick(double avgTickMs, int remainingSecondsInCycle) {
        ChunkSubmissionSink localSink = sink;
        if (localSink == null) {
            return;
        }
        boolean adaptive = BetterAutoSaveConfig.adaptiveEnabled();

        int chunkBudget = chunkThrottle.adjust(avgTickMs, remainingSecondsInCycle, adaptive, shutdownMode);
        for (int i = 0; i < chunkBudget; i++) {
            ChunkSavePriority p = chunkQueue.poll();
            if (p == null) {
                break;
            }
            localSink.submitChunk(p);
        }
        metrics.setWorkerQueueDepth(chunkQueue.size());

        int entityBudget = entityThrottle.adjust(avgTickMs, remainingSecondsInCycle, adaptive, shutdownMode);
        for (int i = 0; i < entityBudget; i++) {
            ChunkSavePriority p = entityQueue.poll();
            if (p == null) {
                break;
            }
            localSink.submitEntity(p);
        }
        metrics.setEntityQueueDepth(entityQueue.size());
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

    public int entityQueueSize() {
        return entityQueue.size();
    }

    public SaveQueue chunkQueueRef() {
        return chunkQueue;
    }

    public SaveQueue entityQueueRef() {
        return entityQueue;
    }
}
