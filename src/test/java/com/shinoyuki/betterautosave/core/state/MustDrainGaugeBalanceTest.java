package com.shinoyuki.betterautosave.core.state;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * mustDrainPending gauge 无竞态配平回归.
 *
 * <p>现场: 若 IO 完成回调先读 wasDraining 快照 (普通读或独立 CAS), 再调 ioFailed/ioCompletedSuccessfully
 * (其内部 compareAndSet(true,false) 静默清 boolean), 最后按快照决定 dec, 则主线程 mixin 对仍处
 * IO_PENDING 的同 state tryMarkMustDrain+inc 若落在"回调读快照之后 / 内部清零之前", 该 inc 被内部 CAS
 * 静默吞掉而永远等不到配对 dec → mustDrainPending gauge 永久泄漏正偏移, drain-unload 命令误报超时 +
 * DiagnosticLogger 空闲门失效 + Prometheus 指标失真.
 *
 * <p>故终态转换内部 CAS 既清 boolean 又通过 lastTransitionClearedMustDrain() 报告结果, 调用方据此
 * dec — boolean 清零与 dec 是同一次 CAS 的原子结果. 本测试用单线程精确复刻并发交错的"丢失序列",
 * 断言 gauge 真值与 boolean 真值恒等. 删掉 lastTransitionClearedMustDrain 驱动的 dec (退回读快照拆分)
 * 则该序列 gauge 泄漏 +1, 断言挂.
 */
class MustDrainGaugeBalanceTest {

    /** 模拟回调线程: 终态转换后用 CAS 返回值驱动 dec, 与 ChunkSaveTask/EntitySaveTask 终态分支同逻辑. */
    private static void terminalBalance(ChunkSaveState state, AtomicLong gauge) {
        ChunkSaveState.IoOutcome outcome = state.ioFailed(0); // maxRetries=0 → FAILED_TERMINAL
        assertEquals(ChunkSaveState.IoOutcome.FAILED_TERMINAL, outcome);
        if (state.lastTransitionClearedMustDrain()) {
            gauge.decrementAndGet();
        }
    }

    @Test
    void interleaved_main_thread_mark_after_callback_terminal_does_not_leak_gauge() {
        // 丢失序列重放: 回调进入终态 ioFailed (清 boolean) 之前, 主线程刚 tryMarkMustDrain+inc.
        // 读快照拆分路径下: 回调先读 wasDraining (true/false 取决时刻), 内部 CAS 吞掉 inc → 漏 dec.
        // 当前: 终态 CAS 返回值唯一决定 dec, 无论主线程 inc 落在 CAS 前还是后都配平.
        ChunkSaveState state = new ChunkSaveState(0L, "overworld", 1L);
        AtomicLong gauge = new AtomicLong();

        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();

        // 主线程首次接管标记 mustDrain + inc.
        if (state.tryMarkMustDrain()) {
            gauge.incrementAndGet();
        }
        assertEquals(1L, gauge.get());

        // 回调进入终态, CAS 清 boolean 并驱动唯一一次 dec.
        terminalBalance(state, gauge);

        assertEquals(0L, gauge.get(),
                "终态 CAS 驱动 dec 后 gauge 必须配平归零");
        assertFalse(state.mustDrain(), "终态后 boolean 必须为 false");
    }

    @Test
    void double_terminal_balance_never_double_decrements() {
        // 若同一 state 的终态分支被错误调用两次 (例如成功回调与失败回调竞争), 第二次 CAS 返 false,
        // lastTransitionClearedMustDrain 为 false, 不会重复 dec 把 gauge 打到负数.
        ChunkSaveState state = new ChunkSaveState(0L, "overworld", 1L);
        AtomicLong gauge = new AtomicLong();
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();
        if (state.tryMarkMustDrain()) {
            gauge.incrementAndGet();
        }

        terminalBalance(state, gauge);
        // 第二次进入终态 (boolean 已 false): CAS 返 false, 不再 dec.
        state.enterIoPending();
        ChunkSaveState.IoOutcome second = state.ioFailed(0);
        assertEquals(ChunkSaveState.IoOutcome.FAILED_TERMINAL, second);
        if (state.lastTransitionClearedMustDrain()) {
            gauge.decrementAndGet();
        }

        assertEquals(0L, gauge.get(),
                "终态 CAS 已清后第二次不应重复 dec (gauge 不得为负)");
    }

    @Test
    void concurrent_mark_and_terminal_converge_gauge_to_boolean_truth() throws InterruptedException {
        // 高并发回归: 一边循环 markDirty->snapshot->serialize->ioPending->tryMarkMustDrain+inc,
        // 一边回调终态 ioCompletedSuccessfully+CAS dec. 收敛后 gauge 必须 == boolean 真值 (0 或 1).
        // 删掉 CAS 返回值驱动的 dec (退回读快照拆分) 在足够多迭代下必然累积正偏移.
        for (int iter = 0; iter < 200; iter++) {
            ChunkSaveState state = new ChunkSaveState(0L, "overworld", 1L);
            AtomicLong gauge = new AtomicLong();
            state.markDirty();
            state.trySnapshot();
            long gen = state.enterSerializing();
            state.enterIoPending();

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);

            Thread mainSim = new Thread(() -> {
                try {
                    start.await();
                    if (state.tryMarkMustDrain()) {
                        gauge.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread callbackSim = new Thread(() -> {
                try {
                    start.await();
                    // generation 未变 → CLEAN_LANDED, 内部 CAS 清 boolean, 返回值驱动 dec.
                    ChunkSaveState.IoOutcome outcome = state.ioCompletedSuccessfully();
                    if (outcome == ChunkSaveState.IoOutcome.CLEAN_LANDED
                            && state.lastTransitionClearedMustDrain()) {
                        gauge.decrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            mainSim.start();
            callbackSim.start();
            start.countDown();
            done.await();

            long g = gauge.get();
            boolean b = state.mustDrain();
            assertEquals(b ? 1L : 0L, g,
                    "iter " + iter + ": gauge(" + g + ") 必须等于 mustDrain boolean 真值(" + b + ") gen=" + gen);
        }
    }
}
