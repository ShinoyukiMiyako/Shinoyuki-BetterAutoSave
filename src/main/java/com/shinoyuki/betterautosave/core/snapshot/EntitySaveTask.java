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
 * v0.6 worker 端 entity save 任务. 与 {@link ChunkSaveTask} 同构, 但实现
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
     * v0.10.2 修复 (C1): IO 提交 seam, 与 {@link ChunkSaveTask.IoSubmitter} 同构. 生产环境绑到
     * {@code entityIoWorker.store(pos, tag)}, 单测注入返回受控 future 的 fake 以验证失败重投循环
     * (单测无法构造真实 IOWorker).
     */
    @FunctionalInterface
    public interface IoSubmitter {
        CompletableFuture<Void> submit(CompoundTag tag);
    }

    private final EntitySnapshot snapshot;
    private final SaveMetrics metrics;
    private final IoSubmitter ioSubmitter;
    private final EntitySaveStateAccess stateOwner;

    public EntitySaveTask(EntitySnapshot snapshot, IOWorker entityIoWorker, SaveMetrics metrics,
                          EntitySaveStateAccess stateOwner) {
        this(snapshot, metrics, tag -> entityIoWorker.store(snapshot.pos(), tag), stateOwner);
    }

    /**
     * 测试用构造: 直接注入 IoSubmitter, 绕开 IOWorker. 仅供单测验证 submitIo 重投循环,
     * 生产代码一律走上面的公开构造. stateOwner 可为 null (单测不验证 map 剔除时).
     */
    EntitySaveTask(EntitySnapshot snapshot, SaveMetrics metrics, IoSubmitter ioSubmitter) {
        this(snapshot, metrics, ioSubmitter, null);
    }

    EntitySaveTask(EntitySnapshot snapshot, SaveMetrics metrics, IoSubmitter ioSubmitter,
                   EntitySaveStateAccess stateOwner) {
        this.snapshot = snapshot;
        this.metrics = metrics;
        this.ioSubmitter = ioSubmitter;
        this.stateOwner = stateOwner;
    }

    @Override
    public String taskName() {
        return "entity@" + snapshot.pos() + "/" + snapshot.dimension().location();
    }

    @Override
    public void execute() {
        // v0.7.1 修复 (C2): 同 ChunkSaveTask, execute 同步异常路径必须复位 gauge.
        boolean serializingDec = false;
        boolean ioPendingIncWithoutFuture = false;
        try {
            CompoundTag tag = EntityNbtAssembler.assemble(snapshot);
            metrics.decInFlightSerializing();
            serializingDec = true;

            // ioPendingIncWithoutFuture 守 submitIo 内 incInFlightIoPending 与 future 注册之间的窗口:
            // submitIo 内 store 抛同步异常时 inc 已发生但 whenComplete 没注册, 外层 catch 补 dec.
            // submitIo 正常返回 (future 注册成功) 后置 false, gauge 复位责任移交 whenComplete.
            ioPendingIncWithoutFuture = true;
            EntitySaveState state = snapshot.state();
            submitIo(state, tag);
            ioPendingIncWithoutFuture = false;
        } catch (Throwable t) {
            // v0.7.1 修复 (C2): 同 ChunkSaveTask 同步异常 gauge 复位.
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
     * v0.10.2 修复 (C1): 把 IO 提交 + 完成回调抽成可重入方法, 让 REQUEUE_DIRTY 失败直接用
     * 已序列化的 tag 原地重投, 与 {@link ChunkSaveTask#submitIo} 对称.
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
     * 返回结果驱动 gauge dec (v0.10.2 修复 M6 的无竞态配平).
     *
     * <p><b>线程安全</b>: 同 ChunkSaveTask — 首次由 SerializationWorker 线程调, 重投由 IOWorker
     * 完成回调线程调. IOWorker.store -> ProcessorMailbox 允许任意线程投递, 不引入新 race.
     */
    private void submitIo(EntitySaveState state, CompoundTag tag) {
        state.enterIoPending();
        metrics.incInFlightIoPending();
        long submitNs = System.nanoTime();
        CompletableFuture<Void> future = ioSubmitter.submit(tag);
        future.whenComplete((ignored, error) -> {
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            if (error != null) {
                LOGGER.error("[BetterAutoSave] entity IO store failed for chunk {} dim={}",
                        snapshot.pos(), snapshot.dimension().location(), error);
                EntitySaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
                if (outcome == EntitySaveState.IoOutcome.FAILED_TERMINAL) {
                    // 重试耗尽: ioFailed 内部 CAS 已清 mustDrain boolean, 用其返回值配平 gauge,
                    // 杜绝"读快照 + 内部静默 clear"拆分导致的孤儿 inc 漏 dec (v0.10.2 修复 M6).
                    if (state.lastTransitionClearedMustDrain()) {
                        metrics.decMustDrainPending();
                    }
                    metrics.recordEntityFailed();
                    return;
                }
                // 未超 maxRetries: 用已序列化的 tag 原地重投, 不清 mustDrain (重投仍在途,
                // 关服 join 必须继续等). ioFailed 在 REQUEUE_DIRTY 不碰 mustDrain, gauge 维持.
                metrics.recordEntityRetried();
                submitIo(state, tag);
                return;
            }
            EntitySaveState.IoOutcome outcome = state.ioCompletedSuccessfully();
            if (outcome == EntitySaveState.IoOutcome.CLEAN_LANDED) {
                // CLEAN_LANDED 内部 CAS 已清 mustDrain boolean, 用其返回值配平 gauge.
                if (state.lastTransitionClearedMustDrain()) {
                    metrics.decMustDrainPending();
                }
                metrics.recordEntityCompleted();
                // v0.10.2 修复 (M4): CLEAN_LANDED 是确认安全的终态 (phase=CLEAN, 无在途 IO,
                // generation 未变即无 pending 编辑). 尝试从 per-level 状态 map 剔除本条目, 防止
                // EntityStorage 单例的状态 map 随进程运行无界增长. 剔除走 identity + phase==CLEAN
                // 原子校验 (computeIfPresent), 主线程已开新一轮 save 则保留留待下次 CLEAN_LANDED.
                if (stateOwner != null) {
                    stateOwner.betterautosave$evictEntityStateIfClean(snapshot.pos().toLong(), state);
                }
                // BAS 公开 API: entity chunk 已成功落盘. 触发外部 listener.
                // 跟 ChunkSaveTask 同语义, listener 异常 Registry 层 catch + log.
                SaveListenerRegistry.fireEntityChunkSaved(snapshot.pos(), snapshot.dimension(), tag);
                return;
            }
            // 落盘成功但 chunk 期间被重新接管 (generation 变化) → REQUEUE_DIRTY. tag 字节已写入,
            // 不重投本 tag (已过期); mustDrain 维持 (下轮接管的新 tag 仍在途语义). 不清不 dec.
            metrics.recordEntityRetried();
            SaveListenerRegistry.fireEntityChunkSaved(snapshot.pos(), snapshot.dimension(), tag);
        });
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        EntitySaveState state = snapshot.state();
        // worker 未捕获异常 (assemble 抛 / 同步抛) 无 tag 可重投, 该轮 async in-flight 到此终结,
        // 不再有挂在 mustDrain 上的在途任务. 故无论 REQUEUE_DIRTY 还是 FAILED_TERMINAL 都必须无条件
        // 清 mustDrain, 否则 REQUEUE_DIRTY 下 mustDrain 永挂, 关服 join 死等且 gauge 泄漏.
        // compareAndClearMustDrain 单一 CAS 既清 boolean 又驱动 dec (v0.10.2 修复 M6).
        if (state.compareAndClearMustDrain()) {
            metrics.decMustDrainPending();
        }
        EntitySaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
        if (outcome == EntitySaveState.IoOutcome.FAILED_TERMINAL) {
            metrics.recordEntityFailed();
        } else {
            // entity 无坐标恢复队列 (实体已离开内存, 坐标恢复无意义); 非终态 unhandled error 仅记数.
            metrics.recordEntityRetried();
        }
        LOGGER.error("[BetterAutoSave] entity worker uncaught for {}", taskName(), cause);
    }
}
