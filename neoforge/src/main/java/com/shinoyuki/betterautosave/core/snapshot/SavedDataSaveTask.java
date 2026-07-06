package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.core.io.AtomicNbtWriter;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * worker 端 SavedData 写盘任务. 与 {@link ChunkSaveTask} /
 * {@link EntitySaveTask} 同结构, 但实现最简: 主线程已把外层 tag 序列化成脱钩字节,
 * worker 仅 gzip + 原子写盘 + (有 listener 时) 反序列化回 tag 触发 listener.
 *
 * <p><b>失败重试策略</b>: vanilla {@code SavedData.save(File)} 失败时只
 * {@code LOGGER.error} 不抛, 不重试. BAS 行为升级: 失败时**worker 线程直接调**
 * {@link net.minecraft.world.level.saveddata.SavedData#setDirty()}, 让下个 autosave
 * 周期重试. 不经 {@code server.execute} 异步派回主线程 —— 关服阶段若 IO 失败发生在
 * stopServer 主循环退出后, server task queue 不再消费 → setDirty 永远不执行 →
 * vanilla 关服同步路径看 dirty=false 跳过 → 数据永久丢失. 直接 worker 线程 setDirty
 * 没这个边界 — vanilla 的 SavedData.dirty 是普通 boolean 字段, 跨线程写入无 race
 * 检查 (mod 主线程读端跟 worker 写端原本就有 happens-before 由 worker→主线程 join 序列
 * 保证), 不引入新 race.
 *
 * <p><b>与 chunk / entity 路径的状态机差异</b>: SavedData 单次 IO 失败不进
 * "永久 FAILED" 状态 — 因为 SavedData 内存版本仍 dirty, 下次 autosave 自然
 * 再次 dispatch. chunk / entity 路径有 unload / 卸载语义, 错过一次 save 数据
 * 就丢了, 必须重试 + maxRetries 上限; SavedData 不存在 unload, 不需要这套.
 */
public final class SavedDataSaveTask implements SaveTask {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final SavedDataSnapshot snapshot;
    private final SaveMetrics metrics;

    public SavedDataSaveTask(SavedDataSnapshot snapshot, SaveMetrics metrics) {
        this.snapshot = snapshot;
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
            // 原子写 (tmp + fsync + ATOMIC_MOVE) 替代 vanilla 的直接覆盖,
            // 防写到一半崩溃留截断 .dat 导致下次加载 gzip 解压失败丢数据.
            AtomicNbtWriter.writeCompressed(snapshot.nbtBytes(), snapshot.targetFile());
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            metrics.recordSavedDataCompleted();
            // 回写历史 size = 本次未压缩字节长度 (非 gzip 后磁盘尺寸), 让 mixin 守卫按主线程真实内存足迹判据:
            // 大文件闸门要拦的是主线程 serializeUncompressed 分配的未压缩 byte[], 用压缩后尺寸会低估 5-10 倍放行超标文件。
            if (snapshot.historySizeMap() != null) {
                snapshot.historySizeMap().put(snapshot.fileName(), (long) snapshot.nbtBytes().length);
            }
            // BAS 公开 API: SavedData 已成功落盘. 触发外部 listener (BetterBackup 等).
            fireListenersIfAny();
        } catch (IOException e) {
            metrics.recordIoStoreNs(System.nanoTime() - submitNs);
            metrics.decInFlightIoPending();
            metrics.recordSavedDataFailed();
            // 重新 mark dirty 让下个 autosave 周期重试. setDirty 必须主线程调,
            // 因 mod 实现可能假设 dirty bit 由主线程操作 (vanilla 的 setDirty
            // 不带同步).
            snapshot.savedData().setDirty();
            LOGGER.error("[BetterAutoSave] SavedData {} write failed, re-marked dirty for next cycle",
                    snapshot.fileName(), e);
        } finally {
            // 释放在途文件名占位, 让下个周期可重新 dispatch 该文件.
            // finally 保证无论成功 / IOException 都释放; 非 IOException 逃到 onUnhandledError
            // 也另行释放 (该路径 execute 的 finally 已先跑).
            releaseInFlight();
        }
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        // execute 第一行已 dec serializing + inc ioPending,
        // 然后 AtomicNbtWriter.writeCompressed 抛**非 IOException** (例如 OOM / NPE) 时 try-catch
        // 不接, 异常逃到 worker 走 onUnhandledError. 此时:
        // - serializing 已 dec, 不能再 dec (会变负)
        // - ioPending 已 inc 但 try 内的 dec 路径全没跑到, 必须补 dec
        // 注意: execute 的 finally 已先于本方法跑, 在途占位已释放; 此处不重复释放
        // (newKeySet 的 remove 幂等无害, 但语义上占位已归 execute finally 负责).
        metrics.decInFlightIoPending();
        metrics.recordSavedDataFailed();
        snapshot.savedData().setDirty();
        LOGGER.error("[BetterAutoSave] SavedData worker uncaught for {}", taskName(), cause);
    }

    /**
     * 落盘成功后通知外部 listener (BetterBackup 等). 仅当真有注册 listener 时才把脱钩字节反序列化回
     * tag —— 无 listener 时跳过, 不为无人消费的 tag 付反序列化开销。
     *
     * <p>写盘此刻已成功; 反序列化失败 (理论不可能, 字节是本进程刚序列化的) 仅影响下游通知, 不影响数据
     * 正确性, 故记日志后跳过, 不重标 dirty。listener 自身抛异常由
     * {@link SaveListenerRegistry#fireSavedDataWritten} 内部逐个 suppress, 不外泄到本 task 计账。
     */
    private void fireListenersIfAny() {
        if (!SaveListenerRegistry.hasSavedDataListeners()) {
            return;
        }
        CompoundTag tag;
        try {
            tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(snapshot.nbtBytes())));
        } catch (IOException e) {
            LOGGER.error("[BetterAutoSave] SavedData {} listener notify skipped: NBT decode failed "
                    + "(data already on disk)", snapshot.fileName(), e);
            return;
        }
        SaveListenerRegistry.fireSavedDataWritten(snapshot.fileName(), tag);
    }

    private void releaseInFlight() {
        if (snapshot.inFlight() != null) {
            snapshot.inFlight().remove(snapshot.inFlightKey());
        }
    }

    /**
     * degraded 模式 worker 全灭、本 task 滞留队列永不 execute 时由 {@link SnapshotPipeline} 善后调用。
     * SavedData 在 dispatch 前已乐观 setDirty(false) + inc serializing, 本 task 永不写盘则 dirty=false 让
     * vanilla 关服 flush 跳过 -> 丢数据。重新 setDirty 让 vanilla 同步兜底, 释放在途占位, 并配平 serializing。
     * setDirty / ConcurrentHashMap remove / AtomicLong dec 均线程安全, 可在死 worker 的 uncaught handler 线程调。
     */
    void abandonOnDegrade() {
        metrics.decInFlightSerializing();
        snapshot.savedData().setDirty();
        releaseInFlight();
        LOGGER.error("[BetterAutoSave] SavedData {} stranded in worker queue at DEGRADED transition; "
                        + "re-marked dirty for vanilla synchronous fallback", snapshot.fileName());
    }
}
