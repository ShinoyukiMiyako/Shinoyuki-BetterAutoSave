package com.shinoyuki.betterautosave.core.load;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.1 L2 {@link LoadInFlightLimiter} 信号量语义回归 (纯 Java, 无 MC)。钉死三条不变式, 任一被破坏即异步加载会
 * <b>整服 hang</b> 或洪水未泄: (1) 满额时 acquire 必须排队 (否则在飞数失控, replay 仍洪水); (2) release 有排队者
 * 时必须 FIFO 放行 + 名额转交 (否则被等区块永久 hang = 死锁); (3) 等量 acquire/release 后在飞数归零、排队清空
 * (否则许可泄漏 -> max 个名额漏光后所有加载饿死)。
 */
class LoadInFlightLimiterTest {

    @Test
    void acquire_up_to_max_grants_immediately() {
        LoadInFlightLimiter l = new LoadInFlightLimiter(() -> 3);
        CompletableFuture<Void> a = l.acquire();
        CompletableFuture<Void> b = l.acquire();
        CompletableFuture<Void> c = l.acquire();
        assertTrue(a.isDone() && b.isDone() && c.isDone(),
                "未满 (max=3) 时前 3 个 acquire 必须立即完成放行");
        assertEquals(3, l.inFlight());
        assertEquals(0, l.queued());
    }

    @Test
    void acquire_beyond_max_queues_then_release_hands_off() {
        LoadInFlightLimiter l = new LoadInFlightLimiter(() -> 2);
        l.acquire();
        l.acquire();
        CompletableFuture<Void> queued = l.acquire();
        assertFalse(queued.isDone(),
                "满额 (max=2) 时第 3 个 acquire 必须排队 (未完成); 否则在飞数失控 -> replay 仍同 tick 洪水");
        assertEquals(2, l.inFlight());
        assertEquals(1, l.queued());

        l.release();
        assertTrue(queued.isDone(),
                "release 必须放行最早排队者; 否则主线程 managedBlock 等的区块永久 hang = 死锁");
        assertEquals(2, l.inFlight(), "名额转交给排队者, 在飞数不变 (非又 +1)");
        assertEquals(0, l.queued());
    }

    @Test
    void release_without_waiter_frees_slot() {
        LoadInFlightLimiter l = new LoadInFlightLimiter(() -> 4);
        l.acquire();
        l.acquire();
        assertEquals(2, l.inFlight());
        l.release();
        assertEquals(1, l.inFlight(), "无排队者时 release 必须减少在飞数; 否则名额泄漏");
        l.release();
        assertEquals(0, l.inFlight());
    }

    @Test
    void no_permit_leak_under_balanced_acquire_release() {
        LoadInFlightLimiter l = new LoadInFlightLimiter(() -> 8);
        int n = 50;
        List<CompletableFuture<Void>> futs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futs.add(l.acquire());
        }
        assertEquals(8, l.inFlight(), "max=8: 前 8 个立即占额");
        assertEquals(42, l.queued(), "其余 42 个排队");
        for (int i = 0; i < n; i++) {
            l.release();
        }
        assertEquals(0, l.inFlight(), "等量 acquire/release 后在飞数必须归零 (无泄漏, 否则最终饿死)");
        assertEquals(0, l.queued(), "全部排队者必须被放行 (无残留饿死)");
        assertTrue(futs.stream().allMatch(CompletableFuture::isDone), "每个 acquire 最终都被放行");
    }

    @Test
    void waiters_released_in_fifo_order() {
        LoadInFlightLimiter l = new LoadInFlightLimiter(() -> 1);
        l.acquire();
        CompletableFuture<Void> w1 = l.acquire();
        CompletableFuture<Void> w2 = l.acquire();
        l.release();
        assertTrue(w1.isDone(), "FIFO: 先排队的 w1 先放行");
        assertFalse(w2.isDone(), "FIFO: w2 仍在等");
        l.release();
        assertTrue(w2.isDone(), "再 release 放行 w2");
    }
}
