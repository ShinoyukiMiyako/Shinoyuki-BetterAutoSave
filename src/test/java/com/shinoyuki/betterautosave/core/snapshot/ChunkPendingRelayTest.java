package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * chunk 在途碰撞 + 卸载的接力快照重投端到端回归 (C-chunk-unload-collision).
 *
 * <p>现场: 某代 IO 在飞时该 chunk 又被编辑 (generation 前进) 又触发卸载 -> mixin 碰撞分支对最新内存做
 * 纯 capture 登记接力槽; 在飞那代落地判 REQUEUE_DIRTY 时回调取出接力快照重投, 把最新代落盘。旧逻辑直接
 * 信任在飞旧代快照, 卸载后编辑增量永久静默丢失。
 *
 * <p>测试技法: 注入 fake IoSubmitter (返回可控 future) + fake PendingReoffer (用同一 submitter 把 pending
 * 包成新 ChunkSaveTask 并立即 execute, 模拟序列化 worker 接力消费, 但不在真实 IOWorker 线程跑)。手动完成
 * future 精确控制"在飞落地"时机, 在落地前插入碰撞编辑, 复刻并发交错的确定性序列。
 *
 * <p>判定标准 (删修复必挂):
 * - 删 submitIo REQUEUE_DIRTY 分支的 takePendingSnapshot + reoffer -> 最终落盘 tag 恒为首代旧 tag, 接力断言挂;
 * - 删 reenterSerializingForPending 的代锁 -> 三代链最终判定错乱;
 * - 删 mixin 碰撞分支的 registerPendingSnapshot -> 槽空, 无接力, 旧代落盘。
 */
class ChunkPendingRelayTest {

