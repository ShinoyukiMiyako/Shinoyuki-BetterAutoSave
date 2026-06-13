package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.state.EntitySaveState;
import com.shinoyuki.betterautosave.core.state.EntitySaveStateAccess;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * worker 端 entity save 任务. 与 {@link ChunkSaveTask} 同构, 但实现
 * 简单得多 — assembler 仅 outer tag 包装 (无 vanilla 私有 helper 调用),
 * IO 提交直接走 entity {@link IOWorker}.
 *
 * <p>持有 entity IOWorker 引用 (mixin inject 时通过 EntityStorageAccessor
 * 取出, 传给 task) 而非走 AsyncIoBridge — 避免 ServerLevel -> entityManager
 * -> permanentStorage -> worker 三层 accessor.
 */
public final class EntitySaveTask implements SaveTask {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    /**
     * IO 提交 seam, 与 {@link ChunkSaveTask.IoSubmitter} 同构. 生产环境绑到
     * {@code entityIoWorker.store(pos, tag)}, 单测注入返回受控 future 的 fake 以验证失败重投循环
     * (单测无法构造真实 IOWorker).
     */
    @FunctionalInterface
    public interface IoSubmitter {
        CompletableFuture<Void> submit(CompoundTag tag);
    }

    /**
     * 接力快照重投 seam, 与 {@link ChunkSaveTask.PendingReoffer} 对称。给定 pending EntitySnapshot 包成新
     * EntitySaveTask offer 回 entityWorkerQueue (assemble 由序列化 worker 做, 不在 IOWorker 邮箱线程内联)。
     * 生产环境绑到 {@link SnapshotPipeline}; 单测注入 fake。
     */
    @FunctionalInterface
    public interface PendingReoffer {
        void reoffer(EntitySnapshot pending);
    }

    private final EntitySnapshot snapshot;
    private final SaveMetrics metrics;
    private final IoSubmitter ioSubmitter;
    private final EntitySaveStateAccess stateOwner;
    private final PendingReoffer pendingReoffer;

    public EntitySaveTask(EntitySnapshot snapshot, IOWorker entityIoWorker, SaveMetrics metrics,
                          EntitySaveStateAccess stateOwner, PendingReoffer pendingReoffer) {
        this(snapshot, metrics, tag -> entityIoWorker.store(snapshot.pos(), tag), stateOwner, pendingReoffer);
    }

    /**
     * 测试用构造: 直接注入 IoSubmitter, 绕开 IOWorker. 仅供单测验证 submitIo 重投循环,
     * 生产代码一律走上面的公开构造. stateOwner / pendingReoffer 可为 null (单测不验证 map 剔除 / 接力时).
     */
    EntitySaveTask(EntitySnapshot snapshot, SaveMetrics metrics, IoSubmitter ioSubmitter) {
        this(snapshot, metrics, ioSubmitter, null, null);
    }

    EntitySaveTask(EntitySnapshot snapshot, SaveMetrics metrics, IoSubmitter ioSubmitter,
                   EntitySaveStateAccess stateOwner) {
        this(snapshot, metrics, ioSubmitter, stateOwner, null);
    }

    EntitySaveTask(EntitySnapshot snapshot, SaveMetrics metrics, IoSubmitter ioSubmitter,
                   EntitySaveStateAccess stateOwner, PendingReoffer pendingReoffer) {
        this.snapshot = snapshot;
        this.metrics = metrics;
        this.ioSubmitter = ioSubmitter;
        this.stateOwner = stateOwner;
        this.pendingReoffer = pendingReoffer;
    }

    @Override
    public String taskName() {
        return "entity@" + snapshot.pos() + "/" + snapshot.dimension().location();
    }

    @Override
    public void execute() {
        // 同 ChunkSaveTask, execute 同步异常路径必须复位 gauge.
        // ioPending 同步抛补偿在 submitIo 内部 (submitIo 自包 try, 不向上抛), 故此处无 ioPending 守卫,
        // 与 ChunkSaveTask 对称.
        boolean serializingDec = false;
        try {
            CompoundTag tag = EntityNbtAssembler.assemble(snapshot);
            metrics.decInFlightSerializing();
            serializingDec = true;

            EntitySaveState state = snapshot.state();
            submitIo(state, tag);
        } catch (Throwable t) {
            // 同 ChunkSaveTask 同步异常 gauge 复位 (assemble 抛, submitIo 已自包补偿).
            if (!serializingDec) {
                metrics.decInFlightSerializing();
            }
            throw t;
        }
    }

