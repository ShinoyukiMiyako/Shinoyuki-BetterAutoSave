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
     * {@code ioBridge.storeChunk(level, pos, tag)}. 抽出此接口让失败重投循环可单测
     * (单测无法构造真实 ServerLevel / IOWorker), 注入返回受控 future 的 fake.
     */
    @FunctionalInterface
    public interface IoSubmitter {
        CompletableFuture<Void> submit(CompoundTag tag);
    }

    /**
     * 接力快照重投 seam。给定 pending 快照, 把它包成新的 ChunkSaveTask 并 offer 回 chunkWorkerQueue
     * (由序列化 worker 做 assemble, **不**在 IOWorker 邮箱线程内联拼装 —— 内联会堵全服写盘)。生产环境
     * 绑到 {@link SnapshotPipeline}; 单测注入记录性 fake 以验证重投触发与代际接力。
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
        // execute 内同步异常路径必须复位 gauge.
        // assemble 抛 → mixin 已 inc serializing 但本函数未 dec → 永久 +1.
        // serializingDec 标志记录"已 dec serializing", assemble 抛时 catch 补 dec, 然后 throw 让
        // SerializationWorker 走 onUnhandledError 处理 state.
        // ioPending 的同步抛补偿在 submitIo 内部 (submitIo 自包 try 抵消 inc 并走 onIoFailure, 不向上抛),
        // 故此处无需 ioPending 守卫 —— submitIo 在 future 注册前不会抛到这里。
        boolean serializingDec = false;
        try {
            // 测 ChunkNbtAssembler.assemble 耗时回写 ChunkLatencyTracker.
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

            ChunkSaveState state = snapshot.state();
            submitIo(state, tag);
        } catch (Throwable t) {
            // execute 同步异常路径 gauge 复位 (assemble 抛, submitIo 已自包补偿不会抛到此).
            if (!serializingDec) {
                metrics.decInFlightSerializing();
            }
            throw t;
        }
    }

    /**
     * 把 IO 提交 + 完成回调抽成可重入方法, 让 REQUEUE_DIRTY 失败直接用已序列化的 tag 原地重投,
     * 不依赖 chunk 对象是否仍加载.
     *
     * <p><b>为何不走坐标恢复队列</b>: 序列化早在 assemble 阶段完成, 失败回调里 snapshot/tag 都还活着.
     * 重试 IO 根本不需要 chunk 对象 — 投坐标进 ChunkRecoveryQueue 时, 主线程 drain 若 getChunkNow 返 null
     * (chunk 已 unload) 会 WARN 丢弃, 等于 unload 后增量丢失. 直接用 tag 重投 IO 后, chunk 是否仍加载与重试
     * 成败无关.
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
        CompletableFuture<Void> future;
        try {
            future = ioSubmitter.submit(tag);
        } catch (Throwable submitError) {
            // submit 同步抛 (生产几乎仅 teardown-NPE / OOM): 回调线程内递归 submitIo 时若漏防则 inc 已发生而无
            // future 可 dec —— 永久泄漏 + phase 卡 IO_PENDING。此处自包补偿: dec 抵消本行 inc, 走与 IO 失败
            // (future.completeExceptionally) 等同的 onIoFailure 终态/重投补偿, 不向 dependent future 漏抛
            // (whenComplete 回调内的递归调用也因此安全)。
            // 不 recordIoStoreNs: submit 同步抛根本没发生 store, 记一个近零样本会污染 IO 延迟直方图。
            metrics.decInFlightIoPending();
            onIoFailure(state, tag, submitError);
            return;
        }
        // whenComplete 回调返回的 dependent future 若被丢弃, 回调内任何同步抛 (含 onIoFailure/safeReoffer
        // 自身再抛的 Error) 会静默落进无人 join 的 future。挂 exceptionally 兜底网, 保证"永不静默": 即便回调内
        // 精确补偿再抛, 至少 ERROR 可见。
        future.whenComplete((ignored, error) -> {
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            if (error != null) {
                LOGGER.error("[BetterAutoSave] IO store failed for chunk {} dim={}",
                        snapshot.pos(), snapshot.dimension().location(), error);
                onIoFailure(state, tag, error);
                return;
            }
            onIoSuccess(state, tag);
        }).exceptionally(callbackError -> {
            // 回调体同步抛且未被回调内精确补偿吸收 (多为 Error): 不静默, 明示 ERROR。
            LOGGER.error("[BetterAutoSave] IO completion callback threw for chunk {} dim={}; in-flight gauges may be "
                            + "left inconsistent for this task",
                    snapshot.pos(), snapshot.dimension().location(), callbackError);
            return null;
        });
    }

    /**
     * IO 失败 (future 异常完成 *或* submit 同步抛) 的统一补偿路径。供回调线程与同步 submit 抛共用同一终态/
     * 重投逻辑, 杜绝两处漂移。
     */
    private void onIoFailure(ChunkSaveState state, CompoundTag tag, Throwable cause) {
        ChunkSaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
        if (outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL) {
            handleTerminalFailure(state);
            return;
        }
        // 未超 maxRetries: 用已序列化的 tag 原地重投, 不清 mustDrain (重投仍在途,
        // 关服 join 必须继续等). ioFailed 在 REQUEUE_DIRTY 不碰 mustDrain, gauge 维持.
        metrics.recordChunkRetried();
        submitIo(state, tag);
    }

    /**
     * FAILED_TERMINAL (IO 重试耗尽) 后按接力槽三态分流处置。只对 EMPTY_DEAD 真终态走兜底, PREPARING
     * 槽 (主线程仍在改写未就绪 tag) 绝不夺取, 杜绝跨线程读写同一 tag 的"未就绪 tag 暴露"。
     *
     * <p><b>前置状态</b>: ioFailed 已把 phase 推 FAILED 且 CAS 清了 mustDrain boolean
     * ({@code lastTransitionClearedMustDrain()} 记录是否真清)。下面按槽态决定是 honor 这次清除 (EMPTY_DEAD),
     * 还是把 mustDrain 恢复给接力链 (CONSUMED / HANDED_TO_MAIN)。
     *
     * <p><b>终值表 (mustDrain boolean / gauge / phase / 接力归属)</b>, 设进入时 gauge=G, boolean=true:
     * <pre>
     * 槽态            | 出口动作                              | mustDrain boolean | gauge | phase            | 谁落最新代
     * --------------- | ------------------------------------ | ----------------- | ----- | ---------------- | ----------
     * READY(CONSUMED) | reenter+safeReoffer 本 task 接力     | true(恢复)        | G     | SERIALIZING(reenter) | 本 task 接力 READY
     * PREPARING       | 标 missed 交还主线程, 不碰恢复路径   | true(恢复)        | G     | FAILED->主线程 reenter 覆盖 | 主线程 publish 自踢
     * (HANDED)        |                                      |                   |       |                  |
     * EMPTY(DEAD)     | honor 清除 + enqueueRecovery         | false             | G-1   | FAILED           | vanilla 同步兜底
     * </pre>
     * CONSUMED / HANDED_TO_MAIN 都"恢复" mustDrain: 跳过 ioFailed 清除的 gauge dec (不 dec) 并 markMustDrain
     * 把 boolean 拨回 true —— 净效果 gauge/boolean 不变, 由接力链 (本 task 或主线程自踢) 的真正终态唯一清除+dec。
     * 只有 EMPTY_DEAD 是真终态死亡: honor 清除 (dec gauge), 走 vanilla 兜底。
     *
     * <p><b>retryCount 不重置</b>: 接力沿用已耗尽的重试预算 (它是同一 state 的属性)。接力 IO 成功则
     * CLEAN_LANDED 自然归零 retryCount; 接力 IO 再失败则立即 FAILED_TERMINAL, 此时槽已 EMPTY (READY 已被本
     * task 消费) 走 EMPTY_DEAD 退 vanilla 兜底 —— 级联收敛, 不会无限接力重试 (重置 retryCount 反而会让持续性
     * IO 故障永不终态、永不退兜底)。
     */
    private void handleTerminalFailure(ChunkSaveState state) {
        ChunkSaveState.ReadyTake take = state.takeReadyForTerminalConsumer();
        switch (take.disposition()) {
            case CONSUMED -> {
                // 槽 READY (碰撞后最新代已就绪): 旧代 IO 终态失败, 但最新代不该随之丢 —— 接力它。接力优先于
                // vanilla 兜底 (接力落最新代, 兜底落旧内存代)。mustDrain 恢复 (接力在途, 关服 join 须继续等)。
                if (pendingReoffer != null) {
                    state.markMustDrain();
                    state.reenterSerializingForPending(take.snapshot().capturedGeneration());
                    safeReoffer(state, take.snapshot());
                    metrics.recordChunkRetried();
                    return;
                }
                // sink 不可达 (单测未注入): 无法接力, 退化为终态死亡。honor ioFailed 的清除, 走兜底。
                if (state.lastTransitionClearedMustDrain()) {
                    metrics.decMustDrainPending();
                }
                LOGGER.error("[BetterAutoSave] chunk {} dim={} had a READY relay snapshot when IO hit terminal retry "
                                + "limit but no reoffer sink; its latest-generation increment is lost (vanilla sync "
                                + "fallback will recover it only if still loaded)",
                        snapshot.pos(), snapshot.dimension().location());
                enqueueRecovery(ChunkSaveState.IoOutcome.FAILED_TERMINAL);
                metrics.recordChunkFailed();
            }
            case HANDED_TO_MAIN -> {
                // 槽 PREPARING: 主线程仍在 dispatch 改写这份未就绪 tag, 终态 task 绝不能夺走它接力 (数据竞争)。
                // takeReadyForTerminalConsumer 已标 missedCycle + 把 drainOwner 拨成 TERMINAL_HANDED (非 NONE),
                // 补踢交还主线程 publish 自踢。本 task 必须 early-return: 不 honor ioFailed 的 mustDrain 清除
                // (不 dec gauge), 不走 enqueueRecovery —— 否则与主线程自踢接力链双重处置同一 state, 且会过早清
                // mustDrain 让关服 join 不等接力。mustDrain (drainOwner) 由主线程自踢的接力链终态唯一清。
                //
                // **不**调 markMustDrain。drainOwner 已是 TERMINAL_HANDED (非 NONE, 满足 "槽非空 -> drainOwner
                // != NONE" 不变式, gauge 维持), markMustDrain 会把它覆盖成 IN_FLIGHT, 让 publishPendingSnapshot
                // 的 terminalHanded 触发点失活 (虽 sameCycleMissed 仍冗余兜底, 但保留 TERMINAL_HANDED 让两个
                // publish 自踢触发点都活, 协议对未来改动更稳健)。
                metrics.recordChunkRetried();
            }
            case EMPTY_DEAD -> {
                // 槽 EMPTY: 无就绪接力, 真终态死亡。honor ioFailed 的 mustDrain 清除 (配平 gauge),
                // enqueueRecovery 还原 isUnsaved 让 vanilla 同步兜底重写最新内存。
                if (state.lastTransitionClearedMustDrain()) {
                    metrics.decMustDrainPending();
                }
                enqueueRecovery(ChunkSaveState.IoOutcome.FAILED_TERMINAL);
                metrics.recordChunkFailed();
            }
        }
    }

    /**
     * IO 成功落地的统一处置。
     */
    private void onIoSuccess(ChunkSaveState state, CompoundTag tag) {
        // land (置 phase) 与 take (取 READY 接力 / 标 missed) 合并成 landAndTake 单 CAS 线性化点, 消除两步
        // (置 phase 与取槽) 之间的窗口 —— 主线程绝不会观测到 "已 land DIRTY 但 take 未发生" 的中间态, 故
        // stale missed 不会被误标成新周期序号。
        // CLEAN_LANDED 时 landAndTake 内部 CAS 清 drainOwner, 用 lastTransitionClearedMustDrain 配平 gauge
        // (单一 CAS 既清 drainOwner 又驱动 dec, 无读快照拆分缝隙).
        ChunkSaveState.LandResult land = state.landAndTake();
        if (state.lastTransitionClearedMustDrain()) {
            metrics.decMustDrainPending();
        }
        if (land.outcome() == ChunkSaveState.IoOutcome.CLEAN_LANDED) {
            metrics.recordChunkCompleted();
        } else {
            // 落盘成功但 chunk 期间 generation 前进 → REQUEUE_DIRTY. 本 tag 字节已写入 region file
            // (这一代已落盘), 但更新代的增量尚未落。
            // 优先用接力快照接力 —— 若 mixin 碰撞分支登记过 pending (该 chunk 在飞期间被编辑且卸载), 取出它
            // 重投, 把最新内存代落盘, 不依赖 chunk 是否仍加载。无 pending 时退回原语义: chunk 仍加载则编辑已
            // setUnsaved(true), 下轮 mixin 接管。
            // landAndTake 只取 READY 槽 (relayPending 非 null 仅当槽 READY); 遇 PREPARING (dispatch 仍在跑,
            // tag 未就绪) 时不取走而原子标 missedCycle (在同一 land CAS 里), 把补踢交还主线程 (publish 时自踢)。
            // 这关死"在飞回调取走 listener 仍在改写的未就绪 tag"。relayPending 为 null 时若槽仍非空
            // (PREPARING-missed), mustDrain 由主线程自踢的接力链终态清, 此处不碰。
            ChunkSnapshot pending = land.relayPending();
            if (pending != null && pendingReoffer != null) {
                // 重投在序列化 worker 上做 assemble (不在本 IOWorker 邮箱线程内联, 防堵全服写盘)。
                // reenterSerializingForPending 把 inFlightGeneration 锁到 pending 自己的代。
                // serializing gauge 的 inc 由 reoffer sink 在真正 offer 时做 (关服残窗 ERROR 路径不 inc)。
                state.reenterSerializingForPending(pending.capturedGeneration());
                safeReoffer(state, pending);
            }
            metrics.recordChunkRetried();
        }
        // BAS 公开 API: chunk 已成功落盘 (CLEAN_LANDED 或 REQUEUE_DIRTY 都说明
        // tag 字节已写入 region file). 触发外部 listener (例如 BetterBackup).
        // listener 异常已在 Registry 层 catch + log, 此处不会抛出.
        SaveListenerRegistry.fireChunkSaved(snapshot.pos(), snapshot.dimension(), tag);
    }

    /**
     * 接力重投的安全包装。reoffer sink 同步抛 (生产几乎仅 teardown-NPE / OOM) 时不得静默丢 pending ——
     * 此刻 pending 已从槽取出 (REQUEUE_DIRTY 路径来自 landAndTake().relayPending(), 两条终态路径来自
     * takeReadyForTerminalConsumer), 只活在调用栈局部, sink 抛则永久丢失且 mustDrain 永挂、serializing 可能泄漏。
     *
     * <p><b>补偿</b>: serializing gauge 由 sink 自身在 inc 与 offer 之间自包 try 配平 (见
     * {@link SnapshotPipeline} 的 reoffer sink, 抛前 dec 回); 本层只负责 mustDrain 终态配平 + ERROR ——
     * 该接力代彻底无法落盘 (无 vanilla 兜底门可还原, chunk 多已卸载), compareAndClearMustDrain 单 CAS 清
     * boolean 并驱动 gauge dec, 明示该代增量丢失。不再向 dependent future 漏抛。
     */
    private void safeReoffer(ChunkSaveState state, ChunkSnapshot pending) {
        try {
            pendingReoffer.reoffer(pending);
        } catch (Throwable reofferError) {
            if (state.compareAndClearMustDrain()) {
                metrics.decMustDrainPending();
            }
            LOGGER.error("[BetterAutoSave] pending relay reoffer threw for chunk {} dim={}; its latest-generation "
                            + "increment is lost (no in-flight task left to retry it)",
                    pending.pos(), pending.dimension().location(), reofferError);
        }
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        ChunkSaveState state = snapshot.state();
        LOGGER.error("[BetterAutoSave] worker uncaught for {}", taskName(), cause);

        // 接力槽前置消费, 与 whenComplete 终态路径对称。接力槽存在的唯一场景就是"在飞碰撞 + 卸载", 此刻
        // pending 是卸载坐标最新代的唯一副本。若清 mustDrain 却不取 pending, 会破坏不变式 (pendingSnapshot
        // 非空 -> mustDrain 恒真): 槽永久泄漏 (AtomicReference 持快照 + NBT/section 副本), 关服 join 不再等
        // 这条接力链。
        // takeReadyForTerminalConsumer 只消费 READY, PREPARING 标 missed 交还主线程 —— 无条件夺 PREPARING 会把
        // 主线程 dispatch 仍在原地改写的未就绪 tag 接力 assemble (跨线程读写同一 CompoundTag 的 HashMap, 静默数据
        // 损坏), 正是状态机要消灭的"未就绪 tag 暴露"在 drain 出口复活。判别 + 取走是同一 CAS 原子结果, 无 TOCTOU。
        ChunkSaveState.ReadyTake take = state.takeReadyForTerminalConsumer();
        switch (take.disposition()) {
            case CONSUMED -> {
                // 槽 READY (就绪最新代): 在飞 task 死亡, 它是该坐标接力的自然消费者, 直接重投。mustDrain 维持
                // (本路径未清过, 仍是 mixin 碰撞分支置的 true; 重投在途, 关服 join 须继续等)。不走 ioFailed/
                // enqueueRecovery —— 该轮 in-flight 由接力 task 接管 (与 whenComplete REQUEUE_DIRTY 重投同语义)。
                if (pendingReoffer != null) {
                    state.reenterSerializingForPending(take.snapshot().capturedGeneration());
                    safeReoffer(state, take.snapshot());
                    return;
                }
                // sink 不可达 (单测未注入接力 / 关服后): 无法重投, READY 最新代增量随这次 unhandled 丢失。
                // 退化走安全网 (清 mustDrain + ioFailed + enqueueRecovery 让 vanilla 兜底)。
                LOGGER.error("[BetterAutoSave] chunk {} dim={} had a READY relay snapshot in onUnhandledError but no "
                                + "reoffer sink; its latest-generation increment is lost (vanilla sync fallback will "
                                + "recover it only if still loaded)",
                        snapshot.pos(), snapshot.dimension().location());
                runUnhandledSafetyNet(state);
            }
            case HANDED_TO_MAIN -> {
                // 槽 PREPARING: 主线程仍在 dispatch 改写未就绪 tag, 终态 task 绝不能夺走接力 (数据竞争)。
                // takeReadyForTerminalConsumer 已标 missed, 补踢交还主线程 publish 自踢。本 task 必须 early-return:
                // 不清 mustDrain (维持给主线程自踢的接力链, 由其终态唯一清)、不走 ioFailed/enqueueRecovery ——
                // 否则与主线程自踢接力链双重处置同一 state, 且过早清 mustDrain 让关服 join 不等接力。
                metrics.recordChunkRetried();
            }
            case EMPTY_DEAD -> {
                // 槽 EMPTY: 无就绪接力, 真终态死亡。走安全网。
                runUnhandledSafetyNet(state);
            }
        }
    }

    /**
     * onUnhandledError 无接力可投时的安全网 (EMPTY_DEAD, 或 READY 但 sink 不可达)。无在途接力任务挂在
     * mustDrain 上, 故清 mustDrain —— 否则 REQUEUE_DIRTY 下 mustDrain 永挂, 关服 join 死等且 gauge 泄漏。
     * compareAndClearMustDrain 单一 CAS 既清 boolean 又驱动 dec。随后 ioFailed 推 phase + enqueueRecovery
     * 还原 isUnsaved 让 vanilla 兜底。
     */
    private void runUnhandledSafetyNet(ChunkSaveState state) {
        if (state.compareAndClearMustDrain()) {
            metrics.decMustDrainPending();
        }
        ChunkSaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
        // worker 未捕获异常 (assemble 后 / IO 提交期抛非 IOException) 把 phase 推到 DIRTY/FAILED,
        // 但 tag 此刻不在作用域内 (assemble 抛则根本没 tag, storeChunk 同步抛则 tag 在 execute 局部),
        // 无法在此重投. 仍投坐标恢复队列还原 isUnsaved 让 vanilla 兜底.
        enqueueRecovery(outcome);
        if (outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL) {
            metrics.recordChunkFailed();
        } else {
            metrics.recordChunkRetried();
        }
    }

    /**
     * 重试耗尽 (FAILED_TERMINAL) 后投坐标恢复队列还原 isUnsaved, 让 vanilla 同步兜底.
     * REQUEUE_DIRTY 走 tag 原地重投不经此队列, 故这里只剩 FAILED_TERMINAL 与 onUnhandledError 两个
     * 无 tag 可重投的入口. dimension 取 location().toString() 与三条重入门口径一致. unloaded + terminal
     * 由 ChunkRecoveryQueue.drain 升级到 ERROR 日志 (带 dim+坐标), 明示本次增量丢失.
     */
    private void enqueueRecovery(ChunkSaveState.IoOutcome outcome) {
        boolean terminal = outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL;
        recoveryQueue.offer(snapshot.dimension().location().toString(), snapshot.pos().toLong(), terminal);
    }
}
