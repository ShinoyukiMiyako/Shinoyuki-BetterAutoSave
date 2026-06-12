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
 * 不变式: 终态死亡出口 (onUnhandledError / onIoFailure FAILED_TERMINAL) 绝不得消费 PREPARING 槽的未就绪 tag
 * (C-unhandled-drains-preparing, 第九轮 Critical)。
 *
 * <p>核查员的否决理由落地成测试约束 (与第八轮"未就绪 tag 暴露"门同级): 旧码两个终态出口走 drainPendingSnapshot
 * 无条件夺走 PREPARING, 把主线程 dispatch 仍在原地改写的可变 tag 接力 assemble = 跨线程读写同一 CompoundTag
 * 的 HashMap, 静默数据损坏。修复后这两个出口只消费 READY, PREPARING 标 missed 交还主线程 publish 自踢。
 *
 * <p>技法 (复刻 ChunkPendingStateMachineTest 的门控 dispatcher): 在 dispatch (PREPARING) 窗口内让在飞 task
 * 走终态出口 (onUnhandledError 同步抛 / FAILED_TERMINAL IO 失败), 断言:
 * (a) 终态出口不消费未就绪 tag (sentinel: 落盘 relay tag 必含 dispatch 后才写的 sentinel);
 * (b) 主线程 publish 见 missed 自踢落盘最新代;
 * (c) mustDrain/serializing/ioPending 终值归零, 最新代恰落一次 (无双投)。
 *
 * <p>判定标准 (删修复必挂): 把终态出口退回 drainPendingSnapshot 全清 -> 终态 task 在门内 (sentinel 未写) 就
 * assemble 了 gen=2 tag -> 落盘 tag 缺 sentinel + 最新代提前落一次, 断言挂; 主线程随后 publish 在 EMPTY 上
 * 空转不自踢, gen=2 又被主线程自踢落一次 = 双投, 计数断言也挂。
 */
class TerminalExitPreparingHandoffTest {

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