    /**
     * 把 IO 提交 + 完成回调抽成可重入方法, 让 REQUEUE_DIRTY 失败直接用已序列化的 tag 原地重投,
     * 与 {@link ChunkSaveTask#submitIo} 对称.
     *
     * <p><b>为何 entity 路径必须原地重投 (而非依赖 vanilla 自愈)</b>: vanilla
     * {@code PersistentEntitySectionManager.processChunkUnload} 先 storeEntities (BAS 同步
     * capture 完成) 后立即从内存驱逐实体并 {@code chunkLoadStatuses.remove}. 卸载后该坐标既无
     * section 也非 LOADED, 后续 autoSave / saveAll 永不再为它调 storeEntities. 此刻 BAS 持有的
     * 已序列化 tag 是唯一副本 — IO 失败若不重投, 该 chunk 全部实体增量永久静默丢失 (entity 路径
     * 无 chunk 侧的坐标恢复队列, 因实体已离开内存, 坐标恢复对 entity 无意义).
     *
     * <p><b>FAILED_TERMINAL 处置</b>: 重试耗尽后无对象可兜底 (chunk 已卸载, 无 isUnsaved 等价门),
     * 这里只能记 ERROR 明示该坐标实体增量永久丢失, 与 {@link ChunkSaveTask} enqueueRecovery 的
     * unloaded+terminal ERROR 升级语义对齐.
     *
     * <p><b>mustDrain 生命周期</b>: 与 chunk 路径一致 — REQUEUE_DIRTY 不清 mustDrain (重投在途,
     * 关服 join 须继续等); 终态 (CLEAN_LANDED / FAILED_TERMINAL) 由状态机内部 CAS 清 boolean 并
     * 返回结果驱动 gauge dec (无竞态配平).
     *
     * <p><b>线程安全</b>: 同 ChunkSaveTask — 首次由 SerializationWorker 线程调, 重投由 IOWorker
     * 完成回调线程调. IOWorker.store -> ProcessorMailbox 允许任意线程投递, 不引入新 race.
     */
    private void submitIo(EntitySaveState state, CompoundTag tag) {
        state.enterIoPending();
        metrics.incInFlightIoPending();
        long submitNs = System.nanoTime();
        CompletableFuture<Void> future;
        try {
            future = ioSubmitter.submit(tag);
        } catch (Throwable submitError) {
            // store 同步抛 (生产几乎仅 teardown-NPE / OOM): 回调线程内递归 submitIo 时若漏防则 inc 已发生而无
            // future 可 dec —— 永久泄漏 + phase 卡 IO_PENDING。自包补偿: dec 抵消本行 inc, 走与 IO 失败等同的
            // onIoFailure (entity 无坐标恢复队列, 终态仅 ERROR), 不向 dependent future 漏抛 (回调内递归也因此安全)。
            // 不 recordIoStoreNs: 同步抛根本没发生 store。
            metrics.decInFlightIoPending();
            onIoFailure(state, tag, submitError);
            return;
        }
        // 与 ChunkSaveTask 对称, 挂 exceptionally 兜底网保证回调体同步抛 (含补偿再抛的 Error) 永不静默落进
        // 无人 join 的 dependent future。
        future.whenComplete((ignored, error) -> {
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            if (error != null) {
                LOGGER.error("[BetterAutoSave] entity IO store failed for chunk {} dim={}",
                        snapshot.pos(), snapshot.dimension().location(), error);
                onIoFailure(state, tag, error);
                return;
            }
            onIoSuccess(state, tag);
        }).exceptionally(callbackError -> {
            LOGGER.error("[BetterAutoSave] entity IO completion callback threw for chunk {} dim={}; in-flight gauges "
                            + "may be left inconsistent for this task",
                    snapshot.pos(), snapshot.dimension().location(), callbackError);
            return null;
        });
    }

    /**
     * entity IO 失败 (future 异常完成 *或* store 同步抛) 的统一补偿, 与 {@link ChunkSaveTask#onIoFailure} 对称。
     */
    private void onIoFailure(EntitySaveState state, CompoundTag tag, Throwable cause) {
        EntitySaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
        if (outcome == EntitySaveState.IoOutcome.FAILED_TERMINAL) {
            // 重试耗尽: ioFailed 内部 CAS 已清 mustDrain boolean, 用其返回值配平 gauge,
            // 杜绝"读快照 + 内部静默 clear"拆分导致的孤儿 inc 漏 dec.
            if (state.lastTransitionClearedMustDrain()) {
                metrics.decMustDrainPending();
            }
            // 终态前若挂着接力快照, 在飞 task 已死无法接力, 该坐标实体已被 vanilla 驱逐出内存 (无 isUnsaved
            // 等价门可让 vanilla 兜底), 这份最新实体增量永久丢失。entity 无坐标恢复队列, 取走清空防泄漏并明示
            // ERROR (带 dim+坐标)。
            if (state.takePendingSnapshot() != null) {
                LOGGER.error("[BetterAutoSave] entity chunk {} dim={} had a pending relay snapshot when IO hit "
                                + "terminal retry limit; its latest entity increment is lost (entities already "
                                + "evicted from memory, no vanilla fallback)",
                        snapshot.pos(), snapshot.dimension().location());
            }
            metrics.recordEntityFailed();
            return;
        }
        // 未超 maxRetries: 用已序列化的 tag 原地重投, 不清 mustDrain (重投仍在途,
        // 关服 join 必须继续等). ioFailed 在 REQUEUE_DIRTY 不碰 mustDrain, gauge 维持.
        metrics.recordEntityRetried();
        submitIo(state, tag);
    }

