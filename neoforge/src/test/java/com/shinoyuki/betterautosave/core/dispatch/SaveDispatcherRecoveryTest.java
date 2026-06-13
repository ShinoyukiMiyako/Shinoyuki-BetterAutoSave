package com.shinoyuki.betterautosave.core.dispatch;

import com.shinoyuki.betterautosave.core.snapshot.ChunkRecoveryQueue;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SaveDispatcher.onPriorityDrained 的 catch 复位逻辑单测.
 *
 * <p>现场: captureAndDispatchChunk 先 setUnsaved(false) 再 enterSerializing 把 phase
 * 推到 SERIALIZING, capture 抛异常冒泡到 onPriorityDrained 的 catch. 若 catch 只
 * recordChunkFailed + log, chunk 会停在 unsaved=false + phase=SERIALIZING:
 * vanilla isUnsaved 重入门跳过 + BAS 非 DIRTY/FAILED phase 重入门跳过, 永久丢失.
 *
 * <p>本测试不构造真实 LevelChunk (MC 类单测无法实例化), 而是把 LevelChunk.setUnsaved
 * 抽象成 {@link SaveDispatcher.UnsavedSetter} 注入, 并在 fake setter 里复刻
 * ChunkAccessMixin.onSetUnsaved 的真实副作用 (setUnsaved(true) -> state.markDirty()),
 * 从而完整模拟生产环境的状态机交互.
 *
 * <p>判定标准: 注释掉 recoverAfterDispatchFailure 里的 state.resetAfterFallback()
 * 第二个断言 (trySnapshot 成功) 挂; 注释掉 chunk.setUnsaved(true) 第一个断言
 * (setUnsaved 被调用为 true) 挂.
 */
class SaveDispatcherRecoveryTest {

    @Test
    void recovery_after_dispatch_failure_restores_unsaved_and_redispatchable_phase() {
        // 模拟 capture 进行到一半抛异常时的状态: 已 setUnsaved(false) + enterSerializing.
        ChunkSaveState state = new ChunkSaveState(0L, "overworld", 1L);
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        assertEquals(ChunkSaveState.Phase.SERIALIZING, state.phase(),
                "前置: chunk 卡在 SERIALIZING (capture 抛异常现场)");

        AtomicReference<Boolean> setUnsavedArg = new AtomicReference<>(null);
        // fake setter 复刻 ChunkAccessMixin.betterautosave$onSetUnsaved: unsaved=true 时调 markDirty.
        ChunkRecoveryQueue.UnsavedSetter chunk = unsaved -> {
            setUnsavedArg.set(unsaved);
            if (unsaved) {
                state.markDirty();
            }
        };

        SaveDispatcher.recoverAfterDispatchFailure(state, chunk);

        // 断言一: vanilla isUnsaved 重入门被还原 (setUnsaved 以 true 调用).
        assertNotNull(setUnsavedArg.get(), "recover 必须调用 setUnsaved");
        assertTrue(setUnsavedArg.get(), "recover 必须 setUnsaved(true) 还原 vanilla 重入门");

        // 断言二: 状态机已可被下一轮重新调度. resetAfterFallback 把 phase 归零到 CLEAN,
        // setUnsaved 触发的 markDirty CAS(CLEAN->DIRTY) 成功推到 DIRTY, trySnapshot 必成功.
        // 若漏掉 resetAfterFallback, phase 卡在 SERIALIZING, markDirty 的 CAS 失败,
        // phase 不进 DIRTY, trySnapshot 返 false.
        assertEquals(ChunkSaveState.Phase.DIRTY, state.phase(),
                "recover 后 phase 必须回到 DIRTY (可再调度)");
        assertTrue(state.trySnapshot(),
                "recover 后 trySnapshot 必须成功, 证明 chunk 重新进入可调度状态");
    }

    @Test
    void recovery_clears_retry_count_so_fresh_attempt_gets_full_budget() {
        // capture 抛异常属于 dispatch 期失败而非 IO 失败, 不应消耗 IO 重试预算.
        // resetAfterFallback 清 retryCount, 让重新接管后走完整 maxRetries.
        ChunkSaveState state = new ChunkSaveState(0L, "overworld", 1L);
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();
        // 制造一次非终态 IO 失败累积 retryCount.
        ChunkSaveState.IoOutcome outcome = state.ioFailed(3);
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, outcome);
        assertEquals(1, state.retryCount());
        // 重新被接管又抛 dispatch 异常.
        state.trySnapshot();
        state.enterSerializing();

        SaveDispatcher.recoverAfterDispatchFailure(state, unsaved -> {
            if (unsaved) {
                state.markDirty();
            }
        });

        assertEquals(0, state.retryCount(),
                "dispatch 期复位必须清 retryCount, 不让 capture 异常吃掉 IO 重试预算");
    }
}
