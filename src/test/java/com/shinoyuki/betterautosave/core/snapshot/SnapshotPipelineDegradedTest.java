package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.api.PipelineStateListener;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
}
