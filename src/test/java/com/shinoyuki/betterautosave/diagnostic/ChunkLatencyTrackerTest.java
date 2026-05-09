package com.shinoyuki.betterautosave.diagnostic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLatencyTrackerTest {

    @Test
    void single_sample_yields_max_p99_last_equal_to_sample() {
        ChunkLatencyTracker t = new ChunkLatencyTracker(100, 1000);
        t.record(42L, "minecraft:overworld", 5_000_000L);

        List<ChunkLatencyRecord> top = t.topByP99(10);
        assertEquals(1, top.size());
        ChunkLatencyRecord r = top.get(0);
        assertEquals(42L, r.packedPos());
        assertEquals("minecraft:overworld", r.dimensionId());
        assertEquals(1, r.sampleCount());
        assertEquals(1, r.totalSamples());
        assertEquals(5_000_000L, r.p99Ns());
        assertEquals(5_000_000L, r.maxNs());
        assertEquals(5_000_000L, r.lastNs());
    }

    @Test
    void ring_buffer_overwrites_oldest_when_full() {
        ChunkLatencyTracker t = new ChunkLatencyTracker(5, 1000);
        // 写 8 个样本, 后 5 个生效: [600, 700, 800, 400, 500] 内存顺序
        // 但语义上窗口内最新 5 个 = {400, 500, 600, 700, 800}
        for (int i = 1; i <= 8; i++) {
            t.record(1L, "dim", i * 100_000_000L);
        }
        List<ChunkLatencyRecord> top = t.topByP99(1);
        ChunkLatencyRecord r = top.get(0);
        assertEquals(5, r.sampleCount(), "窗口满后 sampleCount 不再增长");
        assertEquals(8, r.totalSamples(), "totalSamples 仍累计所有写入");
        assertEquals(800_000_000L, r.maxNs(), "窗口 max 应为最新最大值 800");
        assertEquals(800_000_000L, r.lastNs(), "lastNs 是最新写入");
        // 老样本 100/200/300 应被覆盖, p99 不应取它们 (窗口内 min=400)
        assertTrue(r.p99Ns() >= 400_000_000L,
                "窗口内最小 400, p99 不该返回被覆盖的旧值, got " + r.p99Ns());
    }

    @Test
    void p99_excludes_single_outlier_in_100_sample_window() {
        ChunkLatencyTracker t = new ChunkLatencyTracker(100, 1000);
        for (int i = 0; i < 99; i++) {
            t.record(7L, "dim", 1_000_000L);
        }
        t.record(7L, "dim", 800_000_000L);

        ChunkLatencyRecord r = t.topByP99(1).get(0);
        assertEquals(100, r.sampleCount());
        assertTrue(r.p99Ns() <= 1_000_000L,
                "100 样本 1 outlier (1%) 时 p99 应反映多数, 不被单 outlier 拉高, got " + r.p99Ns());
        assertEquals(800_000_000L, r.maxNs(),
                "max 应反映 outlier");
    }

    @Test
    void p99_reflects_outlier_when_outliers_exceed_one_percent() {
        ChunkLatencyTracker t = new ChunkLatencyTracker(100, 1000);
        for (int i = 0; i < 90; i++) {
            t.record(7L, "dim", 1_000_000L);
        }
        for (int i = 0; i < 10; i++) {
            t.record(7L, "dim", 800_000_000L);
        }
        ChunkLatencyRecord r = t.topByP99(1).get(0);
        assertEquals(100, r.sampleCount());
        assertTrue(r.p99Ns() >= 500_000_000L,
                "10 outlier in 100 (10%) 应让 p99 落在 outlier 区, got " + r.p99Ns());
    }

    @Test
    void empty_record_returns_zero_for_all_stats() {
        ChunkLatencyRecord r = new ChunkLatencyRecord(0L, "dim", 10);
        assertEquals(0, r.sampleCount());
        assertEquals(0L, r.p99Ns());
        assertEquals(0L, r.maxNs());
        assertEquals(0L, r.lastNs());
        assertEquals(0L, r.totalSamples());
    }

    @Test
    void lru_evicts_least_recently_recorded_chunk_when_limit_hit() {
        ChunkLatencyTracker t = new ChunkLatencyTracker(10, 3);
        t.record(1L, "dim", 100L);  // chunk 1
        t.record(2L, "dim", 200L);  // chunk 2
        t.record(3L, "dim", 300L);  // chunk 3, size=3
        // 进入第 4 个 chunk → size > limit, evict eldest (chunk 1)
        t.record(4L, "dim", 400L);
        assertEquals(3, t.size(), "size 收敛到 trackLimit");

        List<ChunkLatencyRecord> all = t.topByP99(10);
        boolean has1 = all.stream().anyMatch(r -> r.packedPos() == 1L);
        boolean has4 = all.stream().anyMatch(r -> r.packedPos() == 4L);
        assertTrue(!has1, "chunk 1 应被 evict, 仍存在: " + has1);
        assertTrue(has4, "chunk 4 应保留");
    }

    @Test
    void lru_access_order_protects_recently_recorded_from_eviction() {
        ChunkLatencyTracker t = new ChunkLatencyTracker(10, 3);
        t.record(1L, "dim", 100L);
        t.record(2L, "dim", 200L);
        t.record(3L, "dim", 300L);
        // 触发 chunk 1 access (重新 record), 它从最老变成最新
        t.record(1L, "dim", 150L);
        // 进入第 4 个 chunk → evict eldest, 此时 eldest 是 chunk 2 (不是 1)
        t.record(4L, "dim", 400L);

        List<ChunkLatencyRecord> all = t.topByP99(10);
        boolean has1 = all.stream().anyMatch(r -> r.packedPos() == 1L);
        boolean has2 = all.stream().anyMatch(r -> r.packedPos() == 2L);
        assertTrue(has1, "chunk 1 被重新 record 后, 应被 LRU 保护不被 evict");
        assertTrue(!has2, "chunk 2 是最老 (距最近 record 时间最远), 应被 evict");
    }

    @Test
    void top_by_p99_is_sorted_descending_by_window_p99() {
        ChunkLatencyTracker t = new ChunkLatencyTracker(10, 100);
        // chunk A: p99 = 100ms, chunk B: p99 = 50ms, chunk C: p99 = 10ms
        for (int i = 0; i < 10; i++) {
            t.record(1L, "dim", 100_000_000L);
            t.record(2L, "dim", 50_000_000L);
            t.record(3L, "dim", 10_000_000L);
        }
        List<ChunkLatencyRecord> top = t.topByP99(3);
        assertEquals(3, top.size());
        assertEquals(1L, top.get(0).packedPos(), "p99 最高的 chunk 1 排第一");
        assertEquals(2L, top.get(1).packedPos());
        assertEquals(3L, top.get(2).packedPos());
        assertTrue(top.get(0).p99Ns() > top.get(1).p99Ns(),
                "排序必须严格降序");
    }

    @Test
    void top_by_p99_n_zero_returns_all() {
        ChunkLatencyTracker t = new ChunkLatencyTracker(10, 100);
        t.record(1L, "dim", 100L);
        t.record(2L, "dim", 200L);
        t.record(3L, "dim", 300L);
        assertEquals(3, t.topByP99(0).size());
        assertEquals(3, t.topByP99(-1).size());
        assertEquals(3, t.topByP99(99).size(), "n > size 时返回全部");
    }

    @Test
    void concurrent_record_same_chunk_does_not_lose_samples() throws InterruptedException {
        ChunkLatencyTracker t = new ChunkLatencyTracker(10_000, 100);
        final int threads = 8;
        final int writesPerThread = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < writesPerThread; j++) {
                        t.record(99L, "dim", j);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "concurrent-record-test-" + i).start();
        }
        start.countDown();
        done.await();

        assertEquals(0, errors.get(), "并发 record 不应抛异常");
        ChunkLatencyRecord r = t.topByP99(1).get(0);
        assertEquals((long) threads * writesPerThread, r.totalSamples(),
                "所有写入必须被计入 totalSamples");
    }

    @Test
    void invalid_construction_args_throw() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkLatencyTracker(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new ChunkLatencyTracker(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> new ChunkLatencyTracker(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkLatencyTracker(100, -1));
    }

    @Test
    void clear_removes_all_records() {
        ChunkLatencyTracker t = new ChunkLatencyTracker(10, 100);
        t.record(1L, "dim", 100L);
        t.record(2L, "dim", 200L);
        assertEquals(2, t.size());
        t.clear();
        assertEquals(0, t.size());
        assertEquals(0, t.topByP99(10).size());
    }
}
