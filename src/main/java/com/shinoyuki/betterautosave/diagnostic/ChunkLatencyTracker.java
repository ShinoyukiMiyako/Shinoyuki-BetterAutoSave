package com.shinoyuki.betterautosave.diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.9: per-chunk worker NBT build latency 跟踪器, 给
 * {@code /betterautosave hottest-chunks} 命令提供数据源.
 *
 * <p>数据结构: synchronized {@link LinkedHashMap} (access-order=true)
 * 实现 LRU. {@code record(packedPos, ...)} 调 {@code get()} 触发 access
 * order 更新, 长时间不再 record 的 chunk 在新 chunk 进入时被
 * removeEldestEntry 自动 evict.
 *
 * <p><b>为什么 synchronized 而不是 ConcurrentHashMap</b>: LinkedHashMap
 * access-order 模式 get() 会修改 internal linked list, 不是线程安全的;
 * 改用 ConcurrentHashMap + 独立 LRU 队列实现复杂度大. record() 调用
 * 频率 = chunk save 完成频率 (~100-500/s 全服), synchronized 单锁开销
 * 在 us 级别可忽略, 不阻塞热路径.
 *
 * <p><b>外层 sync 必须覆盖 get</b>: synchronizedMap 包装的 access-order
 * LinkedHashMap, get 会改 internal state, 必须显式同步外层调用块, 否则
 * iteration / 后续 get 可能 ConcurrentModificationException. 见
 * {@link Collections#synchronizedMap} 文档.
 */
public final class ChunkLatencyTracker {

    private final int windowSize;
    private final int trackLimit;
    private final Map<Long, ChunkLatencyRecord> records;

    public ChunkLatencyTracker(int windowSize, int trackLimit) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be > 0");
        }
        if (trackLimit <= 0) {
            throw new IllegalArgumentException("trackLimit must be > 0");
        }
        this.windowSize = windowSize;
        this.trackLimit = trackLimit;
        this.records = Collections.synchronizedMap(new LinkedHashMap<Long, ChunkLatencyRecord>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ChunkLatencyRecord> eldest) {
                return size() > trackLimit;
            }
        });
    }

    public void record(long packedPos, String dimensionId, long workerNs) {
        ChunkLatencyRecord r;
        synchronized (records) {
            r = records.get(packedPos);
            if (r == null) {
                r = new ChunkLatencyRecord(packedPos, dimensionId, windowSize);
                records.put(packedPos, r);
            }
        }
        // record 内部 synchronized, 跟 tracker 锁不重叠. 此时新 record 已经
        // 在 map 里, 即使被 LRU evict, addSample 仍正常 (record 引用我们持有).
        r.addSample(workerNs);
    }

    /** 按窗口 p99 降序拿 top n. n <= 0 时返回全部. */
    public List<ChunkLatencyRecord> topByP99(int n) {
        List<ChunkLatencyRecord> snapshot;
        synchronized (records) {
            snapshot = new ArrayList<>(records.values());
        }
        snapshot.sort((a, b) -> Long.compare(b.p99Ns(), a.p99Ns()));
        if (n > 0 && snapshot.size() > n) {
            return new ArrayList<>(snapshot.subList(0, n));
        }
        return snapshot;
    }

    public int size() {
        synchronized (records) {
            return records.size();
        }
    }

    public int windowSize() {
        return windowSize;
    }

    public int trackLimit() {
        return trackLimit;
    }

    public void clear() {
        synchronized (records) {
            records.clear();
        }
    }
}
