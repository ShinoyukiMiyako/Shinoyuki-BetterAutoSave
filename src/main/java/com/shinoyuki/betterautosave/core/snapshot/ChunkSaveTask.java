package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

public final class ChunkSaveTask implements SaveTask {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final ChunkSnapshot snapshot;
    private final ServerLevel level;
    private final AsyncIoBridge ioBridge;
    private final SaveMetrics metrics;

    public ChunkSaveTask(ChunkSnapshot snapshot, ServerLevel level, AsyncIoBridge ioBridge, SaveMetrics metrics) {
        this.snapshot = snapshot;
        this.level = level;
        this.ioBridge = ioBridge;
        this.metrics = metrics;
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
            CompoundTag tag = ChunkNbtAssembler.assemble(snapshot);
            metrics.decInFlightSerializing();
            serializingDec = true;

            ChunkSaveState state = snapshot.state();
            state.enterIoPending();
            metrics.incInFlightIoPending();
            ioPendingIncWithoutFuture = true;
            long submitNs = System.nanoTime();
            CompletableFuture<Void> future = ioBridge.storeChunk(level, snapshot.pos(), tag);
            // future 注册成功, whenComplete 必触发, 由 whenComplete 内 dec ioPending,
            // 此处放弃 gauge 复位责任.
            ioPendingIncWithoutFuture = false;
            future.whenComplete((ignored, error) -> {
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            // mustDrain 生命周期: mixin tryMarkMustDrain inc 一次,
            // worker 完成一轮 (无论 outcome) compareAndClearMustDrain dec 一次,
            // 保证 inc/dec 配对, 不依赖状态机内部清 mustDrain.
            // REQUEUE_DIRTY 路径下 chunk 重新 dirty, 下次 mixin 接管时
            // tryMarkMustDrain CAS false->true 再次成功 inc, 配对维持.
            boolean wasDraining = state.compareAndClearMustDrain();
            if (error != null) {
                LOGGER.error("[BetterAutoSave] IO store failed for chunk {} dim={}", snapshot.pos(), snapshot.dimension().location(), error);
                ChunkSaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
                if (wasDraining) {
                    metrics.decMustDrainPending();
                }
                if (outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL) {
                    metrics.recordChunkFailed();
                } else {
                    metrics.recordChunkRetried();
                }
                return;
            }
            ChunkSaveState.IoOutcome outcome = state.ioCompletedSuccessfully();
            if (wasDraining) {
                metrics.decMustDrainPending();
            }
            if (outcome == ChunkSaveState.IoOutcome.CLEAN_LANDED) {
                metrics.recordChunkCompleted();
            } else {
                metrics.recordChunkRetried();
            }
            // BAS 公开 API: chunk 已成功落盘 (CLEAN_LANDED 或 REQUEUE_DIRTY 都说明
            // tag 字节已写入 region file). 触发外部 listener (例如 BetterBackup).
            // listener 异常已在 Registry 层 catch + log, 此处不会抛出.
            SaveListenerRegistry.fireChunkSaved(snapshot.pos(), snapshot.dimension(), tag);
        });
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

    @Override
    public void onUnhandledError(Throwable cause) {
        ChunkSaveState state = snapshot.state();
        boolean wasDraining = state.compareAndClearMustDrain();
        ChunkSaveState.IoOutcome outcome = state.ioFailed(BetterAutoSaveConfig.maxRetries());
        if (wasDraining) {
            metrics.decMustDrainPending();
        }
        if (outcome == ChunkSaveState.IoOutcome.FAILED_TERMINAL) {
            metrics.recordChunkFailed();
        } else {
            metrics.recordChunkRetried();
        }
        LOGGER.error("[BetterAutoSave] worker uncaught for {}", taskName(), cause);
    }
}
