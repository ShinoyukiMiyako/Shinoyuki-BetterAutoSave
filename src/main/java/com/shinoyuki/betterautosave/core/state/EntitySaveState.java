package com.shinoyuki.betterautosave.core.state;

import com.shinoyuki.betterautosave.core.snapshot.EntitySnapshot;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v0.6 entity 路径状态机, 与 {@link ChunkSaveState} 平行结构.
 *
 * <p>差异: entity 路径不区分 unload / eager save / autosave (vanilla 仅
 * {@code PersistentEntitySectionManager.autoSave} / {@code saveAll} 两个入口),
 * 因此 mustDrain 字段保留作为 "已被接管, 关服 join 必须等" 的语义标记,
 * 触发场景比 chunk 路径少.
 *
 * <p>状态转换与 ChunkSaveState 一致:
 * CLEAN -markDirty-> DIRTY -trySnapshot-> SNAPSHOTTING -enterSerializing->
 * SERIALIZING -enterIoPending-> IO_PENDING -ioCompletedSuccessfully->
 * CLEAN (generation match) 或 DIRTY (REQUEUE_DIRTY)
 */
public final class EntitySaveState {

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
    // 终态转换 (CLEAN_LANDED / FAILED_TERMINAL) 内部 CAS 清 mustDrain 的结果,
    // 供 IO 完成回调线程在调完转换后读取以驱动 gauge dec. 仅被同一回调线程写后即读 (whenComplete
    // 是 per-task 单线程序列), 无跨线程可见性需求, 故普通字段即可.
    private boolean lastTransitionClearedMustDrain;
    // 与 ChunkSaveState 对称的"接力快照"槽位。
    // entity 路径碰撞更致命 —— vanilla processChunkUnload 在 storeEntities 后立即驱逐实体内存,
    // 卸载后该坐标永不再被 storeEntities 调用, 被吞那次的最新实体列表是唯一副本。在途碰撞时 mixin
    // 对最新 chunkEntities 做纯 capture 存进本槽。消费者: 在途那代 IO 落地的回调 (CLEAN_LANDED evict 前 / REQUEUE_DIRTY /
    // 终态 take 走), 或主线程 register 后重读 phase 发现回调已终态退出时自取回 (写后读对偶, getAndSet 防双投)。
    private final AtomicReference<EntitySnapshot> pendingSnapshot = new AtomicReference<>();

    public EntitySaveState(long packedPos, String dimensionId, long enqueueSequence) {
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
        // 不碰 pendingSnapshot 槽: entity 无 dispatchSaveEvent 窗口, 故无 chunk 那种 "回调路过 PREPARING 标
        // missed 离开" 的 sticky 载体, 槽是裸 AtomicReference<EntitySnapshot> (无 missedCycle/cycleSeq 概念),
        // 开新周期无需清理跨周期 missed 标记。但 "回调终态取槽" 与 "主线程 registerPendingSnapshot" 仍跑在两条
        // 线程, 需要一道写后读对偶纪律杜绝双不见 (回调先写 phase 终态再 getAndSet 取槽; 主线程先写槽再重读
        // phase), 该纪律在 EntitySaveTask.onIoSuccess 与 EntityStorageMixin 碰撞分支实现, 不在本方法。
        return captured;
    }

    public void enterIoPending() {
        phase.set(Phase.IO_PENDING);
    }

    public IoOutcome ioCompletedSuccessfully() {
        if (generation.get() == inFlightGeneration) {
            phase.set(Phase.CLEAN);
            retryCount.set(0);
            // 记录本次 CAS 是否真正清掉 mustDrain, 让调用方据此 dec gauge.
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
     * 返回上一次终态转换 (ioCompletedSuccessfully / ioFailed) 内部 CAS
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

    public boolean tryMarkMustDrain() {
        return mustDrain.compareAndSet(false, true);
    }

    public boolean compareAndClearMustDrain() {
        return mustDrain.compareAndSet(true, false);
    }

    /**
     * 登记一份接力快照 (覆盖语义最新者胜, 槽非空蕴含 mustDrain 须保持置位)。主线程碰撞分支调: 这是写后读对偶的
     * "写" 半 —— 调用方在本 set 之后必须重读 phase, 若回调已写定终态则自取回自己刚放的 pending 自踢
     * (见 EntityStorageMixin 碰撞分支), 否则会落进死状态 pending 滞留。
     */
    public void registerPendingSnapshot(EntitySnapshot snapshot) {
        pendingSnapshot.set(snapshot);
    }

    /**
     * 取出并清空接力快照槽 (getAndSet null)。回调终态分支与主线程自取共用此析构式读: getAndSet 保证同一份
     * pending 只被一方取走 (防双投), 是写后读对偶在 "双方都看见对方" 时裁定唯一消费者的原语。
     */
    public EntitySnapshot takePendingSnapshot() {
        return pendingSnapshot.getAndSet(null);
    }

    /** 槽是否非空; 供 gauge 不变式断言与诊断。 */
    public boolean hasPendingSnapshot() {
        return pendingSnapshot.get() != null;
    }

    /**
     * 重投接力快照前把 inFlightGeneration 锁到 pending 快照 capture 时的代, phase 推回 SERIALIZING。与
     * {@link ChunkSaveState#reenterSerializingForPending} 同理 —— 锁 pending 自己的代而非当前代,
     * 让接力 IO 落地正确判定 CLEAN_LANDED / REQUEUE_DIRTY。
     */
    public void reenterSerializingForPending(long capturedGeneration) {
        inFlightGeneration = capturedGeneration;
        phase.set(Phase.SERIALIZING);
    }
}
