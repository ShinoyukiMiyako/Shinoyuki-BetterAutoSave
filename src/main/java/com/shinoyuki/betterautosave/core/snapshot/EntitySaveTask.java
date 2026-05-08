package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.state.EntitySaveState;
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

    private final EntitySnapshot snapshot;
    private final IOWorker entityIoWorker;
    private final SaveMetrics metrics;

    public EntitySaveTask(EntitySnapshot snapshot, IOWorker entityIoWorker, SaveMetrics metrics) {
        this.snapshot = snapshot;
        this.entityIoWorker = entityIoWorker;
        this.metrics = metrics;
    }

    @Override
    public String taskName() {
        return "entity@" + snapshot.pos() + "/" + snapshot.dimension().location();
    }

    @Override
    public void execute() {
        CompoundTag tag = EntityNbtAssembler.assemble(snapshot);
        metrics.decInFlightSerializing();

        EntitySaveState state = snapshot.state();
        state.enterIoPending();
        metrics.incInFlightIoPending();
        long submitNs = System.nanoTime();
        CompletableFuture<Void> future = entityIoWorker.store(snapshot.pos(), tag);
        future.whenComplete((ignored, error) -> {
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            boolean wasDraining = state.compareAndClearMustDrain();
            if (error != null) {
                LOGGER.error("[BetterAutoSave] entity IO store failed for chunk {} dim={}",
                        snapshot.pos(), snapshot.dimension().location(), error);
                EntitySaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
                if (wasDraining) {
                    metrics.decMustDrainPending();
                }
                if (outcome == EntitySaveState.IoOutcome.FAILED_TERMINAL) {
                    metrics.recordEntityFailed();
                } else {
                    metrics.recordEntityRetried();
                }
                return;
            }
            EntitySaveState.IoOutcome outcome = state.ioCompletedSuccessfully();
            if (wasDraining) {
                metrics.decMustDrainPending();
            }
            if (outcome == EntitySaveState.IoOutcome.CLEAN_LANDED) {
                metrics.recordEntityCompleted();
            } else {
                metrics.recordEntityRetried();
            }
            // BAS 公开 API: entity chunk 已成功落盘. 触发外部 listener.
            // 跟 ChunkSaveTask 同语义, listener 异常 Registry 层 catch + log.
            SaveListenerRegistry.fireEntityChunkSaved(snapshot.pos(), snapshot.dimension(), tag);
        });
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        EntitySaveState state = snapshot.state();
        boolean wasDraining = state.compareAndClearMustDrain();
        EntitySaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
        if (wasDraining) {
            metrics.decMustDrainPending();
        }
        if (outcome == EntitySaveState.IoOutcome.FAILED_TERMINAL) {
            metrics.recordEntityFailed();
        } else {
            metrics.recordEntityRetried();
        }
        LOGGER.error("[BetterAutoSave] entity worker uncaught for {}", taskName(), cause);
    }
}
