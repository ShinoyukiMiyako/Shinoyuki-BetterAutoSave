package com.shinoyuki.betterautosave.core.state;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v0.6 entity 路径状态机, 与 {@link ChunkSaveState} 平行结构.
 *
 * <p>差异: entity 路径不区分 unload / eager save / autosave (vanilla 仅
 * {@code PersistentEntitySectionManager.autoSave} / {@code saveAll} 两个入口),
 * 因此 mustDrain 字段保留作为 "已被接管, 关服 join 必须等" 的语义标记,
 * 触发场景比 chunk 路径少.
 *
 * <p>状态转换与 ChunkSaveState 一致:
 * CLEAN -markDirty-> DIRTY -trySnapshot-> SNAPSHOTTING -enterSerializing->
 * SERIALIZING -enterIoPending-> IO_PENDING -ioCompletedSuccessfully->
 * CLEAN (generation match) 或 DIRTY (REQUEUE_DIRTY)
 */
public final class EntitySaveState {

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
    private final AtomicBoolean mustDrain = new AtomicBoolean();

    public EntitySaveState(long packedPos, String dimensionId, long enqueueSequence) {
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
        return mustDrain.get();
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
            mustDrain.compareAndSet(true, false);
            return IoOutcome.CLEAN_LANDED;
        }
        phase.set(Phase.DIRTY);
        return IoOutcome.REQUEUE_DIRTY;
    }

    public IoOutcome ioFailed(int maxRetries) {
        int n = retryCount.incrementAndGet();
        if (n > maxRetries) {
            phase.set(Phase.FAILED);
            mustDrain.compareAndSet(true, false);
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
        mustDrain.set(true);
    }

    public void clearMustDrain() {
        mustDrain.set(false);
    }

    public boolean tryMarkMustDrain() {
        return mustDrain.compareAndSet(false, true);
    }

    public boolean compareAndClearMustDrain() {
        return mustDrain.compareAndSet(true, false);
    }
}