    /**
     * entity IO 成功落地的统一处置, 与 {@link ChunkSaveTask#onIoSuccess} 对称。
     */
    private void onIoSuccess(EntitySaveState state, CompoundTag tag) {
        EntitySaveState.IoOutcome outcome = state.ioCompletedSuccessfully();
        if (outcome == EntitySaveState.IoOutcome.CLEAN_LANDED) {
            // 回调侧顺序纪律 (与主线程 registerPendingSnapshot 的写后读对偶): ioCompletedSuccessfully 已先把
            // phase 写成 CLEAN (终态写), 这里再 getAndSet 取 pending 槽 (析构式读)。两侧各自先写后读, 保证
            // "回调读 pending 时 register 已可见" 与 "register 重读 phase 时 CLEAN 已可见" 至少一方成立, 杜绝
            // 双不见。CLEAN_LANDED 一般蕴含 generation 未推进 (无碰撞), 槽应空; 但回调读到的 generation 可能是
            // 碰撞编辑推进 generation 之前的 stale 值, 此刻主线程已 register 了一份更新代 pending —— 若不取槽
            // 直接 evict, 会把这份唯一副本随状态对象送 GC 永久丢失。故 evict 前必取一次。
            EntitySnapshot pending = (EntitySnapshot) state.takePendingSnapshot();
            if (pending != null && pendingReoffer != null) {
                // 取到主线程登记的更新代 pending: 它是 vanilla 即将驱逐内存的唯一副本, 必须接力落盘而非随
                // 状态对象 evict 丢弃。CLEAN_LANDED 已清 mustDrain, 接力在途须恢复 (关服 join 继续等): markMustDrain
                // 把 boolean 拨回, 并跳过下面的 honor-dec (净效果 gauge 维持), 由接力链终态唯一清+dec。不 evict。
                state.markMustDrain();
                state.reenterSerializingForPending(pending.capturedGeneration());
                safeReoffer(state, pending);
                metrics.recordEntityRetried();
                SaveListenerRegistry.fireEntityChunkSaved(snapshot.pos(), snapshot.dimension(), tag);
                return;
            }
            // 槽空 (常规 CLEAN_LANDED, 无碰撞 pending): honor CLEAN_LANDED 的清除配平 gauge, 走 evict 收口。
            // 若 pending 非空但 sink 不可达 (单测未注入 reoffer): 退化为下面的 honor + evict, takePendingSnapshot
            // 已把槽清空防泄漏 (接力代增量随之丢失, 与无 sink 的终态路径一致, entity 无 vanilla 兜底)。
            if (state.lastTransitionClearedMustDrain()) {
                metrics.decMustDrainPending();
            }
            metrics.recordEntityCompleted();
            // CLEAN_LANDED 且槽确空是确认安全的终态. 尝试从 per-level 状态 map 剔除本条目, 防止 EntityStorage
            // 单例的状态 map 随进程运行无界增长. 剔除走 identity + phase==CLEAN 原子校验 (computeIfPresent),
            // 主线程已开新一轮 save 则保留留待下次 CLEAN_LANDED. 槽非空时上面已 early-return, 绝不会带 pending evict.
            if (stateOwner != null) {
                stateOwner.betterautosave$evictEntityStateIfClean(snapshot.pos().toLong(), state);
            }
            // BAS 公开 API: entity chunk 已成功落盘. 触发外部 listener.
            // 跟 ChunkSaveTask 同语义, listener 异常 Registry 层 catch + log.
            SaveListenerRegistry.fireEntityChunkSaved(snapshot.pos(), snapshot.dimension(), tag);
            return;
        }
        // 落盘成功但 generation 前进 → REQUEUE_DIRTY. 本 tag 字节已写入 (这代已落), 但更新代未落。
        // 优先用接力快照接力 —— mixin 碰撞分支登记的最新 entity 列表 (vanilla 即将驱逐的唯一副本) 取出重投,
        // 把最新代落盘, 不依赖实体是否仍在内存。reenterSerializingForPending 锁 pending 自己的代; mustDrain
        // 维持 (REQUEUE_DIRTY 不清)。
        EntitySnapshot pending = (EntitySnapshot) state.takePendingSnapshot();
        if (pending != null && pendingReoffer != null) {
            // serializing gauge 的 inc 由 reoffer sink 在真正 offer 时做 (关服残窗 ERROR 路径不 inc)。
            state.reenterSerializingForPending(pending.capturedGeneration());
            safeReoffer(state, pending);
        }
        metrics.recordEntityRetried();
        SaveListenerRegistry.fireEntityChunkSaved(snapshot.pos(), snapshot.dimension(), tag);
    }

