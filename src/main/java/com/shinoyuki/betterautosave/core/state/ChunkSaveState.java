package com.shinoyuki.betterautosave.core.state;

import com.shinoyuki.betterautosave.core.snapshot.ChunkSnapshot;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkSaveState {

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
    // v0.10.2 修复 (M6): 终态转换 (CLEAN_LANDED / FAILED_TERMINAL) 内部 CAS 清 mustDrain 的结果,
    // 供 IO 完成回调线程在调完转换后读取以驱动 gauge dec. 仅被同一回调线程写后即读 (whenComplete
    // 是 per-task 单线程序列), 无跨线程可见性需求, 故普通字段即可.
    private boolean lastTransitionClearedMustDrain;
    // v0.10.2 修复 (C-chunk-unload-collision): 在途碰撞 + 卸载时的"接力快照"槽位。
    // 当某代 IO 在途 (phase=SNAPSHOTTING/SERIALIZING/IO_PENDING) 时该 chunk 又被编辑 (generation
    // 前进) 并触发卸载, mixin 碰撞分支会对最新内存做一次"纯 capture"(不碰 phase/inFlightGeneration,
    // 不 offer), 把结果存进本槽; 在途那代 IO 落地判 REQUEUE_DIRTY 时, 回调取出本槽的 pending 快照
    // 重投, 接力把最新代落盘。AtomicReference 让主线程 (登记) 与 IOWorker 回调线程 (取出) 安全交接。
    private final AtomicReference<ChunkSnapshot> pendingSnapshot = new AtomicReference<>();

    public ChunkSaveState(long packedPos, String dimensionId, long enqueueSequence) {
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

    /**
     * 是否处于"某代 IO 已在管线在飞"的三态之一 (SNAPSHOTTING / SERIALIZING / IO_PENDING)。
     * v0.10.2 修复 (autosave 通道静默吞咽, gaps[1]): autosave 通道用此判定在途短路, 避免对在飞 chunk
     * 入队一个注定 trySnapshot 失败的 priority (静默 fallback + 虚高计数 + 关服 join 不知情)。
     */
    public boolean isInFlight() {
        Phase p = phase.get();
        return p == Phase.SNAPSHOTTING || p == Phase.SERIALIZING || p == Phase.IO_PENDING;
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
            // v0.10.2 修复 (M6): 记录本次 CAS 是否真正清掉 mustDrain, 让调用方据此 dec gauge.
            // 终态 phase.set 在 CAS 之前完成, mixin 重入门只在 IO_PENDING 等在途 phase 才 inc;
            // 谁的 CAS 赢得 true->false 谁负责唯一一次 dec, 杜绝拆分读快照漏 dec.
            lastTransitionClearedMustDrain = mustDrain.compareAndSet(true, false);
            return IoOutcome.CLEAN_LANDED;
        }
        phase.set(Phase.DIRTY);
        lastTransitionClearedMustDrain = false;
        return IoOutcome.REQUEUE_DIRTY;
    }

    public IoOutcome ioFailed(int maxRetries) {
        int n = retryCount.incrementAndGet();
        if (n > maxRetries) {
            phase.set(Phase.FAILED);
            lastTransitionClearedMustDrain = mustDrain.compareAndSet(true, false);
            return IoOutcome.FAILED_TERMINAL;
        }
        phase.set(Phase.DIRTY);
        lastTransitionClearedMustDrain = false;
        return IoOutcome.REQUEUE_DIRTY;
    }

    /**
     * v0.10.2 修复 (M6): 返回上一次终态转换 (ioCompletedSuccessfully / ioFailed) 内部 CAS
     * 是否真正把 mustDrain 由 true 清成 false. IO 完成回调据此决定是否 dec mustDrainPending gauge,
     * 保证 boolean 清零与 gauge dec 是同一次 CAS 的原子结果. 仅由同一回调线程在调完转换后立即读取.
     */
    public boolean lastTransitionClearedMustDrain() {
        return lastTransitionClearedMustDrain;
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

    /** CAS false -> true; 返回 true 表示首次置位, 调用方可 inc gauge 一次. */
    public boolean tryMarkMustDrain() {
        return mustDrain.compareAndSet(false, true);
    }

    /** CAS true -> false; 返回 true 表示首次清除, 调用方可 dec gauge 一次. */
    public boolean compareAndClearMustDrain() {
        return mustDrain.compareAndSet(true, false);
    }

    /**
     * v0.10.2 修复 (C-chunk-unload-collision): 登记一份"接力快照"。
     *
     * <p><b>覆盖语义 (隐角 B, 最新者胜)</b>: 槽已占用时直接 set 替换, 旧 pending 作废且无需落盘 ——
     * 旧 pending 是更新 pending 的真子集 (同一 chunk 更早一代的内存态), 落它只会被更新代再次覆盖。
     * AtomicReference.set 保证主线程多次登记 (隐角 A 的多代碰撞链) 永远只保留最新一份。
     *
     * <p>登记方 (mixin 碰撞分支) 必须已确保 mustDrain 置位: 只要本槽非空, 在途的接力链尚未落地,
     * 关服 join 必须继续等 (gauge 不变式: pendingSnapshot 非空 -> mustDrain 恒真)。
     */
    public void registerPendingSnapshot(ChunkSnapshot snapshot) {
        pendingSnapshot.set(snapshot);
    }

    /**
     * 取出并清空接力快照槽 (getAndSet null)。在途那代 IO 落地判 REQUEUE_DIRTY 时由回调调用:
     * 返回非 null 则重投它接力落盘, 返回 null 则无 pending, 走原有 REQUEUE_DIRTY 语义 (等下轮)。
     */
    public ChunkSnapshot takePendingSnapshot() {
        return pendingSnapshot.getAndSet(null);
    }

    /** 槽是否非空。供 gauge 不变式断言 (pendingSnapshot 非空 -> mustDrain 恒真) 与诊断使用。 */
    public boolean hasPendingSnapshot() {
        return pendingSnapshot.get() != null;
    }

    /**
     * v0.10.2 修复 (C-chunk-unload-collision, 隐角 A): 重投接力快照前把 inFlightGeneration 锁到该
     * pending 快照 capture 时的代, phase 推回 SERIALIZING。
     *
     * <p><b>为何不复用 enterSerializing</b>: enterSerializing 读 {@code generation.get()} 锁当前代,
     * 但接力快照的代在 (更早的) 纯 capture 时刻就已固定 (存于 snapshot.capturedGeneration)。重投时
     * 若按当前 generation 锁, 期间又有新编辑会错配 —— 必须锁 pending 自己的代, 才能让该接力 IO 落地时
     * generation==inFlightGeneration 正确判 CLEAN_LANDED (无更新编辑) 或 REQUEUE_DIRTY (又有更新 pending)。
     * 这正是 "inFlightGeneration 随 pending 链推进, pending dispatch 时锁其代" 的落地点。
     */
    public void reenterSerializingForPending(long capturedGeneration) {
        inFlightGeneration = capturedGeneration;
        phase.set(Phase.SERIALIZING);
    }
}
