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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pendingSnapshot 四态状态机 (EMPTY/PREPARING/READY + consumerMissed) 的交错穷举回归
 * (dispatch 与在途登记之间的 TOCTOU 窗口)。
 *
 * <p>两组用例:
 * <ol>
 *   <li><b>tag-race 受控复刻</b>: 注入门控 SaveEventDispatcher —— dispatch (PREPARING 窗口) 内手动完成在飞
 *       future 触发 REQUEUE_DIRTY, 断言回调**不**取走未就绪 tag (PREPARING 不可消费), 随后 listener 才往
 *       eventTag 写 sentinel, dispatch 返回 publish 自踢 —— 最终落盘的 relay tag **必含** sentinel, 证明
 *       worker assemble 严格晚于 listener 写完。若把已登记 (register) 直接当作可消费, 回调会抢走 listener 仍在改写的 tag,
 *       relay 落盘缺 sentinel —— 该断言挂。受控交错 (无 sleep), 确定性。</li>
 *   <li><b>状态机全交错矩阵</b>: 回调到达点 (PREPARING 前/中/后) x dispatch 成功/抛 x 单代/嵌套, 每格断言
 *       pending 恰被消费一次 + mustDrain/serializing/ioPending 终值归零 + 槽终态 EMPTY + 无双投。</li>
 * </ol>
 */
class ChunkPendingStateMachineTest {

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

