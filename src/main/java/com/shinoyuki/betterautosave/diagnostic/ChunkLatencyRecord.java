package com.shinoyuki.betterautosave.diagnostic;

import java.util.Arrays;

/**
 * v0.9: 单 chunk 的滑动窗口 latency 统计记录.
 *
 * <p>持有一个固定大小的 ring buffer 存最近 N 次 worker NBT build latency.
 * 当 buffer 满时新样本覆盖最旧的, 实现"最近 N 次"语义. 写位置 writeIdx
 * 模 N 滚动.
 *
 * <p><b>线程安全</b>: 所有 mutable state 由 {@code synchronized} 方法保护.
 * 单 record 写频率 ~ chunk save 完成频率 (autosave 周期内 100-500 次/秒
 * 全服分散到不同 record, 单 record 几乎无并发竞争). 读频率仅命令调用,
 * 不在热路径. 锁竞争可忽略.
 *
 * <p><b>p99 算法</b>: 拷贝 samples 排序取 ceil(count * 0.99) - 1 索引.
 * count 极少时 (< 100) p99 接近 max, 是预期行为.
 */
public final class ChunkLatencyRecord {

    private final long packedPos;
    private final String dimensionId;
    private final long[] samples;

    private int writeIdx;
    private int sampleCount;
    private long totalSamples;
    private long lastSavedAtMillis;

    ChunkLatencyRecord(long packedPos, String dimensionId, int windowSize) {
        this.packedPos = packedPos;
        this.dimensionId = dimensionId;
        this.samples = new long[windowSize];
    }

    public long packedPos() {
        return packedPos;
    }

    public String dimensionId() {
        return dimensionId;
    }

    synchronized void addSample(long workerNs) {
        samples[writeIdx] = workerNs;
        writeIdx = (writeIdx + 1) % samples.length;
        if (sampleCount < samples.length) {
            sampleCount++;
        }
        totalSamples++;
        lastSavedAtMillis = System.currentTimeMillis();
    }

    public synchronized int sampleCount() {
        return sampleCount;
    }

    public synchronized long totalSamples() {
        return totalSamples;
    }

    public synchronized long lastSavedAtMillis() {
        return lastSavedAtMillis;
    }

    public synchronized long lastNs() {
        if (sampleCount == 0) {
            return 0L;
        }
        int last = (writeIdx - 1 + samples.length) % samples.length;
        return samples[last];
    }

    /** 窗口内最大值 (不是历史 cumulative max). */
    public synchronized long maxNs() {
        long max = 0L;
        for (int i = 0; i < sampleCount; i++) {
            if (samples[i] > max) {
                max = samples[i];
            }
        }
        return max;
    }

    /** p99 (窗口内). count=0 返回 0. */
    public synchronized long p99Ns() {
        if (sampleCount == 0) {
            return 0L;
        }
        long[] copy = Arrays.copyOf(samples, sampleCount);
        Arrays.sort(copy);
        int idx = Math.max(0, (int) Math.ceil(sampleCount * 0.99) - 1);
        return copy[idx];
    }
}
