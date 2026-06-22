package com.shinoyuki.betterautosave.core.load;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 并发护栏: 串行化多 load worker 对 vanilla {@code ChunkSerializer.read} 内 static 分发 Codec 的解码。
 *
 * <p>背景 (docs/ASYNC_LOAD_DESIGN.md 第四节并发护栏 / 第三节边界表末两行): {@code read} 体内的
 * {@code BLOCK_STATE_CODEC.parse} / biome {@code codec.parse} / {@code BlendingData.CODEC.parse} /
 * {@code StructureStart.loadStaticStart} 都吃 static 单例 Codec 与 DataFixerUpper 的内部缓存。多 worker 并发
 * decode 同类 static Codec 会读坏这些非线程安全的共享缓存 (C2ME 对此用 SynchronizedCodec 包裹解决)。
 *
 * <p>M1 取舍: 本特性不重写 {@code read} 的循环体 (设计非目标二), 无法在 vanilla 方法内逐 {@code .parse()}
 * 插锁。故 M1 用单把进程级 {@link ReentrantLock} 包住 <b>整段</b> off-thread {@code read} (见
 * {@code ChunkLoadTask}), 保证任一时刻至多一个 worker 在跑 vanilla 解码 —— 以 worker 间解码并行度换取 DFU
 * 缓存安全。spark 实测 deserialize 本就单线程封顶 (单 CCD 多核喂不进去), 故粗粒度串行对吞吐损失有限。
 * 细粒度的 per-Codec SynchronizedCodec (真正放开不同 Codec 的并行) 留作 M2 后吞吐优化, 不在 M1 正确性优先
 * 范围内。
 *
 * <p>lock 是 fair=false 默认 (吞吐优先, 加载是请求驱动无饥饿公平性要求), 可重入 (同一 worker 线程若递归进
 * read 不自死锁, 当前 vanilla read 不递归, 可重入仅作防御)。
 */
public final class LoadCodecGuard {

    private static final ReentrantLock LOCK = new ReentrantLock();

    public static void lock() {
        LOCK.lock();
    }

    public static void unlock() {
        LOCK.unlock();
    }

    private LoadCodecGuard() {
    }
}
