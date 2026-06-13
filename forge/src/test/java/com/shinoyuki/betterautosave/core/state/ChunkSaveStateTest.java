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

    @Test
    void io_completed_clean_landed_clears_must_drain() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        s.markDirty();
        s.trySnapshot();
        s.enterSerializing();
        s.enterIoPending();
        s.markMustDrain();
        assertTrue(s.mustDrain());

        ChunkSaveState.IoOutcome outcome = s.ioCompletedSuccessfully();
        assertEquals(ChunkSaveState.IoOutcome.CLEAN_LANDED, outcome);
        assertFalse(s.mustDrain(),
                "CLEAN_LANDED must clear mustDrain so shutdown join does not wait forever");
    }

    @Test
    void io_completed_requeue_dirty_keeps_must_drain() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        s.markDirty();
        s.trySnapshot();
        s.enterSerializing();
        s.enterIoPending();
        s.markMustDrain();
        s.markDirty();

        ChunkSaveState.IoOutcome outcome = s.ioCompletedSuccessfully();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, outcome);
        assertTrue(s.mustDrain(),
                "REQUEUE_DIRTY must keep mustDrain so the next pass still drains the chunk");
    }

    @Test
    void io_failed_terminal_clears_must_drain() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        s.markDirty();
        s.trySnapshot();
        s.enterSerializing();
        s.enterIoPending();
        s.markMustDrain();

        ChunkSaveState.IoOutcome outcome = s.ioFailed(0);
        assertEquals(ChunkSaveState.IoOutcome.FAILED_TERMINAL, outcome);
        assertFalse(s.mustDrain(),
                "FAILED_TERMINAL must clear mustDrain so shutdown join does not wait forever");
    }

    @Test
    void compare_and_clear_must_drain_returns_prev_state_atomically() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        assertFalse(s.compareAndClearMustDrain(),
                "False on already-clear; caller must not dec gauge");
        s.markMustDrain();
        assertTrue(s.compareAndClearMustDrain(),
                "True on first clear; caller may dec gauge once");
        assertFalse(s.compareAndClearMustDrain(),
                "Subsequent clear must not double-decrement");
        assertFalse(s.mustDrain());
    }

    @Test
    void try_mark_must_drain_returns_prev_state_atomically() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        assertTrue(s.tryMarkMustDrain(),
                "True on first mark; caller may inc gauge once");
        assertFalse(s.tryMarkMustDrain(),
                "Subsequent mark must not double-increment");
        assertTrue(s.mustDrain());
    }

    @Test
    void io_failed_retry_keeps_must_drain() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        s.markDirty();
        s.trySnapshot();
        s.enterSerializing();
        s.enterIoPending();
        s.markMustDrain();

        ChunkSaveState.IoOutcome outcome = s.ioFailed(2);
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, outcome);
        assertTrue(s.mustDrain(),
                "Non-terminal retry must keep mustDrain so the next attempt still drains");
    }

    /**
     * isInFlight 必须恰好对在飞三态 (SNAPSHOTTING/SERIALIZING/IO_PENDING) 返 true, 对 CLEAN/DIRTY/FAILED
     * 返 false。autosave 通道 (ChunkMapMixin) 用它在途短路, 避免对在飞 chunk 入队注定 trySnapshot 失败的
     * priority。判定标准: 若 isInFlight 漏判 IO_PENDING, 在途 chunk 被错误 enqueue, 此测试挂。
     */
    @Test
    void is_in_flight_true_exactly_for_pipeline_phases() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        assertFalse(s.isInFlight(), "CLEAN 非在飞");

        s.markDirty();
        assertFalse(s.isInFlight(), "DIRTY 非在飞 (尚未进管线)");

        s.trySnapshot();
        assertTrue(s.isInFlight(), "SNAPSHOTTING 在飞");

        s.enterSerializing();
        assertTrue(s.isInFlight(), "SERIALIZING 在飞");

        s.enterIoPending();
        assertTrue(s.isInFlight(), "IO_PENDING 在飞");

        ChunkSaveState.IoOutcome outcome = s.ioFailed(0);
        assertEquals(ChunkSaveState.IoOutcome.FAILED_TERMINAL, outcome);
        assertFalse(s.isInFlight(), "FAILED 非在飞 (让 vanilla 兜底)");
    }

    /**
     * autosave 通道在途短路决策回归: 复刻 ChunkMapMixin 在途分支 —— in-flight 时
     * 只 tryMarkMustDrain (gauge inc 一次) 且**不**入队, 让关服 join 知情。断言 mustDrain 被标记且
     * 重复进入不重复 inc。删 mixin 的 tryMarkMustDrain -> 在途 chunk 关服 join 不等, 此契约失守。
     */
    @Test
    void autosave_in_flight_short_circuit_marks_must_drain_once_without_enqueue() {
        ChunkSaveState s = new ChunkSaveState(0L, "overworld", 1L);
        s.markDirty();
        s.trySnapshot();
        s.enterSerializing();
        s.enterIoPending();          // 在飞

        int[] enqueueCount = {0};
        int[] mustDrainGauge = {0};

        // 复刻 mixin 循环对单个在飞 chunk 的两次 autosave 扫描.
        for (int cycle = 0; cycle < 2; cycle++) {
            if (s.isInFlight()) {
                if (s.tryMarkMustDrain()) {
                    mustDrainGauge[0]++;
                }
                continue;            // 不入队
            }
            enqueueCount[0]++;        // 非在飞才入队 (本场景不该到这)
        }

        assertEquals(0, enqueueCount[0],
                "在飞 chunk 不得被 autosave 通道入队 (避免注定 trySnapshot 失败的 priority)");
        assertEquals(1, mustDrainGauge[0],
                "在飞短路必须标记 mustDrain 恰一次 (重复扫描不重复 inc)");
        assertTrue(s.mustDrain(), "关服 join 必须能从 mustDrain 知道还要等该在飞 chunk");
    }
}
