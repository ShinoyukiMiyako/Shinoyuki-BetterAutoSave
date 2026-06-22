package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChunkRecoveryQueue 行为单测.
 *
 * <p>现场: ChunkSaveTask IO 失败回调 (worker 线程) 调 ioFailed 把 phase 置回 DIRTY/FAILED,
 * 但 vanilla isUnsaved 仍是 capture 时清的 false, 三条重入门全跳过 → 永久丢失. 本队列让
 * 失败回调投递, 主线程 drain 还原 isUnsaved.
 *
 * <p>判定标准:
 * - 删掉 ChunkSaveTask.enqueueRecovery 的 offer → "队列收到条目" 断言挂 (见 redispatchable 测试前置).
 * - 删掉 ChunkRecoveryQueue.drain 里的 chunk.setUnsaved(true) → "drain 还原 unsaved" 断言挂.
 */
class ChunkRecoveryQueueTest {

    private static final String DIM = "minecraft:overworld";
    private static final long PACKED = new ChunkPos(7, -3).toLong();

    @Test
    void offer_then_drain_restores_unsaved_and_makes_state_redispatchable() {
        ChunkRecoveryQueue queue = new ChunkRecoveryQueue();

        // 模拟一个 IO 失败后状态机已 ioFailed -> DIRTY 的 chunk.
        ChunkSaveState state = new ChunkSaveState(PACKED, DIM, 1L);
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();
        ChunkSaveState.IoOutcome outcome = state.ioFailed(3);
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, outcome);
        assertEquals(ChunkSaveState.Phase.DIRTY, state.phase());

        // worker 线程投递 (REQUEUE_DIRTY -> terminal=false).
        queue.offer(DIM, PACKED, false);
        assertEquals(1, queue.size(), "失败回调投递后队列必须有 1 条待恢复");

        // 主线程 drain: 模拟 unsaved 标志载体 + ChunkAccessMixin.onSetUnsaved 副作用 (markDirty).
        AtomicInteger setUnsavedTrueCount = new AtomicInteger();
        int recovered = queue.drain((dimensionId, packedPos) -> {
            assertEquals(DIM, dimensionId);
            assertEquals(PACKED, packedPos);
            return unsaved -> {
                if (unsaved) {
                    setUnsavedTrueCount.incrementAndGet();
                    state.markDirty();
                }
            };
        });

        assertEquals(1, recovered, "drain 必须恢复 1 个 chunk");
        assertEquals(0, queue.size(), "drain 后队列清空");
        assertEquals(1, setUnsavedTrueCount.get(),
                "drain 必须以 unsaved=true 调用一次 (还原 vanilla 重入门)");
        // 状态机仍 DIRTY, isUnsaved 已还原 → 下一轮重入门可重新接管, trySnapshot 必成功.
        assertEquals(ChunkSaveState.Phase.DIRTY, state.phase());
        assertTrue(state.trySnapshot(),
                "isUnsaved 还原后 chunk 必须可被重新调度 (trySnapshot 成功)");
    }

    @Test
    void drain_skips_unloaded_chunk_resolver_returns_null() {
        ChunkRecoveryQueue queue = new ChunkRecoveryQueue();
        queue.offer(DIM, PACKED, true);
        assertEquals(1, queue.size());

        // chunk 已 unload: resolver 返 null. drain 必须消费条目但不计入 recovered.
        int recovered = queue.drain((dimensionId, packedPos) -> null);

        assertEquals(0, recovered, "已 unload 的 chunk 不应计入恢复数");
        assertEquals(0, queue.size(), "无论是否恢复, 条目都必须从队列移除避免无限堆积");
    }

    @Test
    void terminal_entry_still_restores_unsaved_for_vanilla_fallback() {
        ChunkRecoveryQueue queue = new ChunkRecoveryQueue();

        // FAILED_TERMINAL 状态 (phase=FAILED): BAS 重入门让出给 vanilla, 但仍需还原 isUnsaved
        // 否则 vanilla 同步路径 (按 isUnsaved 过滤) 也救不回.
        ChunkSaveState state = new ChunkSaveState(PACKED, DIM, 1L);
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();
        ChunkSaveState.IoOutcome outcome = state.ioFailed(0);
        assertEquals(ChunkSaveState.IoOutcome.FAILED_TERMINAL, outcome);
        assertEquals(ChunkSaveState.Phase.FAILED, state.phase());

        queue.offer(DIM, PACKED, true);

        AtomicInteger setUnsavedTrueCount = new AtomicInteger();
        int recovered = queue.drain((dimensionId, packedPos) ->
                unsaved -> {
                    if (unsaved) {
                        setUnsavedTrueCount.incrementAndGet();
                    }
                });

        assertEquals(1, recovered);
        assertEquals(1, setUnsavedTrueCount.get(),
                "终态失败也必须还原 isUnsaved 让 vanilla 同步路径兜底");
    }

    @Test
    void empty_queue_drain_is_noop_and_resolver_not_called() {
        ChunkRecoveryQueue queue = new ChunkRecoveryQueue();
        AtomicInteger resolverCalls = new AtomicInteger();
        int recovered = queue.drain((dimensionId, packedPos) -> {
            resolverCalls.incrementAndGet();
            return null;
        });
        assertEquals(0, recovered);
        assertEquals(0, resolverCalls.get(), "空队列 drain 不应调 resolver");
    }

    @Test
    void multiple_offers_drain_all_in_fifo() {
        ChunkRecoveryQueue queue = new ChunkRecoveryQueue();
        long a = new ChunkPos(0, 0).toLong();
        long b = new ChunkPos(1, 0).toLong();
        long c = new ChunkPos(2, 0).toLong();
        queue.offer(DIM, a, false);
        queue.offer(DIM, b, false);
        queue.offer(DIM, c, true);
        assertEquals(3, queue.size());

        StringBuilder order = new StringBuilder();
        AtomicInteger recoveredCount = new AtomicInteger();
        int recovered = queue.drain((dimensionId, packedPos) -> {
            order.append(packedPos).append(',');
            return unsaved -> recoveredCount.incrementAndGet();
        });

        assertEquals(3, recovered);
        assertEquals(3, recoveredCount.get());
        assertEquals(a + "," + b + "," + c + ",", order.toString(),
                "drain 必须按 FIFO 顺序处理所有待恢复 chunk");
    }
}
