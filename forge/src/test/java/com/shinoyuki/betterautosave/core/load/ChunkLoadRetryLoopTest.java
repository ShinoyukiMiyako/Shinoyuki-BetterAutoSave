package com.shinoyuki.betterautosave.core.load;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ChunkLoadTask 的 read-stage 重试编排 seam ({@code readWithRetry}) 的行为回归。真跑 seam + 真 {@link
 * LoadDeferredActions} (二者 MC-free), 用假的 {@code ReadAttempt} 模拟 read 排延迟副作用 + 抛/成功, 验证 ASM 计数
 * 断言无法覆盖的行为: clearForRetry 的<b>位置</b> (每次尝试前, 而非成功 read 之后) 与重试计数 / 原样上抛。
 */
class ChunkLoadRetryLoopTest {

    /**
     * clearForRetry 必须在<b>每次</b>尝试前跑, 故成功那次返回后 sink 只含成功尝试排入的副作用, 不叠加前面失败尝试的。
     * 若 clearForRetry 错位到成功 read 之后, 三次尝试排入的 3 个副作用会全部残留 -> drainCaptured 得 3 个 = 脏写。
     */
    @Test
    void retry_clears_deferred_before_each_attempt_so_success_carries_only_its_own_side_effects() throws Exception {
        LoadDeferredActions sink = new LoadDeferredActions();
        AtomicInteger reads = new AtomicInteger();
        List<Integer> retries = new ArrayList<>();
        // 每次尝试都往 sink 排一个本次尝试专属的副作用 (模拟 read 内 redirect 排 POI/光照); 前 2 次抛, 第 3 次成功。
        ChunkLoadTask.ReadAttempt<String> attempt = () -> {
            int k = reads.getAndIncrement();
            sink.add(() -> { });
            if (k < 2) {
                throw new RuntimeException("transient parse race");
            }
            return "ok";
        };

        String result = ChunkLoadTask.readWithRetry(sink, 5, attempt, (n, t) -> retries.add(n));

        assertEquals("ok", result, "第 3 次成功必须返回其值");
        assertEquals(3, reads.get(), "首次 + 2 次重试 = 3 次尝试");
        assertEquals(List.of(1, 2), retries, "onRetry 只在前 2 次失败各回调一次, 重试序号从 1 起");
        List<Runnable> deferred = sink.drainCaptured();
        assertEquals(1, deferred.size(),
                "成功那次 read 前 clearForRetry 已复位, drainCaptured 只含成功尝试排的 1 个副作用; clearForRetry 错位"
                        + "(放成功 read 之后) 则累积 3 个 = 上次失败尝试的 POI/光照残留被二次回放 (脏写)");
    }

    /** 首次成功 (零重试): onRetry 不回调, clearForRetry 仍跑一次 (成功前), sink 只含本次副作用。 */
    @Test
    void first_attempt_success_does_not_retry() throws Exception {
        LoadDeferredActions sink = new LoadDeferredActions();
        AtomicInteger retries = new AtomicInteger();
        String result = ChunkLoadTask.readWithRetry(sink, 3, () -> {
            sink.add(() -> { });
            return "ok";
        }, (n, t) -> retries.incrementAndGet());

        assertEquals("ok", result);
        assertEquals(0, retries.get(), "首次即成功不得触发重试回调");
        assertEquals(1, sink.drainCaptured().size(), "成功那次的副作用完整带出");
    }

    /** 重试耗尽必须原样上抛最后一次异常 (不包装不吞), onRetry 恰回调 maxRetries 次。 */
    @Test
    void exhausted_retries_rethrows_original_after_maxRetries() {
        LoadDeferredActions sink = new LoadDeferredActions();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        RuntimeException boom = new RuntimeException("always fails");
        ChunkLoadTask.ReadAttempt<String> attempt = () -> {
            reads.incrementAndGet();
            throw boom;
        };

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ChunkLoadTask.readWithRetry(sink, 2, attempt, (n, t) -> retries.incrementAndGet()));

        assertSame(boom, thrown, "重试耗尽必须原样上抛最后一次异常, 不包装不吞 (交 worker 走 onUnhandledError 兜底)");
        assertEquals(3, reads.get(), "首次 + maxRetries(2) 次重试 = 3 次尝试");
        assertEquals(2, retries.get(), "onRetry 只在重试 (非终态) 时回调, maxRetries=2 -> 2 次");
    }

    /** maxRetries=0 (config 允许关重试): 首次抛即终态原样上抛, 不重试。 */
    @Test
    void zero_max_retries_rethrows_on_first_throw() {
        LoadDeferredActions sink = new LoadDeferredActions();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        IllegalStateException boom = new IllegalStateException("corrupt");
        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> ChunkLoadTask.readWithRetry(sink, 0, () -> {
                    reads.incrementAndGet();
                    throw boom;
                }, (n, t) -> retries.incrementAndGet()));

        assertSame(boom, thrown);
        assertEquals(1, reads.get(), "maxRetries=0 -> 只尝试一次");
        assertEquals(0, retries.get(), "maxRetries=0 -> 零重试回调");
    }
}
