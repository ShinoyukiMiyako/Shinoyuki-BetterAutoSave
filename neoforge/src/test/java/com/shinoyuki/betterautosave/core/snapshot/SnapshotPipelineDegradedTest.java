package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.api.PipelineStateListener;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.core.io.AtomicNbtWriter;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
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
     * 恢复队列 drain 必须与 degraded 闸门解耦。降级后存活 worker 与 IOWorker 回调仍在 enqueueRecovery,
     * 若 drain 跟随 degraded 停摆则失败 chunk 的 isUnsaved 永不还原, 降级会话期间不落盘, 进程被 kill 即静默
     * 丢失。本测试断言: 即便 pipeline 已 degraded, 其 ChunkRecoveryQueue 仍能 drain 并还原 isUnsaved
     * (恢复队列对象不持有 pipeline 引用, 零 degraded 耦合)。
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

    /**
     * 全 chunk worker 死亡触发 degraded 后, 在队 task 已 incInFlightSerializing 但永不 execute ->
     * inFlightSerializing 永久 >0, drainPending 轮询条件永假, 若不短路就会空耗满 shutdownTimeoutSeconds
     * (默认 60s) 才返 false, 平白拖慢关服。degraded 下必须提前明示返回 false。
     */
    @Test
    void degraded_drain_pending_returns_immediately_without_spinning_timeout() {
        SaveMetrics metrics = new SaveMetrics();
        SnapshotPipeline pipeline = new SnapshotPipeline(null, null, metrics);
        // 模拟全 worker 死亡: 在队 task 已 inc serializing 但永不 execute (无人 dec), gauge 永久 >0.
        metrics.incInFlightSerializing();
        pipeline.triggerDegraded();

        long timeoutMs = 5_000L;
        long t0 = System.currentTimeMillis();
        boolean drained = pipeline.drainPending(timeoutMs);
        long elapsed = System.currentTimeMillis() - t0;

        assertFalse(drained, "degraded + 卡住的 inFlightSerializing 下 drainPending 必返 false (未真正 drain)");
        assertTrue(elapsed < 1_000L,
                "degraded 下必须提前返回, 不空耗满 timeout, 实际耗时 " + elapsed + "ms (timeout " + timeoutMs + "ms)");
    }

    /**
     * M2 (issue #6/#8 审查): worker 全灭触发 degraded 时, 队列里已 capture 未 execute 的 task 必须被善后,
     * 否则数据被 vanilla 关服 flush 当"已存"跳过而静默丢失。SavedData 通道可在 bare JUnit 端到端构造 (无状态机/
     * 无 MC 运行期依赖), 验证 triggerDegraded -> drain -> abandon 的完整链: 重新 setDirty 让 vanilla 兜底、释放
     * 在途占位、配平 serializing gauge。删 triggerDegraded 的 drain 善后 -> savedData 仍 dirty=false -> 断言挂。
     */
    @Test
    void degraded_drains_stranded_savedData_task_remarking_dirty_for_vanilla_flush() {
        SaveMetrics metrics = new SaveMetrics();
        SnapshotPipeline pipeline = new SnapshotPipeline(null, null, metrics);

        // 模拟 mixin dispatch: 乐观清 dirty + inc serializing + add 在途占位 + 入队, 但 worker 全灭永不 execute.
        SavedData savedData = new SavedData() {
            @Override
            public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
                return tag;
            }
        };
        savedData.setDirty(false);
        String name = "test_stranded_saveddata";
        pipeline.savedDataInFlight().add(name);
        SavedDataSnapshot snapshot = new SavedDataSnapshot(name, new File(name + ".dat"),
                AtomicNbtWriter.serializeUncompressed(new CompoundTag()),
                savedData, null, name, pipeline.savedDataInFlight());
        metrics.incInFlightSerializing();
        pipeline.savedDataWorkerQueue().offer(new SavedDataSaveTask(snapshot, metrics));

        // 全 worker 死亡触发 degraded -> 逐出残留 task 善后.
        pipeline.triggerDegraded();

        assertTrue(savedData.isDirty(),
                "降级善后必须把滞留 SavedData 重新 setDirty, 否则 vanilla 关服 flush 按 dirty=false 跳过 -> 丢数据");
        assertFalse(pipeline.savedDataInFlight().contains(name),
                "降级善后必须释放在途文件名占位, 否则该 .dat 进程内永不再保存");
        assertEquals(0L, metrics.snapshot().inFlightSerializing(),
                "降级善后必须配平 serializing gauge (dispatch 的 inc 由 abandon dec)");
        assertEquals(0, pipeline.savedDataWorkerQueue().size(), "残留 task 必须被逐出队列");
    }

    /**
     * degraded 翻转残窗: 某 dispatch 已过 degraded 闸门, 却在 drainStrandedOnDegrade 跑完之后才 offer, 该 task
     * 会滞留无存活 worker 的队列永不 execute (乐观清掉的 dirty 永不还原 -> vanilla flush 跳过 -> 静默丢增量)。
     * reclaimIfDegradedAfterOffer 是 offer 后的补捞: 已降级则抢下 task 自行 abandon。这里先 triggerDegraded (drain
     * 跑完队列已空), 再模拟迟到 offer, 断言 reclaim 抢下并把 SavedData 重新 setDirty + 释放占位 + 配平 gauge。
     * 删 reclaim 的 abandon 分支 (退化为 return false) -> savedData 仍 dirty=false -> 断言挂。
     */
    @Test
    void reclaim_after_offer_abandons_task_stranded_past_degraded_gate() {
        SaveMetrics metrics = new SaveMetrics();
        SnapshotPipeline pipeline = new SnapshotPipeline(null, null, metrics);
        pipeline.triggerDegraded();
        assertTrue(pipeline.isDegraded(), "前置: pipeline 已降级 (drainStranded 已跑完, 队列空)");

        SavedData savedData = new SavedData() {
            @Override
            public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
                return tag;
            }
        };
        savedData.setDirty(false);
        String name = "test_reclaim_stranded";
        pipeline.savedDataInFlight().add(name);
        SavedDataSnapshot snapshot = new SavedDataSnapshot(name, new File(name + ".dat"),
                AtomicNbtWriter.serializeUncompressed(new CompoundTag()),
                savedData, null, name, pipeline.savedDataInFlight());
        SavedDataSaveTask task = new SavedDataSaveTask(snapshot, metrics);
        // 模拟 "已过闸门但 drain 之后才 offer": 主线程 inc serializing + offer 到已无 worker 的队列.
        metrics.incInFlightSerializing();
        pipeline.savedDataWorkerQueue().offer(task);

        boolean reclaimed = pipeline.reclaimIfDegradedAfterOffer(task, pipeline.savedDataWorkerQueue());

        assertTrue(reclaimed, "已降级 + task 仍在队 -> reclaim 必抢下并 abandon");
        assertTrue(savedData.isDirty(),
                "reclaim abandon 必须重新 setDirty 走 vanilla 兜底, 否则本周期增量静默丢失");
        assertFalse(pipeline.savedDataInFlight().contains(name), "reclaim abandon 必须释放在途占位");
        assertEquals(0L, metrics.snapshot().inFlightSerializing(),
                "reclaim abandon 必须配平 serializing gauge (dispatch 的 inc 由 abandon dec)");
        assertEquals(0, pipeline.savedDataWorkerQueue().size(), "reclaim 后 task 已移出队列");
    }

    /**
     * reclaim 的负向不变式: 未降级时绝不得触碰刚 offer 的 task (它归存活 worker 正常消费)。删 degraded.get() 门控
     * 使 reclaim 无条件 abandon -> 稳态每次 dispatch 都被误 abandon 走同步兜底, 此断言 (dirty 仍 false / task 仍在队) 挂。
     */
    @Test
    void reclaim_after_offer_is_noop_when_not_degraded() {
        SaveMetrics metrics = new SaveMetrics();
        SnapshotPipeline pipeline = new SnapshotPipeline(null, null, metrics);
        assertFalse(pipeline.isDegraded(), "前置: pipeline 未降级");

        SavedData savedData = new SavedData() {
            @Override
            public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
                return tag;
            }
        };
        savedData.setDirty(false);
        String name = "test_reclaim_noop";
        pipeline.savedDataInFlight().add(name);
        SavedDataSnapshot snapshot = new SavedDataSnapshot(name, new File(name + ".dat"),
                AtomicNbtWriter.serializeUncompressed(new CompoundTag()),
                savedData, null, name, pipeline.savedDataInFlight());
        SavedDataSaveTask task = new SavedDataSaveTask(snapshot, metrics);
        metrics.incInFlightSerializing();
        pipeline.savedDataWorkerQueue().offer(task);

        boolean reclaimed = pipeline.reclaimIfDegradedAfterOffer(task, pipeline.savedDataWorkerQueue());

        assertFalse(reclaimed, "未降级 -> reclaim 不得触碰 task");
        assertFalse(savedData.isDirty(), "未降级 -> 不得 abandon (dirty 仍 false, 交 worker 落盘)");
        assertEquals(1, pipeline.savedDataWorkerQueue().size(), "未降级 -> task 仍在队列");
        assertEquals(1L, metrics.snapshot().inFlightSerializing(),
                "未降级 -> serializing gauge 保持 (worker execute 才 dec)");
    }

    /**
     * JVM 关闭兜底 hook 的接管语义: worker 改 daemon 后, JVM 退出不再被 worker 钉死, 由本兜底 hook 在 halt
     * 前补 drain+join 保证落盘。但正常关服 (onServerStopping) 已自己 drain, 故一旦 detachShutdownHook
     * 接管, 兜底 hook 必须变 no-op, 不重复 drain。删掉 managedShutdownDone 守卫 -> 第二次断言挂。
     */
    @Test
    void jvm_shutdown_drain_runs_until_normal_shutdown_takes_over() {
        SnapshotPipeline pipeline = newPipeline();
        // 未接管 (start() 未调, worker 列表空): 兜底 drain 必须真正执行并立即收敛。
        assertTrue(pipeline.drainOnJvmShutdown(), "onServerStopping 未接管时, JVM 兜底 drain 必须执行");
        // 正常关服接管后: 兜底 hook 必须跳过, 不重复 drain。
        pipeline.detachShutdownHook();
        assertFalse(pipeline.drainOnJvmShutdown(), "detach (正常关服接管) 后, JVM 兜底 drain 必须跳过");
    }
}