    private static ResourceKey<Level> DIM;
    private int savedMaxRetries;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DIM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation("minecraft", "overworld"));
    }

    @BeforeEach
    void setup() throws Exception {
        ServerThreadAssert.markCurrentThreadAsWorker();
        savedMaxRetries = setMaxRetries(3);
    }

    @AfterEach
    void teardown() throws Exception {
        setMaxRetries(savedMaxRetries);
        ServerThreadAssert.unmarkCurrentThreadAsWorker();
    }

    /** 每代一个独立的 tag 实例, 以 "gen" 字段标记代号, 用于断言最终落盘的是哪一代。 */
    private ChunkSnapshot snapshotForGeneration(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    /**
     * GPT 点名回归: 同坐标第一次 future 未完成时, 第二次 (卸载) save 撞在途登记接力, 第一次 future 完成后
     * 接力把第二次的最新代落盘 —— 而不是把第二次增量丢掉。
     */
    @Test
    void second_unload_save_while_first_future_incomplete_relays_latest_generation() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        // 第一代 (gen=1) dispatch 现场.
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        // 第一次 submit 返回手动控制的未完成 future; 后续接力 submit 返回已完成 future.
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        CompletableFuture<Void> firstFuture = new CompletableFuture<>();
        futures.add(firstFuture);
        List<CompoundTag> submittedTags = new ArrayList<>();

        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };

        // fake reoffer: 用同一 submitter 把 pending 包成新 task 并立即 execute (模拟 worker 接力消费).
        ChunkSaveTask.PendingReoffer[] reofferHolder = new ChunkSaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            // 复刻生产 reoffer sink: 真正接力前 inc serializing (与 relay execute 首行 dec 配平).
            metrics.incInFlightSerializing();
            ChunkSaveTask relay = new ChunkSaveTask(pending, metrics, null, recoveryQueue,
                    submitter, reofferHolder[0]);
            relay.execute();
        };

        ChunkSaveTask task = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        task.execute();

        // 此刻 gen=1 的 IO 在飞 (firstFuture 未完成). 模拟 mixin 碰撞分支: 编辑 (gen=2) + 登记接力快照.
        state.markDirty();          // gen=2 (碰撞编辑)
        assertTrue(state.tryMarkMustDrain());
        ChunkSnapshot gen2Pending = snapshotForGeneration(state, 2L);
        state.registerPendingSnapshot(gen2Pending);

        // 第一次 IO 现在完成 -> whenComplete 触发 -> REQUEUE_DIRTY -> 取 pending 接力 gen=2.
        firstFuture.complete(null);

        // 接力必须把 gen=2 (最新代) 落盘, 不是 gen=1 旧代.
        CompoundTag lastSubmitted = submittedTags.get(submittedTags.size() - 1);
        assertEquals(2L, lastSubmitted.getLong("gen"),
                "接力必须把碰撞后最新代 (gen=2) 落盘, 而非信任在飞旧代 (gen=1)");
        assertSame(gen2Pending.preBuiltFullTag(), lastSubmitted,
                "接力提交的必须是 pending 快照的 tag 实例");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase(), "接力落地后 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "接力链落地后 mustDrain 清零");
        assertFalse(state.hasPendingSnapshot(), "pending 已被取走消费, 槽清空");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零 (含接力 task 的 inc/dec)");
    }

    /**
     * 隐角 A 三代链: capture G -> 编辑 G+1 碰撞登记 pending -> pending 在飞中再编辑 G+2 再碰撞 ->
     * 断言最终落盘为 G+2。
     */
    @Test
    void three_generation_collision_chain_lands_final_generation() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        // 代 G=1 dispatch.
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        // 控制 future: 第一代 gen-1 IO 用手动 future; 接力 gen-? IO 也用手动 future, 以便在 gen-2 接力在飞时
        // 再插入 gen-3 碰撞 (复刻 "pending 在飞中再编辑再碰撞").
        CompletableFuture<Void> gen1Future = new CompletableFuture<>();
        CompletableFuture<Void> relayFuture = new CompletableFuture<>();
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        futures.add(gen1Future);
        futures.add(relayFuture);
        List<CompoundTag> submittedTags = new ArrayList<>();

        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        ChunkSaveTask.PendingReoffer[] reofferHolder = new ChunkSaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            // 复刻生产 reoffer sink: 真正接力前 inc serializing (与 relay execute 首行 dec 配平).
            metrics.incInFlightSerializing();
            ChunkSaveTask relay = new ChunkSaveTask(pending, metrics, null, recoveryQueue,
                    submitter, reofferHolder[0]);
            relay.execute();
        };

        ChunkSaveTask task = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        task.execute();             // gen-1 IO 在飞 (gen1Future 未完成)

        // 碰撞 G+1: 编辑 gen=2 + 登记 pending(gen=2).
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        state.registerPendingSnapshot(snapshotForGeneration(state, 2L));

        // 碰撞 G+2: pending(gen=2) 还在槽里 (gen-1 IO 还没落地), 再编辑 gen=3 + 登记 pending(gen=3) 覆盖.
        state.markDirty();          // gen=3
        ChunkSnapshot gen3Pending = snapshotForGeneration(state, 3L);
        state.registerPendingSnapshot(gen3Pending);

        // gen-1 IO 落地 -> REQUEUE_DIRTY -> 取 pending(gen=3, 最新者胜) 接力, 锁 inFlightGeneration=3.
        gen1Future.complete(null);

        // 接力 gen-3 IO 在飞 (relayFuture 未完成). 期间无更新编辑, 完成它 -> CLEAN_LANDED.
        relayFuture.complete(null);

        CompoundTag lastSubmitted = submittedTags.get(submittedTags.size() - 1);
        assertEquals(3L, lastSubmitted.getLong("gen"),
                "三代链最终落盘必须是 G+2 (gen=3, 最新者胜), 不是 gen=1 旧代也不是被覆盖的 gen=2");
        assertSame(gen3Pending.preBuiltFullTag(), lastSubmitted, "最终落盘的是 gen=3 pending 的 tag 实例");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase(), "三代链终落地 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "三代链落地后 mustDrain 清零");
        assertFalse(state.hasPendingSnapshot(), "三代链落地后无残留 pending");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
    }

    /** pending 被接力消费后槽必须清空 (不残留导致下次误接力或泄漏)。 */
    @Test
    void pending_consumed_clears_slot() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        CompletableFuture<Void> gen1Future = new CompletableFuture<>();
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        futures.add(gen1Future);
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        ChunkSaveTask.PendingReoffer[] reofferHolder = new ChunkSaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            // 复刻生产 reoffer sink: 真正接力前 inc serializing (与 relay execute 首行 dec 配平).
            metrics.incInFlightSerializing();
            ChunkSaveTask relay = new ChunkSaveTask(pending, metrics, null, recoveryQueue,
                    submitter, reofferHolder[0]);
            relay.execute();
        };

        ChunkSaveTask task = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        task.execute();

        state.markDirty();
        state.tryMarkMustDrain();
        state.registerPendingSnapshot(snapshotForGeneration(state, 2L));
        assertTrue(state.hasPendingSnapshot(), "登记后槽非空");

        gen1Future.complete(null);

        assertFalse(state.hasPendingSnapshot(),
                "接力消费 pending 后槽必须清空 (takePendingSnapshot getAndSet null)");
    }

    /**
     * 多 worker 同坐标顺序: 即便有多条序列化 worker, 同坐标的 IO 提交也必须按代序严格递增 ——
     * 因为接力 task 只在前一代 IO 落地 (whenComplete) 内部才被创建并 offer, 形成 "前代落地 -> 后代提交"
     * 的天然 happens-before, 后代 tag 必然在前代 tag 之后进同一 pos 的 IOWorker mailbox (FIFO 覆盖),
     * 最终态正确。本测试记录每次 IO 提交的代号, 断言全程严格递增 (无乱序覆盖), 且最终落最新代。
     */
    @Test
    void same_coord_relay_submissions_are_strictly_generation_ordered() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        // 每代 IO 用手动 future, 模拟逐代落地. gen1 -> 接力 gen2 -> 接力 gen3.
        CompletableFuture<Void> f1 = new CompletableFuture<>();
        CompletableFuture<Void> f2 = new CompletableFuture<>();
        CompletableFuture<Void> f3 = new CompletableFuture<>();
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        futures.add(f1);
        futures.add(f2);
        futures.add(f3);
        List<Long> submittedGenerations = new ArrayList<>();
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submittedGenerations.add(tag.getLong("gen"));
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        ChunkSaveTask.PendingReoffer[] reofferHolder = new ChunkSaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            // 复刻生产 reoffer sink: 真正接力前 inc serializing (与 relay execute 首行 dec 配平).
            metrics.incInFlightSerializing();
            ChunkSaveTask relay = new ChunkSaveTask(pending, metrics, null, recoveryQueue,
                    submitter, reofferHolder[0]);
            relay.execute();
        };

        ChunkSaveTask task = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        task.execute();             // 提交 gen=1, f1 未完成

        // 碰撞 gen=2, 登记 pending.
        state.markDirty();          // gen=2
        state.tryMarkMustDrain();
        state.registerPendingSnapshot(snapshotForGeneration(state, 2L));
        f1.complete(null);          // gen=1 落地 -> 接力提交 gen=2 (f2 未完成)

        // gen=2 接力在飞期间再碰撞 gen=3, 登记 pending.
        state.markDirty();          // gen=3
        state.registerPendingSnapshot(snapshotForGeneration(state, 3L));
        f2.complete(null);          // gen=2 落地 -> 接力提交 gen=3 (f3 未完成)

        f3.complete(null);          // gen=3 落地 CLEAN_LANDED

        assertEquals(List.of(1L, 2L, 3L), submittedGenerations,
                "同坐标 IO 提交必须按代序严格递增 (1->2->3), 接力只在前代落地后才提交后代");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase());
        assertFalse(state.mustDrain());
        assertFalse(state.hasPendingSnapshot());
    }

    /**
     * M-unhandled-abandons-pending: onUnhandledError 撞上非空 pending 时必须接力重投最新代, 而非清 mustDrain
     * 丢弃 pending。复刻: gen=1 IO 在飞 -> 碰撞登记 pending(gen=2) -> 在飞 task 的 worker execute 抛非受控异常
     * (走 onUnhandledError) -> 接力把 gen=2 落盘。
     *
     * <p>判定标准 (删修复必挂): 删 onUnhandledError 的 takePendingSnapshot + reoffer 分支 -> pending 不被接力,
     * 最终未提交 gen=2, "接力落最新代"断言挂; 且 hasPendingSnapshot 残留 true (槽泄漏)。
     */
    @Test
    void unhandled_error_with_pending_relays_latest_generation() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();
        // mixin 碰撞分支语义: 在飞期间置 mustDrain.
        assertTrue(state.tryMarkMustDrain());

        // relay IO 用手动 future, 接力后保持在途 (不完成), 以便在 "relay 在途" 这个状态点断言不变式.
        List<CompoundTag> submittedTags = new ArrayList<>();
        CompletableFuture<Void> relayFuture = new CompletableFuture<>();
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            return relayFuture;
        };
        ChunkSaveTask.PendingReoffer[] reofferHolder = new ChunkSaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            metrics.incInFlightSerializing();
            ChunkSaveTask relay = new ChunkSaveTask(pending, metrics, null, recoveryQueue,
                    submitter, reofferHolder[0]);
            relay.execute();
        };

        // 在飞那代登记接力 pending(gen=2).
        state.markDirty();          // gen=2
        ChunkSnapshot gen2Pending = snapshotForGeneration(state, 2L);
        state.registerPendingSnapshot(gen2Pending);

        // 在飞 task 的 worker execute 抛非受控异常 -> 走 onUnhandledError. dec 掉它的 serializing inc
        // (复刻 execute 同步异常路径的 gauge 复位: assemble 抛会先 dec serializing).
        metrics.decInFlightSerializing();
        ChunkSaveTask inFlightTask = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        inFlightTask.onUnhandledError(new RuntimeException("assemble boom"));

        // 不变式断言点 (relay 在途, future 未完成): 这是上一轮不变式测试的盲区 —— 它只走 happy 状态机序列,
        // 从不经 onUnhandledError。旧码 onUnhandledError 无条件清 mustDrain 且不取 pending, 回退到旧码则
        // 此刻 mustDrain=false (下面第一断言挂) 且 pending 残留非空 (第二断言挂)。
        assertEquals(1, submittedTags.size(), "接力必须提交一次最新代 IO");
        assertEquals(2L, submittedTags.get(0).getLong("gen"),
                "onUnhandledError 必须接力把碰撞后最新代 (gen=2) 落盘, 而非丢弃 pending");
        assertSame(gen2Pending.preBuiltFullTag(), submittedTags.get(0),
                "接力提交的是 pending 快照的 tag 实例");
        assertTrue(state.mustDrain(),
                "onUnhandledError 接力 relay 仍在途时 mustDrain 必须维持真 (不变式: 在途 -> mustDrain)");
        assertFalse(state.hasPendingSnapshot(), "pending 已被接力 take 走, 槽清空 (不泄漏)");

        // 完成 relay IO -> CLEAN_LANDED 收口.
        relayFuture.complete(null);
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase(), "接力落地后 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "接力链落地后 mustDrain 归零");
        assertFalse(state.hasPendingSnapshot());

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
    }

    /**
     * M-unhandled-abandons-pending 安全网: onUnhandledError 有 pending 但 reoffer sink 不可达 (null) 时,
     * 必须取走 pending (不泄漏) + 清 mustDrain + 配平 gauge, 走 ERROR 安全网。
     *
     * <p>判定标准 (删修复必挂): 删 onUnhandledError 的 takePendingSnapshot -> 槽残留 true, hasPendingSnapshot
     * 断言挂; 删 mustDrain 清除路径 -> mustDrain 永真, gauge 泄漏。
     */
    @Test
    void unhandled_error_with_pending_but_no_sink_clears_slot_and_must_drain() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L);
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        state.markDirty();
        state.registerPendingSnapshot(snapshotForGeneration(state, 2L));

        // pendingReoffer = null (sink 不可达).
        ChunkSaveTask task = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, tag -> CompletableFuture.completedFuture(null));
        task.onUnhandledError(new RuntimeException("boom"));

        assertFalse(state.hasPendingSnapshot(), "sink 不可达也必须取走 pending 清空槽 (防永久泄漏)");
        assertFalse(state.mustDrain(), "无接力可投时必须清 mustDrain");
        assertEquals(0L, metrics.snapshot().mustDrainPending(), "mustDrain gauge 配平归零");
    }

    private static int setMaxRetries(int value) throws Exception {
        Field f = com.shinoyuki.betterautosave.config.BetterAutoSaveConfig.class.getDeclaredField("maxRetries");
        f.setAccessible(true);
        int prev = f.getInt(null);
        f.setInt(null, value);
        return prev;
    }
}
