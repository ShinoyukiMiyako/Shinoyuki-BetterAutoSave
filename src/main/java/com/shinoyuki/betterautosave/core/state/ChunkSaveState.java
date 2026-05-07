package com.shinoyuki.betterautosave.core.state;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkSaveState {

    public enum Phase {
        CLEAN,
        DIRTY,
        SNAPSHOTTING,
        SERIALIZING,
        IO_PENDING,
        FAILED
    }

    public enum IoOutcome {
        CLEAN_LANDED,
        REQUEUE_DIRTY,
        FAILED_TERMINAL
    }

    private final long packedPos;
    private final String dimensionId;
    private final long enqueueSequence;

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.CLEAN);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicInteger retryCount = new AtomicInteger();
    private volatile long inFlightGeneration;
    private volatile boolean mustDrain;

    public ChunkSaveState(long packedPos, String dimensionId, long enqueueSequence) {
        this.packedPos = packedPos;
        this.dimensionId = dimensionId;
        this.enqueueSequence = enqueueSequence;
    }

    public long packedPos() {
        return packedPos;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public long enqueueSequence() {
        return enqueueSequence;
    }

    public Phase phase() {
        return phase.get();
    }

    public long generation() {
        return generation.get();
    }

    public int retryCount() {
        return retryCount.get();
    }

    public long inFlightGeneration() {
        return inFlightGeneration;
    }

    public boolean mustDrain() {
        return mustDrain;
    }

    public void markDirty() {
        generation.incrementAndGet();
        phase.compareAndSet(Phase.CLEAN, Phase.DIRTY);
    }

    public boolean trySnapshot() {
        return phase.compareAndSet(Phase.DIRTY, Phase.SNAPSHOTTING);
    }

    public long enterSerializing() {
        long captured = generation.get();
        inFlightGeneration = captured;
        phase.set(Phase.SERIALIZING);
        return captured;
    }

    public void enterIoPending() {
        phase.set(Phase.IO_PENDING);
    }

    public IoOutcome ioCompletedSuccessfully() {
        if (generation.get() == inFlightGeneration) {
            phase.set(Phase.CLEAN);
            retryCount.set(0);
            mustDrain = false;
            return IoOutcome.CLEAN_LANDED;
        }
        phase.set(Phase.DIRTY);
        return IoOutcome.REQUEUE_DIRTY;
    }

    public IoOutcome ioFailed(int maxRetries) {
        int n = retryCount.incrementAndGet();
        if (n > maxRetries) {
            phase.set(Phase.FAILED);
            mustDrain = false;
            return IoOutcome.FAILED_TERMINAL;
        }
        phase.set(Phase.DIRTY);
        return IoOutcome.REQUEUE_DIRTY;
    }

    public void resetAfterFallback() {
        retryCount.set(0);
        phase.set(Phase.CLEAN);
    }

    public void markMustDrain() {
        mustDrain = true;
    }

    public void clearMustDrain() {
        mustDrain = false;
    }
}
