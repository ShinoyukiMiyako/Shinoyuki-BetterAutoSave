package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.api.PipelineStateListener;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SnapshotPipeline.triggerDegraded 的单向闩锁 + 首次翻转 fire 语义.
 *
 * <p>不调 {@code start()} (不启动 worker, 避免拉起 MC 依赖), 直接构造 pipeline 后
 * 调 triggerDegraded. scheduler / ioBridge 传 null: triggerDegraded 路径只动
 * degraded 闩锁与 fire, 不触碰这两个协作者 (SaveScheduler 在单测环境构造会因
 * 未初始化的 config 抛 IllegalArgumentException, 故不能 new 它).
 *
 * <p>判定标准: 删掉 triggerDegraded 里的 firePipelineDegraded 调用, 第一个断言挂;
 * 把 compareAndSet 换成无条件 set + fire, 第二个 "只 fire 一次" 断言挂.
 */
class SnapshotPipelineDegradedTest {

    private PipelineStateListener registered;

    @AfterEach
    void cleanup() {
        if (registered != null) {
            SaveListenerRegistry.unregisterPipelineState(registered);
            registered = null;
        }
    }

    private SnapshotPipeline newPipeline() {
        return new SnapshotPipeline(null, null, new SaveMetrics());
    }

    @Test
    void first_degraded_transition_fires_listener_and_flips_flag() {
        AtomicInteger fires = new AtomicInteger();
        PipelineStateListener l = fires::incrementAndGet;
        registered = l;
        SaveListenerRegistry.registerPipelineState(l);

        SnapshotPipeline pipeline = newPipeline();
        assertFalse(pipeline.isDegraded(), "初始未降级");

        pipeline.triggerDegraded();

        assertTrue(pipeline.isDegraded(), "triggerDegraded 后闩锁置位");
        assertEquals(1, fires.get(), "首次降级必须 fire 一次");
    }

    @Test
    void repeated_trigger_fires_only_once() {
        AtomicInteger fires = new AtomicInteger();
        PipelineStateListener l = fires::incrementAndGet;
        registered = l;
        SaveListenerRegistry.registerPipelineState(l);

        SnapshotPipeline pipeline = newPipeline();
        pipeline.triggerDegraded();
        pipeline.triggerDegraded();
        pipeline.triggerDegraded();

        assertEquals(1, fires.get(), "单向闩锁: 多次 triggerDegraded 只 fire 一次");
    }

    /**
     * Major 修复 M2: 恢复队列 drain 必须与 degraded 闸门解耦。降级后存活 worker 与 IOWorker 回调
     * 仍在 enqueueRecovery, 若 drain 跟随 degraded 停摆则失败 chunk 的 isUnsaved 永不还原, 降级会话
     * 期间不落盘, 进程被 kill 即静默丢失。本测试断言: 即便 pipeline 已 degraded, 其 ChunkRecoveryQueue
     * 仍能 drain 并还原 isUnsaved (恢复队列对象不持有 pipeline 引用, 零 degraded 耦合)。
     *
     * <p>判定标准: 把恢复 drain 退回 degraded 闸门之后 (即降级时跳过 drain), 降级期投递的恢复条目
     * 不会被处理, 此处断言 recovered==2 与 resolver 被调用挂。
     */
    @Test
    void recovery_queue_drains_while_degraded() {
        SnapshotPipeline pipeline = newPipeline();
        pipeline.triggerDegraded();
        assertTrue(pipeline.isDegraded(), "前置: pipeline 已降级");

        // 降级会话期间存活 worker / IOWorker 回调投递 IO 失败恢复条目.
        pipeline.chunkRecoveryQueue().offer("minecraft:overworld", 100L, true);
        pipeline.chunkRecoveryQueue().offer("minecraft:the_nether", 200L, false);

        AtomicInteger resolved = new AtomicInteger();
        AtomicBoolean restoredUnsaved = new AtomicBoolean(false);
        // resolver 复刻主线程 drainChunkRecoveryQueue 行为: 找到已加载 chunk 还原 isUnsaved.
        int recovered = pipeline.chunkRecoveryQueue().drain((dim, packed) -> {
            resolved.incrementAndGet();
            return unsaved -> {
                if (unsaved) {
                    restoredUnsaved.set(true);
                }
            };
        });

        assertEquals(2, recovered,
                "降级态下恢复队列仍须 drain 并还原全部失败 chunk 的 isUnsaved");
        assertEquals(2, resolved.get(), "两条恢复条目都必须被 resolver 处理");
        assertTrue(restoredUnsaved.get(), "drain 必须以 setUnsaved(true) 还原 vanilla 重入门");
        assertEquals(0, pipeline.chunkRecoveryQueue().size(), "drain 后队列清空");
    }
}
