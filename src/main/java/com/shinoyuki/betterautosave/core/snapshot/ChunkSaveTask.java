package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.diagnostic.ChunkLatencyTracker;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

public final class ChunkSaveTask implements SaveTask {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    /**
     * IO 提交 seam: 把"用给定 tag 提交一次 region file 写"抽象出来, 生产环境绑到
     * {@code ioBridge.storeChunk(level, pos, tag)}. 抽出此接口让 M2 的失败重投循环可单测
     * (单测无法构造真实 ServerLevel / IOWorker), 注入返回受控 future 的 fake.
     */
    @FunctionalInterface
    public interface IoSubmitter {
        CompletableFuture<Void> submit(CompoundTag tag);
    }

    /**
     * v0.10.2 修复 (C-chunk-unload-collision): 接力快照重投 seam。给定 pending 快照, 把它包成新的
     * ChunkSaveTask 并 offer 回 chunkWorkerQueue (由序列化 worker 做 assemble, **不**在 IOWorker
     * 邮箱线程内联拼装 —— 内联会堵全服写盘)。生产环境绑到 {@link SnapshotPipeline}; 单测注入
     * 记录性 fake 以验证重投触发与代际接力。
     */
    @FunctionalInterface
    public interface PendingReoffer {
        void reoffer(ChunkSnapshot pending);
    }

    private final ChunkSnapshot snapshot;
    private final SaveMetrics metrics;
    private final ChunkLatencyTracker latencyTracker;
    private final ChunkRecoveryQueue recoveryQueue;
    private final IoSubmitter ioSubmitter;
    private final PendingReoffer pendingReoffer;

    public ChunkSaveTask(ChunkSnapshot snapshot, ServerLevel level, AsyncIoBridge ioBridge, SaveMetrics metrics,
                         ChunkLatencyTracker latencyTracker, ChunkRecoveryQueue recoveryQueue,
                         PendingReoffer pendingReoffer) {
        this(snapshot, metrics, latencyTracker, recoveryQueue,
                tag -> ioBridge.storeChunk(level, snapshot.pos(), tag), pendingReoffer);
    }

    /**
     * 测试用构造: 直接注入 IoSubmitter, 绕开 ServerLevel / AsyncIoBridge. 仅供单测验证
     * submitIo 重投循环, 生产代码一律走上面的公开构造. pendingReoffer 可为 null (单测不验证接力时).
     */
    ChunkSaveTask(ChunkSnapshot snapshot, SaveMetrics metrics, ChunkLatencyTracker latencyTracker,
                  ChunkRecoveryQueue recoveryQueue, IoSubmitter ioSubmitter) {
        this(snapshot, metrics, latencyTracker, recoveryQueue, ioSubmitter, null);
    }

    ChunkSaveTask(ChunkSnapshot snapshot, SaveMetrics metrics, ChunkLatencyTracker latencyTracker,
                  ChunkRecoveryQueue recoveryQueue, IoSubmitter ioSubmitter, PendingReoffer pendingReoffer) {
        this.snapshot = snapshot;
        this.metrics = metrics;
        this.latencyTracker = latencyTracker;
        this.recoveryQueue = recoveryQueue;
        this.ioSubmitter = ioSubmitter;
        this.pendingReoffer = pendingReoffer;
    }

    @Override
    public String taskName() {
        return "chunk@" + snapshot.pos() + "/" + snapshot.dimension().location();
    }

    @Override
    public void execute() {
        // v0.7.1 修复 (C2): execute 内同步异常路径必须复位 gauge.
        // assemble 抛 → mixin 已 inc serializing 但本函数未 dec → 永久 +1.
        // 用 task-local 标志记录"已 dec serializing"和"已 inc ioPending 且 future 未注册",
        // 同步异常时 catch 块按标志补偿 dec, 然后 throw 让 SerializationWorker 走 onUnhandledError 处理 state.
        boolean serializingDec = false;
        boolean ioPendingIncWithoutFuture = false;
        try {
            // v0.9: 测 ChunkNbtAssembler.assemble 耗时回写 ChunkLatencyTracker.
            // 这部分耗时跟 SerializationWorker 整体测的 worker time 几乎相等 —
            // 后续 ioBridge.storeChunk 仅提交 future 立即返回, 占比 < 0.1%.
            // 单独测这段而非复用 worker time, 避免改 SaveTask 接口加 onComplete 回调.
            long assembleT0 = System.nanoTime();
            CompoundTag tag = ChunkNbtAssembler.assemble(snapshot);
            long assembleNs = System.nanoTime() - assembleT0;
            metrics.decInFlightSerializing();
            serializingDec = true;
            if (latencyTracker != null) {
                latencyTracker.record(snapshot.pos().toLong(),
                        snapshot.dimension().location().toString(),
                        assembleNs);
            }

            // ioPendingIncWithoutFuture 守 submitIo 内 incInFlightIoPending 与 future 注册之间的窗口:
            // submitIo 内 storeChunk 抛同步异常时 inc 已发生但 whenComplete 没注册, 外层 catch 补 dec.
            // submitIo 正常返回 (future 注册成功) 后置 false, gauge 复位责任移交 whenComplete.
            ioPendingIncWithoutFuture = true;
            ChunkSaveState state = snapshot.state();
            submitIo(state, tag);
            ioPendingIncWithoutFuture = false;
        } catch (Throwable t) {
            // v0.7.1 修复 (C2): execute 同步异常路径 gauge 复位.
            if (!serializingDec) {
                metrics.decInFlightSerializing();
            }
            if (ioPendingIncWithoutFuture) {
                metrics.decInFlightIoPending();
            }
            throw t;
        }
    }

