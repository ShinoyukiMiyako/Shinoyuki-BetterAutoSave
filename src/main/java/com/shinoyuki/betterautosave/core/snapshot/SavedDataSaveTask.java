package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * v0.7 worker 端 SavedData 写盘任务. 与 {@link ChunkSaveTask} /
 * {@link EntitySaveTask} 同结构, 但实现最简: 主线程已构好完整 tag, worker
 * 仅调 {@link NbtIo#writeCompressed} 写盘 + 触发 listener.
 *
 * <p><b>失败重试策略</b>: vanilla {@code SavedData.save(File)} 失败时只
 * {@code LOGGER.error} 不抛, 不重试. BAS 行为升级: 失败时通过
 * {@link MinecraftServer#execute} 在主线程调 {@link net.minecraft.world.level.saveddata.SavedData#setDirty()},
 * 让下个 autosave 周期重试. **一致重试 + 同步 fallback** 由上层 vanilla
 * autosave 周期天然提供, 不需要 BAS 自己计 retry.
 *
 * <p><b>与 chunk / entity 路径的状态机差异</b>: SavedData 单次 IO 失败不进
 * "永久 FAILED" 状态 — 因为 SavedData 内存版本仍 dirty, 下次 autosave 自然
 * 再次 dispatch. chunk / entity 路径有 unload / 卸载语义, 错过一次 save 数据
 * 就丢了, 必须重试 + maxRetries 上限; SavedData 不存在 unload, 不需要这套.
 */
public final class SavedDataSaveTask implements SaveTask {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final SavedDataSnapshot snapshot;
    private final MinecraftServer server;
    private final SaveMetrics metrics;

    public SavedDataSaveTask(SavedDataSnapshot snapshot, MinecraftServer server, SaveMetrics metrics) {
        this.snapshot = snapshot;
        this.server = server;
        this.metrics = metrics;
    }

    @Override
    public String taskName() {
        return "savedData@" + snapshot.fileName();
    }

    @Override
    public void execute() {
        metrics.decInFlightSerializing();
        metrics.incInFlightIoPending();
        long submitNs = System.nanoTime();
        try {
            NbtIo.writeCompressed(snapshot.preBuiltTag(), snapshot.targetFile());
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            metrics.recordSavedDataCompleted();
            // BAS 公开 API: SavedData 已成功落盘. 触发外部 listener (BetterBackup 等).
            SaveListenerRegistry.fireSavedDataWritten(snapshot.fileName(), snapshot.preBuiltTag());
        } catch (IOException e) {
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            metrics.recordSavedDataFailed();
            // 重新 mark dirty 让下个 autosave 周期重试. setDirty 必须主线程调,
            // 因 mod 实现可能假设 dirty bit 由主线程操作 (vanilla 的 setDirty
            // 不带同步).
            server.execute(() -> snapshot.savedData().setDirty());
            LOGGER.error("[BetterAutoSave] SavedData {} write failed, re-marked dirty for next cycle",
                    snapshot.fileName(), e);
        }
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        metrics.decInFlightSerializing();
        metrics.recordSavedDataFailed();
        server.execute(() -> snapshot.savedData().setDirty());
        LOGGER.error("[BetterAutoSave] SavedData worker uncaught for {}", taskName(), cause);
    }
}