    /**
     * 接力重投安全包装, 与 {@link ChunkSaveTask#safeReoffer} 对称。reoffer sink 同步抛时 pending 已被
     * takePendingSnapshot 取走 (getAndSet) 只活在栈局部 —— sink 抛则永久丢失。serializing gauge 由 sink 自身
     * inc/offer 间自包 try 配平; 本层负责 mustDrain 终态配平 + ERROR。entity 无 vanilla 兜底, 该坐标最新实体
     * 增量彻底丢失。不向 dependent future 漏抛。
     */
    private void safeReoffer(EntitySaveState state, EntitySnapshot pending) {
        try {
            pendingReoffer.reoffer(pending);
        } catch (Throwable reofferError) {
            if (state.compareAndClearMustDrain()) {
                metrics.decMustDrainPending();
            }
            LOGGER.error("[BetterAutoSave] entity pending relay reoffer threw for chunk {} dim={}; its latest entity "
                            + "increment is lost (entities already evicted, no vanilla fallback)",
                    pending.pos(), pending.dimension().location(), reofferError);
        }
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        EntitySaveState state = snapshot.state();
        LOGGER.error("[BetterAutoSave] entity worker uncaught for {}", taskName(), cause);

        // 与 ChunkSaveTask 对称, 接力槽前置消费。entity 侧更严重 —— 无坐标恢复队列, 实体已被 vanilla 驱逐出
        // 内存, 接力槽是最新实体增量 (命名生物/盔甲架/展示框等) 的唯一副本, 若清 mustDrain 不取 pending 则该坐标
        // 增量永久静默丢失且无 ERROR。
        EntitySnapshot pending = (EntitySnapshot) state.takePendingSnapshot();
        if (pending != null && pendingReoffer != null) {
            // 接力链可达: 重投最新代, mustDrain 维持 (重投仍在途, 关服 join 须继续等)。reoffer sink 自身在
            // 真正 offer 时 inc serializing, workersStopping 关服残窗走 ERROR 安全网并在那里清 mustDrain+gauge。
            // 本路径不调 ioFailed —— 该轮 in-flight 由接力 task 接管 (与 whenComplete REQUEUE_DIRTY 重投同语义)。
            state.reenterSerializingForPending(pending.capturedGeneration());
            // 与 whenComplete REQUEUE_DIRTY 路径共用 safeReoffer, 与 ChunkSaveTask 对称, reoffer sink 同步抛时
            // 不丢 pending —— mustDrain 终态配平 + ERROR。
            safeReoffer(state, pending);
            return;
        }

        // 无 pending 或 sink 不可达: 走原有安全网。无在途接力任务挂在 mustDrain 上, 故无条件清 mustDrain,
        // 否则 REQUEUE_DIRTY 下 mustDrain 永挂, 关服 join 死等且 gauge 泄漏 (单 CAS 配平)。
        if (pending != null) {
            // sink 为 null (无法重投): entity 无 vanilla 兜底, 该坐标最新实体增量永久丢失, 明示 ERROR 防静默。
            LOGGER.error("[BetterAutoSave] entity chunk {} dim={} had a pending relay snapshot in onUnhandledError but "
                            + "no reoffer sink; its latest entity increment is lost (entities already evicted, no "
                            + "vanilla fallback)",
                    snapshot.pos(), snapshot.dimension().location());
        }
        if (state.compareAndClearMustDrain()) {
            metrics.decMustDrainPending();
        }
        EntitySaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
        if (outcome == EntitySaveState.IoOutcome.FAILED_TERMINAL) {
            metrics.recordEntityFailed();
        } else {
            // 该 task 已死, 无后续 submitIo 重建 phase。ioFailed 的 REQUEUE 不再写 phase (只让 in-place 重投
            // 保持 IO_PENDING), 故安全网必须自行发布真终态 DIRTY, 否则 phase 停在 SERIALIZING 让 state 永卡在飞态。
            state.markNoInFlightDirty();
            // entity 无坐标恢复队列 (实体已离开内存, 坐标恢复无意义); 非终态 unhandled error 仅记数.
            metrics.recordEntityRetried();
        }
    }
}