    /**
     * v0.10.2 修复 (M2): 把 IO 提交 + 完成回调抽成可重入方法, 让 REQUEUE_DIRTY 失败直接用
     * 已序列化的 tag 原地重投, 不依赖 chunk 对象是否仍加载.
     *
     * <p><b>为何不再走坐标恢复队列</b>: 序列化早在 assemble 阶段完成, 失败回调里 snapshot/tag
     * 都还活着. 重试 IO 根本不需要 chunk 对象 — 之前 REQUEUE_DIRTY 投坐标进 ChunkRecoveryQueue,
     * 主线程 drain 时 getChunkNow 返 null (chunk 已 unload) 就 WARN 丢弃, 等于 unload 后弃疗.
     * 直接用 tag 重投 IO 后, chunk 是否仍加载与重试成败无关.
     *
     * <p><b>线程安全</b>: 本方法首次由 SerializationWorker 线程调, 重投由 IOWorker 完成回调线程
     * (whenComplete) 调. ioBridge.storeChunk -> IOWorker.store -> submitTask -> mailbox.askEither,
     * vanilla ProcessorMailbox 是线程安全消息队列, 设计上允许任意线程投递; pendingWrites 仅在
     * mailbox 单消费者线程内访问. 故任意线程调 storeChunk 安全, 不引入新 race.
     *
     * <p><b>mustDrain 生命周期</b>: REQUEUE_DIRTY 时**不**清 mustDrain — 重投仍在途, 关服 join
     * 必须继续等. 仅终态 (CLEAN_LANDED / FAILED_TERMINAL) 清 mustDrain 并配平 gauge. 退避用
     * retryCount/maxRetries 终止, 不额外加 sleep (whenComplete 跑在 IOWorker 线程, sleep 会堵
     * mailbox 消费; vanilla IOWorker 自身已有 region file 排队, 失败多为持续性故障, 无谓退避).
     */
    private void submitIo(ChunkSaveState state, CompoundTag tag) {
        state.enterIoPending();
        metrics.incInFlightIoPending();
        long submitNs = System.nanoTime();
        CompletableFuture<Void> future = ioSubmitter.submit(tag);
        future.whenComplete((ignored, error) -> {
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            if (error != null) {
                LOGGER.error("[BetterAutoSave] IO store failed for chunk {} dim={}",
                        snapshot.pos(), snapshot.dimension().location(), error);
                ChunkSaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
                if (outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL) {
                    // 重试耗尽: ioFailed 内部 CAS 已清 mustDrain boolean, 用其返回值配平 gauge,
                    // 杜绝"先读快照 wasDraining 再内部静默 clear"拆分被并发 inc 抢跑漏 dec (v0.10.2 修复 M6).
                    // 还原 isUnsaved 让 vanilla 同步兜底.
                    if (state.lastTransitionClearedMustDrain()) {
                        metrics.decMustDrainPending();
                    }
                    // v0.10.2 修复 (C-chunk-unload-collision, 隐角 C): 终态前若挂着接力快照, 它的最新代
                    // 增量随这次彻底失败一并丢失。在飞 task 已死, 无法再接力。取走清空防泄漏并明示 ERROR ——
                    // 对仍加载的 chunk, 下面 enqueueRecovery 还原 isUnsaved 让 vanilla 同步重写最新内存救回;
                    // 已卸载的, ChunkRecoveryQueue.drain 的 terminal+unloaded 分支再升级 ERROR (带 dim+坐标)。
                    if (state.takePendingSnapshot() != null) {
                        LOGGER.error("[BetterAutoSave] chunk {} dim={} had a pending relay snapshot when IO hit terminal "
                                        + "retry limit; its latest-generation increment may be lost (vanilla sync fallback "
                                        + "will recover it only if still loaded)",
                                snapshot.pos(), snapshot.dimension().location());
                    }
                    enqueueRecovery(outcome);
                    metrics.recordChunkFailed();
                    return;
                }
                // 未超 maxRetries: 用已序列化的 tag 原地重投, 不清 mustDrain (重投仍在途,
                // 关服 join 必须继续等). ioFailed 在 REQUEUE_DIRTY 不碰 mustDrain, gauge 维持.
                metrics.recordChunkRetried();
                submitIo(state, tag);
                return;
            }
            // 成功回调: 终态 (CLEAN_LANDED 或 stale REQUEUE_DIRTY) 都终结本轮 in-flight.
            // CLEAN_LANDED 时 ioCompletedSuccessfully 内部 CAS 清 mustDrain boolean, 用其返回值配平 gauge
            // (v0.10.2 修复 M6: 单一 CAS 既清 boolean 又驱动 dec, 无读快照拆分缝隙).
            ChunkSaveState.IoOutcome outcome = state.ioCompletedSuccessfully();
            if (state.lastTransitionClearedMustDrain()) {
                metrics.decMustDrainPending();
            }
            if (outcome == ChunkSaveState.IoOutcome.CLEAN_LANDED) {
                metrics.recordChunkCompleted();
            } else {
                // 落盘成功但 chunk 期间 generation 前进 → REQUEUE_DIRTY. 本 tag 字节已写入 region file
                // (这一代已落盘), 但更新代的增量尚未落。
                // v0.10.2 修复 (C-chunk-unload-collision): 优先用接力快照接力 —— 若 mixin 碰撞分支登记过
                // pending (该 chunk 在飞期间被编辑且卸载), 取出它重投, 把最新内存代落盘, 不依赖 chunk 是否
                // 仍加载。无 pending 时退回原语义: chunk 仍加载则编辑已 setUnsaved(true), 下轮 mixin 接管。
                ChunkSnapshot pending = state.takePendingSnapshot();
                if (pending != null && pendingReoffer != null) {
                    // 重投在序列化 worker 上做 assemble (不在本 IOWorker 邮箱线程内联, 防堵全服写盘)。
                    // reenterSerializingForPending 把 inFlightGeneration 锁到 pending 自己的代 (隐角 A)。
                    state.reenterSerializingForPending(pending.capturedGeneration());
                    metrics.incInFlightSerializing();
                    pendingReoffer.reoffer(pending);
                }
                metrics.recordChunkRetried();
            }
            // BAS 公开 API: chunk 已成功落盘 (CLEAN_LANDED 或 REQUEUE_DIRTY 都说明
            // tag 字节已写入 region file). 触发外部 listener (例如 BetterBackup).
            // listener 异常已在 Registry 层 catch + log, 此处不会抛出.
            SaveListenerRegistry.fireChunkSaved(snapshot.pos(), snapshot.dimension(), tag);
        });
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        ChunkSaveState state = snapshot.state();
        // worker 未捕获异常无 tag 可重投, 该轮 async in-flight 到此终结 (后续靠坐标恢复队列 +
        // vanilla 兜底, 不再有挂在 mustDrain 上的在途任务). 故无论 ioFailed 返 REQUEUE_DIRTY 还是
        // FAILED_TERMINAL 都必须无条件清 mustDrain — 否则 REQUEUE_DIRTY 下 mustDrain 永挂, 关服 join
        // 死等且 gauge 泄漏. compareAndClearMustDrain 单一 CAS 既清 boolean 又驱动 dec (v0.10.2 修复 M6).
        if (state.compareAndClearMustDrain()) {
            metrics.decMustDrainPending();
        }
        ChunkSaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
        // worker 未捕获异常 (assemble 后 / IO 提交期抛非 IOException) 把 phase 推到 DIRTY/FAILED,
        // 但 tag 此刻不在 onUnhandledError 作用域内 (assemble 抛则根本没 tag, storeChunk 同步抛则
        // tag 在 execute 局部), 无法在此重投. 仍投坐标恢复队列还原 isUnsaved 让 vanilla 兜底.
        enqueueRecovery(outcome);
        if (outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL) {
            metrics.recordChunkFailed();
        } else {
            metrics.recordChunkRetried();
        }
        LOGGER.error("[BetterAutoSave] worker uncaught for {}", taskName(), cause);
    }

    /**
     * 重试耗尽 (FAILED_TERMINAL) 后投坐标恢复队列还原 isUnsaved, 让 vanilla 同步兜底.
     * v0.10.2 修复 (M2) 起 REQUEUE_DIRTY 改为 tag 原地重投, 不再走此队列 — 这里只剩
     * FAILED_TERMINAL 与 onUnhandledError 两个无 tag 可重投的入口. dimension 取
     * location().toString() 与三条重入门口径一致. unloaded + terminal 由
     * ChunkRecoveryQueue.drain 升级到 ERROR 日志 (带 dim+坐标), 明示本次增量丢失.
     */
    private void enqueueRecovery(ChunkSaveState.IoOutcome outcome) {
        boolean terminal = outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL;
        recoveryQueue.offer(snapshot.dimension().location().toString(), snapshot.pos().toLong(), terminal);
    }
}