    /** 每代独立 tag, "gen" 标记代号; FULL 模式 (worker assemble 直接返回 preBuiltFullTag)。 */
    private ChunkSnapshot snapshotForGeneration(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    // ===================== 1) tag-race 受控复刻 =====================

    /**
     * 核心反例: 在飞 IO 落地于 dispatch (PREPARING) 窗口内时, 回调不得取走 listener 仍在原地
     * 改写的未就绪 tag。复刻全链: begin(PREPARING) -> [dispatcher 门内: 完成在飞 future 触发回调 -> 回调见
     * PREPARING 标 missed 不消费 -> listener 写 sentinel] -> publish 见 missed 自踢 -> relay assemble 落盘。
     * 断言落盘 relay tag 含 sentinel (worker 严格晚于 listener)。
     *
     * <p>判定标准: 把 takeReadyPendingSnapshot 的 PREPARING 分支退回直接消费 -> 回调在门内就
     * assemble 了 gen=2 tag (此刻 sentinel 尚未写入) -> 落盘 tag 缺 sentinel, "含 sentinel"断言挂。
     */
    @Test
    void inflight_landing_during_dispatch_does_not_consume_unready_tag() {
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

        ChunkSaveTask inFlightTask = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        inFlightTask.execute();     // gen=1 IO 在飞 (gen1Future 未完成)

        // 碰撞: markDirty -> tryMark -> beginPending(gen=2, PREPARING). gen=2 的 tag 初始无 sentinel.
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        ChunkSnapshot gen2Pending = snapshotForGeneration(state, 2L);
        CompoundTag gen2Tag = gen2Pending.preBuiltFullTag();
        assertFalse(gen2Tag.contains("listenerSentinel"), "前置条件: gen=2 tag 初始无 sentinel");
        state.beginPendingSnapshot(gen2Pending);

        // 门控 dispatcher: 复刻无界 listener 在主线程原地改写这份 tag, 且在改写之前在飞 IO 恰落地。
        boolean[] callbackSawUnreadyTag = {false};
        ChunkCaptureProcedure.SaveEventDispatcher saved = ChunkCaptureProcedure.swapSaveEventDispatcher(
                (chunk, level, eventTag) -> {
                    // (a) listener 尚未写 sentinel 时, 在飞 IO 落地 -> REQUEUE_DIRTY -> 回调 takeReadyPendingSnapshot.
                    //     此刻槽是 PREPARING, 回调必须标 missed 返 null 不消费; 若它消费, relay 会在此刻 (sentinel
                    //     未写) assemble, submittedTags 会多出一条缺 sentinel 的 gen=2.
                    int submittedBefore = submittedTags.size();
                    gen1Future.complete(null);
                    int submittedAfter = submittedTags.size();
                    if (submittedAfter != submittedBefore) {
                        // 回调在门内提交了 (说明错误地消费了 PREPARING) —— 记录供断言.
                        callbackSawUnreadyTag[0] = true;
                    }
                    // (b) listener 现在才往这份 tag 写增量 sentinel.
                    eventTag.putBoolean("listenerSentinel", true);
                });
        try {
            ChunkCaptureProcedure.dispatchSaveEvent(null, null, gen2Pending, ConfigSpec.EventCompatMode.FULL, metrics);
        } finally {
            ChunkCaptureProcedure.swapSaveEventDispatcher(saved);
        }

        // dispatch 返回 -> publish 见 missed -> 取回 gen=2 主线程自踢.
        ChunkSnapshot toReoffer = state.publishPendingSnapshot();
        assertSame(gen2Pending, toReoffer, "回调已 missed, publish 必须取回 pending 交主线程自踢");
        state.reenterSerializingForPending(toReoffer.capturedGeneration());
        reofferHolder[0].reoffer(toReoffer);

        // 核心断言: 回调在门内 (PREPARING) 没有消费未就绪 tag.
        assertFalse(callbackSawUnreadyTag[0],
                "在飞回调落地于 PREPARING 窗口时绝不能消费未就绪 tag (否则取走 listener 仍在改写的 tag)");
        // 落盘的 relay tag 必含 sentinel: 证明 worker assemble 严格晚于 listener 写完.
        CompoundTag landed = submittedTags.get(submittedTags.size() - 1);
        assertEquals(2L, landed.getLong("gen"), "落盘的是 gen=2");
        assertTrue(landed.getBoolean("listenerSentinel"),
                "落盘 relay tag 必含 listener sentinel (worker 晚于 listener 写完才 assemble)");
        assertEquals(1, countGeneration(submittedTags, 2L), "gen=2 只落盘一次 (无双投)");

        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase());
        assertFalse(state.mustDrain(), "全链落地 mustDrain 归零");
        assertFalse(state.hasPendingSnapshot(), "全链落地槽空");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightSerializing(), "serializing 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending 配平归零");
    }

    // ===================== 2) 状态机全交错矩阵 =====================

    private enum CallbackArrival {
        BEFORE_PREPARING,   // 回调在 beginPending 之前落地 (slot EMPTY) -> EMPTY_CONSUMER_PASSED 标记
        DURING_PREPARING,   // 回调在 dispatch 窗口内落地 (slot PREPARING) -> 标 consumerMissed
        AFTER_READY         // 回调在 publish 之后落地 (slot READY) -> 正常 take+reoffer
    }

    private static Stream<Arguments> matrix() {
        List<Arguments> out = new ArrayList<>();
        for (CallbackArrival arrival : CallbackArrival.values()) {
            for (boolean dispatchThrows : new boolean[]{false, true}) {
                for (boolean nested : new boolean[]{false, true}) {
                    out.add(Arguments.of(arrival, dispatchThrows, nested));
                }
            }
        }
        return out.stream();
    }

    /**
     * 全交错: 每格驱动 begin/dispatch/publish/abort 协议 + 在指定点插入在飞回调, 断言 pending 恰被消费一次
     * (最新代落盘) + 三 gauge 终值归零 + 槽 EMPTY。dispatch 抛时走 abort 撤销 (放弃接力, 信任在飞旧代),
     * 故落盘代不同分支不同, 但 gauge/槽 终态恒一致。
     */
    @ParameterizedTest(name = "arrival={0} dispatchThrows={1} nested={2}")
    @MethodSource("matrix")
    void state_machine_all_interleavings(CallbackArrival arrival, boolean dispatchThrows, boolean nested) {
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

        ChunkSaveTask inFlightTask = new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]);
        inFlightTask.execute();     // gen=1 IO 在飞

        // 碰撞: markDirty -> tryMark(inc gauge).
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();

        // 回调到达点 BEFORE_PREPARING: 在飞 IO 在 beginPending 之前落地 (slot EMPTY).
        if (arrival == CallbackArrival.BEFORE_PREPARING) {
            gen1Future.complete(null);
            assertEquals(1, submittedTags.size(), "BEFORE: 回调见 EMPTY 只标记不重投, 仅在飞 gen=1 落盘");
        }

        long latestGen = 2L;
        ChunkSnapshot gen2Pending = snapshotForGeneration(state, 2L);
        state.beginPendingSnapshot(gen2Pending);

        // 嵌套: PREPARING 期间第二次主线程碰撞, 覆盖为 gen=3 (最新者胜 + missed 合并).
        if (nested) {
            state.markDirty();      // gen=3
            latestGen = 3L;
            state.beginPendingSnapshot(snapshotForGeneration(state, 3L));
        }

        // 回调到达点 DURING_PREPARING: dispatch 窗口内落地 (slot PREPARING) -> 标 missed 不消费.
        if (arrival == CallbackArrival.DURING_PREPARING) {
            gen1Future.complete(null);
            assertEquals(1, submittedTags.size(), "DURING: 回调见 PREPARING 标 missed 不重投, 仅在飞 gen=1 落盘");
            assertTrue(state.hasPendingSnapshot(), "DURING: PREPARING 仍挂 pending 未被消费");
        }

        // dispatch.
        boolean aborted = false;
        if (dispatchThrows) {
            // dispatch 抛 -> abort 撤销 PREPARING. 撤销取回非 null (回调从不消费 PREPARING).
            ChunkSnapshot back = state.abortPendingSnapshot();
            assertSame(latestGenSnapshot(state, back, latestGen), back, "abort 取回的是最新代 pending");
            if (state.compareAndClearMustDrain()) {
                metrics.decMustDrainPending();
            }
            aborted = true;
        } else {
            // dispatch 成功 -> publish. 若 missed (BEFORE/DURING) 主线程自踢; 否则发布 READY 等回调.
            ChunkSnapshot toReoffer = state.publishPendingSnapshot();
            if (toReoffer != null) {
                state.reenterSerializingForPending(toReoffer.capturedGeneration());
                reofferHolder[0].reoffer(toReoffer);
            }
        }

        // 回调到达点 AFTER_READY: publish 已发布 READY, 现在落地消费.
        if (arrival == CallbackArrival.AFTER_READY) {
            gen1Future.complete(null);
        }

        // ===== 终值断言 (所有格通用) =====
        assertFalse(state.hasPendingSnapshot(), "终态槽必 EMPTY (pending 恰被消费一次或撤销)");
        assertEquals(0L, metrics.snapshot().mustDrainPending(), "mustDrain gauge 终值归零");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 终值归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 终值归零");
        assertFalse(state.mustDrain(), "mustDrain boolean 终值 false");

        // 落盘代断言: dispatch 抛走撤销, 放弃接力, 只落在飞 gen=1; 否则最新代 (gen=2 或嵌套 gen=3) 恰落一次.
        if (aborted) {
            assertEquals(0, countGeneration(submittedTags, latestGen),
                    "dispatch 抛撤销后最新代不落盘 (信任在飞旧代)");
            assertEquals(1, countGeneration(submittedTags, 1L), "仅在飞 gen=1 落盘一次");
        } else {
            assertEquals(1, countGeneration(submittedTags, latestGen),
                    "dispatch 成功时最新代 (" + latestGen + ") 必恰落盘一次 (无双投无丢失)");
            assertEquals(latestGen, submittedTags.get(submittedTags.size() - 1).getLong("gen"),
                    "最后落盘的是最新代");
            assertEquals(ChunkSaveState.Phase.CLEAN, state.phase(), "成功链终态 phase CLEAN");
        }
    }

    /** 防御性: abort 取回的 pending 应当代号 == latestGen (确认覆盖语义最新者胜)。 */
    private ChunkSnapshot latestGenSnapshot(ChunkSaveState state, ChunkSnapshot back, long latestGen) {
        if (back != null) {
            assertEquals(latestGen, back.preBuiltFullTag().getLong("gen"),
                    "abort 取回的必须是最新代 pending (覆盖最新者胜)");
        }
        return back;
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

    private static int setMaxRetries(int value) throws Exception {
        Field f = com.shinoyuki.betterautosave.config.BetterAutoSaveConfig.class.getDeclaredField("maxRetries");
        f.setAccessible(true);
        int prev = f.getInt(null);
        f.setInt(null, value);
        return prev;
    }
}