    private ChunkSnapshot snapshotForGeneration(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    private static int countGeneration(List<CompoundTag> tags, long generation) {
        int n = 0;
        for (CompoundTag t : tags) {
            if (t.getLong("gen") == generation) {
                n++;
            }
        }
        return n;
    }

    /**
     * onUnhandledError 出口: 在 dispatch (PREPARING) 窗口内在飞 task 同步抛走 onUnhandledError, 断言它不消费
     * PREPARING 未就绪 tag, missed 交还主线程, 主线程 publish 自踢落盘含 sentinel 的最新代, gauge 终值归零无双投。
     */
    @Test
    void unhandled_error_during_dispatch_does_not_consume_preparing_hands_to_main() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1

        List<CompoundTag> submittedTags = new ArrayList<>();
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            return CompletableFuture.completedFuture(null);
        };
        ChunkSaveTask.PendingReoffer[] reofferHolder = new ChunkSaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            metrics.incInFlightSerializing();
            ChunkSaveTask relay = new ChunkSaveTask(pending, metrics, null, recoveryQueue,
                    submitter, reofferHolder[0]);
            relay.execute();
        };

        // gen=1 在飞 task (尚未 execute; 我们手动在门内驱动它走 onUnhandledError).
        ChunkSaveTask gen1Task = new ChunkSaveTask(snapshotForGeneration(state, 1L), metrics, null, recoveryQueue,
                submitter, reofferHolder[0]);
        metrics.incInFlightSerializing();   // 复刻 dispatch 时的 inc (gen=1 在飞)

        // 碰撞: markDirty -> tryMark -> beginPending(gen=2, PREPARING). gen=2 tag 初始无 sentinel.
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        ChunkSnapshot gen2Pending = snapshotForGeneration(state, 2L);
        CompoundTag gen2Tag = gen2Pending.preBuiltFullTag();
        assertFalse(gen2Tag.contains("listenerSentinel"), "前置条件: gen=2 tag 初始无 sentinel");
        state.beginPendingSnapshot(gen2Pending);

        boolean[] terminalConsumedUnready = {false};
        ChunkCaptureProcedure.SaveEventDispatcher saved = ChunkCaptureProcedure.swapSaveEventDispatcher(
                (chunk, level, eventTag) -> {
                    // (a) sentinel 未写时, 在飞 gen=1 task 同步抛走 onUnhandledError. 此刻槽 PREPARING:
                    //     终态出口必须标 missed 不消费; 若它消费, relay 会在此刻 (sentinel 未写) assemble gen=2.
                    int before = submittedTags.size();
                    // 复刻 execute 同步异常路径: assemble 抛会先 dec serializing.
                    metrics.decInFlightSerializing();
                    gen1Task.onUnhandledError(new RuntimeException("assemble boom in dispatch window"));
                    if (submittedTags.size() != before) {
                        terminalConsumedUnready[0] = true;
                    }
                    // (b) listener 现在才往 tag 写 sentinel.
                    eventTag.putBoolean("listenerSentinel", true);
                });
        try {
            ChunkCaptureProcedure.dispatchSaveEvent(null, null, gen2Pending, ConfigSpec.EventCompatMode.FULL, metrics);
        } finally {
            ChunkCaptureProcedure.swapSaveEventDispatcher(saved);
        }

        // dispatch 返回 -> 主线程 publish 见 missed -> 取回 gen=2 自踢.
        ChunkSnapshot toReoffer = state.publishPendingSnapshot();
        assertSame(gen2Pending, toReoffer, "onUnhandledError 标了 missed, publish 必须取回 pending 交主线程自踢");
        state.reenterSerializingForPending(toReoffer.capturedGeneration());
        reofferHolder[0].reoffer(toReoffer);

        assertFalse(terminalConsumedUnready[0],
                "onUnhandledError 落于 PREPARING 窗口时绝不能消费未就绪 tag");
        CompoundTag landed = submittedTags.get(submittedTags.size() - 1);
        assertEquals(2L, landed.getLong("gen"), "主线程自踢落盘的是 gen=2");
        assertTrue(landed.getBoolean("listenerSentinel"),
                "落盘 relay tag 必含 sentinel (assemble 严格晚于 listener 写完, 证明未在门内提前消费)");
        assertEquals(1, countGeneration(submittedTags, 2L), "gen=2 只落盘一次 (无双投)");

        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase());
        assertFalse(state.mustDrain(), "全链落地 mustDrain 归零");
        assertFalse(state.hasPendingSnapshot(), "全链落地槽空");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.mustDrainPending(), "mustDrain gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending 配平归零");
    }

    /**
     * FAILED_TERMINAL 出口 (同构用例): 在 dispatch (PREPARING) 窗口内在飞 gen=1 IO 终态失败走 FAILED_TERMINAL,
     * 断言它不消费 PREPARING 未就绪 tag, missed 交还主线程, 主线程 publish 自踢落盘含 sentinel 的最新代, 终值归零。
     */
    @Test
    void terminal_failure_during_dispatch_does_not_consume_preparing_hands_to_main() throws Exception {
        // maxRetries=0: 首次 IO 失败即 FAILED_TERMINAL.
        setMaxRetries(0);

        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1

        // gen=1 IO 用手动 future (在门内 completeExceptionally 触发 FAILED_TERMINAL); relay IO 用已完成 future.
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

        // gen=1 在飞: execute 提交 gen=1 IO (gen1Future 未完成).
        ChunkSaveTask gen1Task = new ChunkSaveTask(snapshotForGeneration(state, 1L), metrics, null, recoveryQueue,
                submitter, reofferHolder[0]);
        metrics.incInFlightSerializing();
        gen1Task.execute();
        assertEquals(1, submittedTags.size(), "gen=1 IO 已提交在飞");

        // 碰撞: beginPending(gen=2, PREPARING), 初始无 sentinel.
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        ChunkSnapshot gen2Pending = snapshotForGeneration(state, 2L);
        assertFalse(gen2Pending.preBuiltFullTag().contains("listenerSentinel"), "前置: gen=2 无 sentinel");
        state.beginPendingSnapshot(gen2Pending);

        boolean[] terminalConsumedUnready = {false};
        ChunkCaptureProcedure.SaveEventDispatcher saved = ChunkCaptureProcedure.swapSaveEventDispatcher(
                (chunk, level, eventTag) -> {
                    // (a) sentinel 未写时, gen=1 IO 终态失败 -> whenComplete error -> onIoFailure -> FAILED_TERMINAL.
                    //     槽 PREPARING: 必须标 missed 不消费.
                    int before = submittedTags.size();
                    gen1Future.completeExceptionally(new RuntimeException("io terminal fail in dispatch window"));
                    if (submittedTags.size() != before) {
                        terminalConsumedUnready[0] = true;
                    }
                    // (b) listener 写 sentinel.
                    eventTag.putBoolean("listenerSentinel", true);
                });
        try {
            ChunkCaptureProcedure.dispatchSaveEvent(null, null, gen2Pending, ConfigSpec.EventCompatMode.FULL, metrics);
        } finally {
            ChunkCaptureProcedure.swapSaveEventDispatcher(saved);
        }

        // dispatch 返回 -> 主线程 publish 见 missed -> 取回 gen=2 自踢.
        ChunkSnapshot toReoffer = state.publishPendingSnapshot();
        assertSame(gen2Pending, toReoffer, "FAILED_TERMINAL 标了 missed, publish 必须取回 pending 交主线程自踢");
        state.reenterSerializingForPending(toReoffer.capturedGeneration());
        reofferHolder[0].reoffer(toReoffer);

        assertFalse(terminalConsumedUnready[0],
                "FAILED_TERMINAL 落于 PREPARING 窗口时绝不能消费未就绪 tag");
        CompoundTag landed = submittedTags.get(submittedTags.size() - 1);
        assertEquals(2L, landed.getLong("gen"), "主线程自踢落盘的是 gen=2");
        assertTrue(landed.getBoolean("listenerSentinel"),
                "落盘 relay tag 必含 sentinel (assemble 严格晚于 listener 写完)");
        assertEquals(1, countGeneration(submittedTags, 2L), "gen=2 只落盘一次 (无双投)");

        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase());
        assertFalse(state.mustDrain(), "全链落地 mustDrain 归零");
        assertFalse(state.hasPendingSnapshot(), "全链落地槽空");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.mustDrainPending(), "mustDrain gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending 配平归零");
    }

    /**
     * FAILED_TERMINAL 出口对 READY 槽: 旧代 IO 终态失败但碰撞最新代已 READY -> 接力它 (接力优先于 vanilla 兜底),
     * 而非旧码那样 drain 丢弃。验证 fixSketch step 2 的 READY 接力语义。
     *
     * <p>判定标准 (删修复必挂): 删 handleTerminalFailure 的 CONSUMED 接力分支 (退回 drain 丢弃) -> gen=2 不落盘,
     * "接力落最新代"断言挂; 且 mustDrain 被提前清, 终值断言挂。
     */
    @Test
    void terminal_failure_with_ready_slot_relays_latest_generation() throws Exception {
        setMaxRetries(0);   // 首次失败即 FAILED_TERMINAL

        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1

        // gen=1 IO 手动失败; relay gen=2 IO 成功.
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

        ChunkSaveTask gen1Task = new ChunkSaveTask(snapshotForGeneration(state, 1L), metrics, null, recoveryQueue,
                submitter, reofferHolder[0]);
        metrics.incInFlightSerializing();
        gen1Task.execute();         // gen=1 IO 在飞

        // 碰撞: 登记 READY 最新代 (无 dispatch 窗口的就绪登记, 复刻槽已 READY).
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        ChunkSnapshot gen2Pending = snapshotForGeneration(state, 2L);
        state.registerReadyPendingSnapshot(gen2Pending);

        // gen=1 IO 终态失败 -> FAILED_TERMINAL -> 槽 READY -> 接力 gen=2 (relay IO 成功 -> CLEAN_LANDED).
        gen1Future.completeExceptionally(new RuntimeException("gen1 io terminal fail"));

        assertEquals(2L, submittedTags.get(submittedTags.size() - 1).getLong("gen"),
                "FAILED_TERMINAL 见 READY 槽必须接力最新代 gen=2 (接力优先于 vanilla 兜底)");
        assertSame(gen2Pending.preBuiltFullTag(), submittedTags.get(submittedTags.size() - 1),
                "接力提交的是 gen=2 pending 的 tag 实例");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase(), "接力落地 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "接力链落地后 mustDrain 归零");
        assertFalse(state.hasPendingSnapshot(), "接力消费后槽空");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.mustDrainPending(), "mustDrain gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending 配平归零");
    }

    private static int setMaxRetries(int value) throws Exception {
        Field f = com.shinoyuki.betterautosave.config.BetterAutoSaveConfig.class.getDeclaredField("maxRetries");
        f.setAccessible(true);
        int prev = f.getInt(null);
        f.setInt(null, value);
        return prev;
    }
}
