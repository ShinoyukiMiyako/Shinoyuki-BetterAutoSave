package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.core.dispatch.SaveDispatcher;
import com.shinoyuki.betterautosave.core.snapshot.ChunkRecoveryQueue;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChunkMapSaveMixin dispatch 异常 catch 路径的恢复契约单测.
 *
 * <p>现场: SnapshotPipeline.captureAndDispatchChunk 第一行 setUnsaved(false),
 * 随后 capture 抛异常冒泡到 ChunkMapSaveMixin 的 catch. 若 catch 只调
 * resetAfterFallback (归零 phase) + compareAndClearMustDrain + 计数 + log, 不还原
 * isUnsaved, 也不 cancel cir -> vanilla ChunkMap.save 方法体续跑撞 isUnsaved 门
 * (此刻 false) -> return false 跳过同步写 -> 本次保存丢失; 该 chunk 此后不再编辑则
 * unload 与关服 flush 同样按 isUnsaved 门跳过, 永久丢失.
 *
 * <p>catch 调 SaveDispatcher.recoverAfterDispatchFailure(state, levelChunk::setUnsaved),
 * 同时还原 isUnsaved=true, 让续跑的 vanilla 方法体过 isUnsaved 门 -> 当场同步写盘救回本次数据.
 *
 * <p>本测试不构造真实 LevelChunk (MC 类单测无法实例化), 复刻 ChunkAccessMixin.onSetUnsaved
 * 副作用 (setUnsaved(true) -> markDirty) 注入 fake setter, 模拟 catch 现场状态机交互.
 *
 * <p>判定标准: 注释掉 recoverAfterDispatchFailure 内 chunk.setUnsaved(true) 半步 ->
 * "catch 必须还原 isUnsaved" 断言挂; 注释掉 state.resetAfterFallback() -> "phase 回 DIRTY"
 * 与 trySnapshot 断言挂.
 */
class ChunkMapSaveRecoveryTest {

    @Test
    void catch_path_restores_unsaved_flag_so_vanilla_body_passes_isunsaved_gate() {
        // 复刻 capture 抛异常时刻: trySnapshot + setUnsaved(false) + enterSerializing 已跑.
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        assertEquals(ChunkSaveState.Phase.SERIALIZING, state.phase(),
                "前置: chunk 卡在 SERIALIZING (capture 抛异常现场)");

        // fake setter: 既记录调用参数, 又复刻 ChunkAccessMixin.onSetUnsaved 的真实副作用
        // (setUnsaved(true) -> state.markDirty()), 完整模拟 vanilla isUnsaved 门 + 状态机联动.
        AtomicReference<Boolean> lastSetUnsavedArg = new AtomicReference<>(null);
        AtomicBoolean unsavedFlag = new AtomicBoolean(false);
        ChunkRecoveryQueue.UnsavedSetter levelChunk = unsaved -> {
            lastSetUnsavedArg.set(unsaved);
            unsavedFlag.set(unsaved);
            if (unsaved) {
                state.markDirty();
            }
        };

        // 这正是 ChunkMapSaveMixin catch 现在调的同一个调用.
        SaveDispatcher.recoverAfterDispatchFailure(state, levelChunk);

        // 断言一: vanilla isUnsaved 门被还原 — 续跑的 ChunkMap.save 方法体才能过门同步写盘.
        assertNotNull(lastSetUnsavedArg.get(), "catch 必须调 setUnsaved");
        assertTrue(lastSetUnsavedArg.get(), "catch 必须还原 isUnsaved=true (否则 vanilla 续跑跳过本次同步写)");
        assertTrue(unsavedFlag.get(), "isUnsaved 终值必须为 true");

        // 断言二: phase 归零并可被任一重入门重新接管 (resetAfterFallback + markDirty 联动).
        assertEquals(ChunkSaveState.Phase.DIRTY, state.phase(),
                "catch 后 phase 必须回到 DIRTY");
        assertTrue(state.trySnapshot(),
                "catch 后 chunk 必须可被重新调度, 证明状态机彻底还原");
    }
}
