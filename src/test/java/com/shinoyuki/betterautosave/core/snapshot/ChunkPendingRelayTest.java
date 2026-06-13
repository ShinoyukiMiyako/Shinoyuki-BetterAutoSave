package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.core.worker.WorkerThreadAssert;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * chunk 在途碰撞 + 卸载的接力快照重投端到端回归.
 *
 * <p>现场: 某代 IO 在飞时该 chunk 又被编辑 (generation 前进) 又触发卸载 -> mixin 碰撞分支对最新内存做
 * 纯 capture 登记接力槽; 在飞那代落地判 REQUEUE_DIRTY 时回调取出接力快照重投, 把最新代落盘。若不接力而直接
 * 信任在飞旧代快照, 卸载后编辑增量将永久静默丢失。
 *
 * <p>测试技法: 注入 fake IoSubmitter (返回可控 future) + fake PendingReoffer (用同一 submitter 把 pending
 * 包成新 ChunkSaveTask 并立即 execute, 模拟序列化 worker 接力消费, 但不在真实 IOWorker 线程跑)。手动完成
 * future 精确控制"在飞落地"时机, 在落地前插入碰撞编辑, 复刻并发交错的确定性序列。
 *
 * <p>判定标准:
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
        WorkerThreadAssert.markCurrentThreadAsWorker();
        savedMaxRetries = setMaxRetries(3);
    }

    @AfterEach
    void teardown() throws Exception {
        setMaxRetries(savedMaxRetries);
        WorkerThreadAssert.unmarkCurrentThreadAsWorker();
    }

    /** 每代一个独立的 tag 实例, 以 "gen" 字段标记代号, 用于断言最终落盘的是哪一代。 */
    private ChunkSnapshot snapshotForGeneration(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    /**
     * 同坐标第一次 future 未完成时, 第二次 (卸载) save 撞在途登记接力, 第一次 future 完成后
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
        state.registerReadyPendingSnapshot(gen2Pending);

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
     * 三代链: capture G -> 编辑 G+1 碰撞登记 pending -> pending 在飞中再编辑 G+2 再碰撞 ->
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
        state.registerReadyPendingSnapshot(snapshotForGeneration(state, 2L));

        // 碰撞 G+2: pending(gen=2) 还在槽里 (gen-1 IO 还没落地), 再编辑 gen=3 + 登记 pending(gen=3) 覆盖.
        state.markDirty();          // gen=3
        ChunkSnapshot gen3Pending = snapshotForGeneration(state, 3L);
        state.registerReadyPendingSnapshot(gen3Pending);

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
        state.registerReadyPendingSnapshot(snapshotForGeneration(state, 2L));
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
        state.registerReadyPendingSnapshot(snapshotForGeneration(state, 2L));
        f1.complete(null);          // gen=1 落地 -> 接力提交 gen=2 (f2 未完成)

        // gen=2 接力在飞期间再碰撞 gen=3, 登记 pending.
        state.markDirty();          // gen=3
        state.registerReadyPendingSnapshot(snapshotForGeneration(state, 3L));
        f2.complete(null);          // gen=2 落地 -> 接力提交 gen=3 (f3 未完成)

        f3.complete(null);          // gen=3 落地 CLEAN_LANDED

        assertEquals(List.of(1L, 2L, 3L), submittedGenerations,
                "同坐标 IO 提交必须按代序严格递增 (1->2->3), 接力只在前代落地后才提交后代");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase());
        assertFalse(state.mustDrain());
        assertFalse(state.hasPendingSnapshot());
    }

    /**
     * onUnhandledError 撞上非空 pending 时必须接力重投最新代, 而非清 mustDrain
     * 丢弃 pending。复刻: gen=1 IO 在飞 -> 碰撞登记 pending(gen=2) -> 在飞 task 的 worker execute 抛非受控异常
     * (走 onUnhandledError) -> 接力把 gen=2 落盘。
     *
     * <p>判定标准: 删 onUnhandledError 的 takePendingSnapshot + reoffer 分支 -> pending 不被接力,
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
        state.registerReadyPendingSnapshot(gen2Pending);

        // 在飞 task 的 worker execute 抛非受控异常 -> 走 onUnhandledError. dec 掉它的 serializing inc
        // (复刻 execute 同步异常路径的 gauge 复位: assemble 抛会先 dec serializing).
        metrics.decInFlightSerializing();
        ChunkSaveTask inFlightTask = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        inFlightTask.onUnhandledError(new RuntimeException("assemble boom"));

        // 不变式断言点 (relay 在途, future 未完成): onUnhandledError 接力取走 pending 并保持 mustDrain,
        // 故此刻 mustDrain 仍为 true 且 pending 已被取走 (下面两断言)。
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
     * onUnhandledError 安全网: onUnhandledError 有 pending 但 reoffer sink 不可达 (null) 时,
     * 必须取走 pending (不泄漏) + 清 mustDrain + 配平 gauge, 走 ERROR 安全网。
     *
     * <p>判定标准: 删 onUnhandledError 的 takePendingSnapshot -> 槽残留 true, hasPendingSnapshot
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
        state.registerReadyPendingSnapshot(snapshotForGeneration(state, 2L));

        // pendingReoffer = null (sink 不可达).
        ChunkSaveTask task = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, tag -> CompletableFuture.completedFuture(null));
        task.onUnhandledError(new RuntimeException("boom"));

        assertFalse(state.hasPendingSnapshot(), "sink 不可达也必须取走 pending 清空槽 (防永久泄漏)");
        assertFalse(state.mustDrain(), "无接力可投时必须清 mustDrain");
        assertEquals(0L, metrics.snapshot().mustDrainPending(), "mustDrain gauge 配平归零");
    }

    /**
     * 反序交错 1: 在飞 IO 落地于 dispatch 窗口内 (PREPARING 态) ->
     * 回调发现槽但**不可消费**, 标 consumerMissed 返 null 离开 (关"未就绪 tag 暴露"门); dispatch 成功 ->
     * 主线程 publishPendingSnapshot 检测到 missed -> 取回就绪 pending 自踢落盘 (补踢)。
     *
     * <p>协议把"已登记 (PREPARING 可发现)"与"可消费 (READY)"解耦: 回调路过 PREPARING 不取 tag (此刻 listener
     * 仍在改写 gen=2 tag), 补踢交还主线程, 避免取走未就绪 tag。
     *
     * <p>判定标准: 删 takeReadyPendingSnapshot 的 PREPARING-标-missed 分支 (退回直接消费) ->
     * 回调取走未就绪 tag (违反协议); 删 publishPendingSnapshot 的 missed-自踢分支 -> gen=2 永不落盘 (槽残 READY 孤儿,
     * mustDrain 永挂)。
     */
    @Test
    void callback_misses_preparing_then_main_thread_self_reoffers_on_publish() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        CompletableFuture<Void> gen1Future = new CompletableFuture<>();
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        futures.add(gen1Future);
        List<CompoundTag> submittedTags = new ArrayList<>();
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        ChunkSaveTask.PendingReoffer[] reofferHolder = new ChunkSaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            metrics.incInFlightSerializing();
            ChunkSaveTask relay = new ChunkSaveTask(pending, metrics, null, recoveryQueue,
                    submitter, reofferHolder[0]);
            relay.execute();
        };

        ChunkSaveTask task = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        task.execute();             // gen=1 IO 在飞

        // mixin 碰撞分支序列: markDirty -> tryMark -> beginPending(gen=2, 挂 PREPARING) -> [dispatch 窗口].
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        ChunkSnapshot gen2Pending = snapshotForGeneration(state, 2L);
        state.beginPendingSnapshot(gen2Pending);

        // 反序交错: dispatch 仍在跑 (PREPARING) 时在飞 IO 先落地 -> REQUEUE_DIRTY -> takeReadyPendingSnapshot
        // 见 PREPARING 标 missed 返 null, **不**取走未就绪 tag. gen=2 此刻绝不能被提交.
        gen1Future.complete(null);
        assertEquals(1, submittedTags.size(),
                "回调路过 PREPARING 不得消费未就绪 tag (此刻只提交过在飞 gen=1)");
        assertEquals(1L, submittedTags.get(0).getLong("gen"), "在飞落地的是 gen=1");
        assertTrue(state.hasPendingSnapshot(), "PREPARING 仍挂着 gen=2, 槽未空 (回调未消费)");

        // dispatch 成功返回 -> publishPendingSnapshot 见 missed -> 取回 gen=2 让主线程自踢.
        ChunkSnapshot toReoffer = (ChunkSnapshot) state.publishPendingSnapshot();
        assertSame(gen2Pending, toReoffer, "回调已 missed, publish 必须取回 pending 交主线程自踢");
        assertFalse(state.hasPendingSnapshot(), "publish 取走 pending 后槽归 EMPTY");

        // 主线程自踢: reenter + reoffer (复刻 SnapshotPipeline.reofferChunkPendingFromMainThread).
        state.reenterSerializingForPending(toReoffer.capturedGeneration());
        reofferHolder[0].reoffer(toReoffer);

        assertEquals(2L, submittedTags.get(submittedTags.size() - 1).getLong("gen"),
                "主线程自踢必须把 gen=2 落盘");
        assertSame(gen2Pending.preBuiltFullTag(), submittedTags.get(submittedTags.size() - 1),
                "自踢提交的是 gen=2 pending 的 tag 实例");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase(), "自踢接力落地 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "接力链落地后 mustDrain 清零");
        assertFalse(state.hasPendingSnapshot(), "全链落地无残留 pending");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
    }

    /**
     * 反序交错 2: dispatch 抛 (第三方 listener 故障) ->
     * abortPendingSnapshot 把 PREPARING 撤销归 EMPTY 取回非 null (恒成功, 因回调在 PREPARING 期间从不消费),
     * 主线程据此清 mustDrain + 配平 gauge。撤销后在飞旧代落地判 REQUEUE_DIRTY (generation 已前进永不相等),
     * takeReadyPendingSnapshot 见 EMPTY 取 null 不重投, 此后无路径清 mustDrain —— 故撤销时亲自清才不泄漏。
     *
     * <p>本用例还覆盖: 在飞回调在 abort *之前* 已路过 PREPARING (标 missed)。abort 取回的 PREPARING 即便带
     * missed 也是 EMPTY 终态 (撤销), 主线程不再 publish 自踢 (dispatch 已抛), missed 标志随撤销作废 —— 验证
     * abort 路径不会被残留 missed 误导去重复接力。
     *
     * <p>判定标准: 删主线程 abort 后的 compareAndClearMustDrain -> 在飞旧代落地后 mustDrain 永真,
     * gauge 永久正偏移 (本断言 mustDrainPending()==0 挂); 删 abortPendingSnapshot 的 PREPARING->EMPTY 撤销 ->
     * 槽残留 PREPARING, hasPendingSnapshot 断言挂。
     */
    @Test
    void dispatch_throw_aborts_preparing_and_balances_must_drain() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        CompletableFuture<Void> gen1Future = new CompletableFuture<>();
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        futures.add(gen1Future);
        List<CompoundTag> submittedTags = new ArrayList<>();
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        ChunkSaveTask.PendingReoffer[] reofferHolder = new ChunkSaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            metrics.incInFlightSerializing();
            ChunkSaveTask relay = new ChunkSaveTask(pending, metrics, null, recoveryQueue,
                    submitter, reofferHolder[0]);
            relay.execute();
        };

        ChunkSaveTask task = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        task.execute();             // gen=1 IO 在飞 (gen1Future 未完成)

        // mixin 碰撞分支序列: markDirty -> tryMark(inc gauge) -> beginPending(gen=2, PREPARING) -> [dispatch 抛].
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        assertEquals(1L, metrics.snapshot().mustDrainPending());
        state.beginPendingSnapshot(snapshotForGeneration(state, 2L));

        // 在飞 IO 在 dispatch 抛之前先落地 -> REQUEUE_DIRTY -> 见 PREPARING 标 missed 返 null (不消费未就绪 tag).
        gen1Future.complete(null);
        assertEquals(1, submittedTags.size(), "回调路过 PREPARING 不提交 gen=2 (只在飞 gen=1)");
        assertTrue(state.hasPendingSnapshot(), "PREPARING (带 missed) 仍在槽, 回调未取走");

        // dispatch 抛 -> abortPendingSnapshot 撤销 PREPARING->EMPTY, 取回非 null -> 主线程清 mustDrain.
        ChunkSnapshot aborted = (ChunkSnapshot) state.abortPendingSnapshot();
        assertNotNull(aborted, "dispatch 抛时 abort 必须取回 PREPARING 的 pending (恒非 null)");
        if (state.compareAndClearMustDrain()) {
            metrics.decMustDrainPending();
        }
        assertFalse(state.hasPendingSnapshot(), "abort 后槽归 EMPTY");
        assertFalse(state.mustDrain(), "abort 自我撤销必须清 mustDrain");
        assertEquals(0L, metrics.snapshot().mustDrainPending(), "撤销后 gauge 配平归零");

        // 撤销后无任何后续接力 (在飞已落地走了 REQUEUE_DIRTY, 槽已 EMPTY, 无人再投).
        assertEquals(1, submittedTags.size(), "撤销后无接力重投 (只提交过 gen=1 一次)");
        assertEquals(1L, submittedTags.get(0).getLong("gen"), "在飞落地的是旧代 gen=1");
        assertEquals(0L, metrics.snapshot().mustDrainPending(),
                "撤销路径全程 gauge 配平归零, 不因 REQUEUE_DIRTY 永不清 mustDrain 而泄漏");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
    }

    private static int setMaxRetries(int value) throws Exception {
        Field f = com.shinoyuki.betterautosave.config.BetterAutoSaveConfig.class.getDeclaredField("maxRetries");
        f.setAccessible(true);
        int prev = f.getInt(null);
        f.setInt(null, value);
        return prev;
    }
}
