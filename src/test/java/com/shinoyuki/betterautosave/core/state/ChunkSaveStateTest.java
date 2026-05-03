package com.shinoyuki.betterautosave.core.state;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSaveStateTest {

    @Test
    void mark_dirty_increments_generation_unconditionally() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        assertEquals(ChunkSaveState.Phase.CLEAN, s.phase());
        assertEquals(0L, s.generation());

        s.markDirty();
        assertEquals(ChunkSaveState.Phase.DIRTY, s.phase());
        assertEquals(1L, s.generation());

        s.markDirty();
        assertEquals(2L, s.generation(),
                "Generation must increment even when phase is already DIRTY");
        assertEquals(ChunkSaveState.Phase.DIRTY, s.phase());
    }

    @Test
    void try_snapshot_only_succeeds_once_per_dirty() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        s.markDirty();

        assertTrue(s.trySnapshot());
        assertFalse(s.trySnapshot(), "Second trySnapshot must fail because phase moved past DIRTY");
        assertEquals(ChunkSaveState.Phase.SNAPSHOTTING, s.phase());
    }

    @Test
    void io_completed_with_unchanged_generation_lands_clean() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        s.markDirty();
        s.trySnapshot();
        long captured = s.enterSerializing();
        assertEquals(1L, captured);
        s.enterIoPending();

        ChunkSaveState.IoOutcome outcome = s.ioCompletedSuccessfully();
        assertEquals(ChunkSaveState.IoOutcome.CLEAN_LANDED, outcome);
        assertEquals(ChunkSaveState.Phase.CLEAN, s.phase());
    }

    @Test
    void io_completed_with_changed_generation_requeues_dirty() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        s.markDirty();
        s.trySnapshot();
        s.enterSerializing();
        s.enterIoPending();

        s.markDirty();

        ChunkSaveState.IoOutcome outcome = s.ioCompletedSuccessfully();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, outcome);
        assertEquals(ChunkSaveState.Phase.DIRTY, s.phase());
        assertEquals(2L, s.generation());
    }

    @Test
    void io_failed_retries_until_max_then_terminal() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        s.markDirty();
        s.trySnapshot();
        s.enterSerializing();
        s.enterIoPending();

        ChunkSaveState.IoOutcome r1 = s.ioFailed(2);
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, r1);
        assertEquals(1, s.retryCount());

        s.trySnapshot();
        s.enterSerializing();
        s.enterIoPending();
        ChunkSaveState.IoOutcome r2 = s.ioFailed(2);
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, r2);
        assertEquals(2, s.retryCount());

        s.trySnapshot();
        s.enterSerializing();
        s.enterIoPending();
        ChunkSaveState.IoOutcome r3 = s.ioFailed(2);
        assertEquals(ChunkSaveState.IoOutcome.FAILED_TERMINAL, r3);
        assertEquals(ChunkSaveState.Phase.FAILED, s.phase());
    }

    @Test
    void concurrent_dirty_marks_never_lose_generation_increments() throws InterruptedException {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        int writers = 8;
        int marksPerWriter = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger live = new AtomicInteger(writers);

        for (int i = 0; i < writers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < marksPerWriter; j++) {
                        s.markDirty();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    live.decrementAndGet();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(0, live.get());
        assertEquals((long) writers * marksPerWriter, s.generation(),
                "Every markDirty must increment generation atomically without loss");
    }

    @Test
    void must_drain_flag_independent_of_phase() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        assertFalse(s.mustDrain());
        s.markMustDrain();
        assertTrue(s.mustDrain());
        s.markDirty();
        assertTrue(s.mustDrain(), "markDirty must not affect mustDrain flag");
        s.clearMustDrain();
        assertFalse(s.mustDrain());
    }
}
