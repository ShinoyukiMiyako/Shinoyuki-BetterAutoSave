package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * IO 失败后的主线程待恢复队列 (Critical 修复 2).
 *
 * <p><b>背景</b>: ChunkSaveTask 的 IO 失败回调 (whenComplete 错误分支 /
 * onUnhandledError) 跑在 IOWorker 线程, 调 {@code ChunkSaveState.ioFailed} 把 phase
 * 置回 DIRTY (REQUEUE_DIRTY) 或 FAILED (FAILED_TERMINAL). 但三条重入路径
 * (ChunkMapMixin autosave / ChunkMapSaveMixin eager+unload / SaveDispatcher) 全部以
 * vanilla {@code chunk.isUnsaved()} 为门, 而 capture 早已 {@code setUnsaved(false)}.
 * IO 失败后 vanilla isUnsaved 仍是 false → 三条门全跳过 → 失败 chunk 除非玩家再次编辑
 * 否则永久丢失本次快照, 关服 vanilla flush 同样按 isUnsaved 过滤救不回.
 *
 * <p><b>设计</b>: 失败回调投 (dimensionId, packedPos) 进本队列 (worker 线程安全),
 * 主线程 tick 路径 drain: 按 dim 找已加载 LevelChunk, {@code setUnsaved(true)} 还原
 * vanilla 重入门 (经 ChunkAccessMixin 触发 markDirty 把 phase 推回 DIRTY,
 * CAS(CLEAN->DIRTY) 在 phase 已是 DIRTY 时失败无害). chunk 已 unload 则该数据已由
 * unload 路径处理或确已丢失, log WARN. FAILED_TERMINAL 同样还原 isUnsaved 让 vanilla
 * 同步路径兜底.
 *
 * <p><b>不覆盖 entity 路径</b>: entity 重入由 PersistentEntitySectionManager.autoSave()
 * 每周期无条件对非空 chunk 调 storeEntities 驱动, 唯一的重入门是 EntitySaveState.phase
 * (没有 isUnsaved 等价标志). ioFailed 把 phase 置回 DIRTY/FAILED 后, 下个 autoSave
 * 周期 EntityStorageMixin 自然命中 DIRTY -> trySnapshot 重新 dispatch (或 FAILED ->
 * vanilla 兜底). entity 路径本就自愈, 无需恢复队列.
 */
public final class ChunkRecoveryQueue {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    /**
     * chunk 的 vanilla unsaved 标志写入句柄. 抽象出 {@code LevelChunk.setUnsaved} 以便
     * recovery / dispatch 复位逻辑单测 (MC 类单测无法实例化). 与 SaveDispatcher 复位 seam
     * 复用同一抽象.
     */
    @FunctionalInterface
    public interface UnsavedSetter {
        void setUnsaved(boolean unsaved);
    }

    /** 一条待恢复记录: 维度 id + packed chunk pos + 是否终态 (仅影响 log 级别). */
    public record Entry(String dimensionId, long packedPos, boolean terminal) {
    }

    /** 主线程 drain 时把 (dimId, packedPos) 解析为已加载 chunk 的 setUnsaved 句柄; 未加载返 null. */
    @FunctionalInterface
    public interface ChunkResolver {
        UnsavedSetter resolve(String dimensionId, long packedPos);
    }

    private final ConcurrentLinkedQueue<Entry> pending = new ConcurrentLinkedQueue<>();

    /** worker 线程调: IO 失败后投递待恢复 chunk. terminal=true 表示 FAILED_TERMINAL. */
    public void offer(String dimensionId, long packedPos, boolean terminal) {
        pending.offer(new Entry(dimensionId, packedPos, terminal));
    }

    public int size() {
        return pending.size();
    }

    /**
     * 主线程调: drain 队列, 对每条记录用 resolver 找已加载 chunk 并 setUnsaved(true).
     * resolver 返 null (chunk 已 unload) 时 log WARN — 数据已由 unload 路径处理或确已丢失.
     *
     * @return 实际恢复 (setUnsaved 调用成功) 的 chunk 数
     */
    public int drain(ChunkResolver resolver) {
        int recovered = 0;
        Entry e;
        while ((e = pending.poll()) != null) {
            UnsavedSetter chunk = resolver.resolve(e.dimensionId(), e.packedPos());
            if (chunk == null) {
                LOGGER.warn("[BetterAutoSave] IO-failed chunk {} dim={} no longer loaded; "
                                + "recovery skipped (data handled by unload path or already lost)",
                        e.packedPos(), e.dimensionId());
                continue;
            }
            // setUnsaved(true) 还原 vanilla 重入门; 经 ChunkAccessMixin 触发 markDirty,
            // 让下一轮 autosave (REQUEUE_DIRTY: phase 已 DIRTY) 或 vanilla 同步
            // (FAILED_TERMINAL: phase=FAILED, BAS 重入门让出给 vanilla) 兜底落盘.
            chunk.setUnsaved(true);
            recovered++;
            if (e.terminal()) {
                LOGGER.error("[BetterAutoSave] IO-failed chunk {} dim={} hit terminal retry limit; "
                                + "restored vanilla unsaved flag for synchronous fallback",
                        e.packedPos(), e.dimensionId());
            }
        }
        return recovered;
    }
}
