package com.shinoyuki.betterautosave.core.state;

import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.snapshot.ChunkSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不变式: missed 标记绝不得跨保存周期存活触发幽灵自踢。
 *
 * <p>SlotWord 协议用<b>周期序号代际 fence</b>守这条线: missed 携带它所属在飞周期的 inFlightCycleSeq,
 * beginPendingSnapshot / publishPendingSnapshot 仅在 missedCycle==inFlightCycleSeq 时认定同周期, 旧周期
 * 序号的 stale 自动丢弃 —— 同时覆盖 "清理前已存在的 stale" 与 "清理后才写入的 stale" 两种来源。
 */
class StaleMissedCrossCycleTest {

    private static ResourceKey<Level> DIM;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DIM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
    }

    private ChunkSnapshot snapshotForGeneration(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    /**
     * 三代 ABA 序列 (清理前已存在的 stale): gen3 不继承上一周期 stale missed -> publish 发布 READY 不提前自踢
     * -> gen2 回调正确判 REQUEUE_DIRTY。
     */
    @Test
    void stale_missed_does_not_survive_into_next_save_cycle() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);

        // === gen1 周期: 在飞期间被纯编辑 (gen=2), 落地判 REQUEUE_DIRTY, 回调路过空槽留 EMPTY_CONSUMER_PASSED. ===
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        state.enterIoPending();
        state.markDirty();          // gen=2 (在飞纯编辑, 无碰撞登记, 槽仍 EMPTY)

        ChunkSaveState.IoOutcome gen1Landed = state.ioCompletedSuccessfully();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, gen1Landed,
                "gen1 落地时 generation 已前进到 2 -> REQUEUE_DIRTY");
        // 回调路过空槽: takeReadyPendingSnapshot 见 EMPTY 标 consumerMissed -> 留 EMPTY_CONSUMER_PASSED.
        assertNull(state.takeReadyPendingSnapshot(),
                "回调见 EMPTY 只标 missed 返 null 不重投");
        assertFalse(state.hasPendingSnapshot(),
                "EMPTY_CONSUMER_PASSED 仍是 EMPTY 类 (hasPendingSnapshot 不含 missed 载体)");

        // === gen2 周期: 下一次 fresh capture (ChunkMap.save 正常路径) enterSerializing 开新周期. ===
        // 修复点: enterSerializing 分配新 inFlightCycleSeq, 使 gen1 周期留下的 missed 带的旧周期序号与本周期不等.
        state.trySnapshot();
        long gen2InFlight = state.enterSerializing();   // inFlightGeneration=2, 新 cycleSeq (> gen1 周期)
        assertEquals(2L, gen2InFlight);
        state.enterIoPending();

        // === gen3 碰撞 (gen2 在飞): 编辑 gen=3 + beginPendingSnapshot. ===
        state.markDirty();          // gen=3
        ChunkSnapshot gen3Pending = snapshotForGeneration(state, 3L);
        state.beginPendingSnapshot(gen3Pending);

        // 核心断言 1: gen3 PREPARING 不得继承 stale missed -> publish 发布 READY 等 gen2 回调, 不提前自踢.
        ChunkSnapshot selfReoffer = (ChunkSnapshot) state.publishPendingSnapshot();
        assertNull(selfReoffer,
                "gen3 不得继承 gen1 周期的 stale missed; publish 必须发布 READY (返 null) 而非提前自踢 (返非 null)");
        assertTrue(state.hasPendingSnapshot(), "publish 后槽为 READY, 等 gen2 回调消费");

        // 核心断言 2: gen2 IO 落地时 generation(3) != inFlightGeneration(2) -> REQUEUE_DIRTY, 不被
        // phantom 自踢提前覆盖的 inFlightGeneration 误判成 CLEAN_LANDED.
        ChunkSaveState.IoOutcome gen2Landed = state.ioCompletedSuccessfully();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, gen2Landed,
                "gen2 落地必须判 REQUEUE_DIRTY (inFlightGeneration 未被 phantom 自踢提前覆盖到 3)");

        // gen2 回调正常消费 READY 的 gen3 接力.
        ChunkSnapshot taken = (ChunkSnapshot) state.takeReadyPendingSnapshot();
        assertSame(gen3Pending, taken, "gen2 回调消费 READY 槽里的 gen3 接力");
    }

    /**
     * 清理之后才写入的 stale: 代际 fence 守得住 "晚写" 的 stale, 时序性单 CAS 清理守不住。
     *
     * <p>序: gen1 周期开 (cycleSeq=C1) -> 主线程<b>先</b>开 gen2 周期 (enterSerializing 把 cycleSeq 推到 C2) ->
     * gen1 回调<b>之后</b>才在 EMPTY 槽标 missed。生产路径 landAndTake 在 land 同一 CAS 用<b>当时</b>的
     * inFlightCycleSeq 标 missed, 故 missed 带 land 周期 C1。本用例断言代际 fence 对 "begin 所在周期与 missed
     * 所标周期一致" 的正确处理: 若 missed 标的是<b>更早</b>周期 (C1), 在 C2 周期 begin 必丢弃它。
     */
    @Test
    void stale_missed_from_earlier_cycle_dropped_even_when_written_late() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);

        // gen1 周期 (C1) 在飞 + 纯编辑 gen=2。
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // C1
        long c1 = state.slot().inFlightCycleSeq();
        state.enterIoPending();
        state.markDirty();          // gen=2

        // gen1 回调用 landAndTake (合并): missed 标 C1 周期 (land 时刻周期), 不会漂移。
        ChunkSaveState.LandResult gen1 = state.landAndTake();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, gen1.outcome());
        assertEquals(c1, state.slot().missedCycle(), "missed 带 land 周期 C1");

        // 主线程开 gen2 周期 (C2 > C1)。missed 仍是 C1 (写定在前)。
        state.trySnapshot();
        state.enterSerializing();   // C2
        long c2 = state.slot().inFlightCycleSeq();
        assertTrue(c2 > c1);
        state.enterIoPending();

        // gen3 碰撞 begin 在 C2 周期: missed(C1) != inFlightCycleSeq(C2) -> 代际 fence 丢弃 -> publish 不自踢。
        state.markDirty();          // gen=3
        ChunkSnapshot gen3 = snapshotForGeneration(state, 3L);
        state.beginPendingSnapshot(gen3);
        assertNull(state.publishPendingSnapshot(),
                "更早周期 (C1) 的 stale missed 在 C2 周期 begin 被代际 fence 丢弃, publish 发布 READY 不自踢");
        assertTrue(state.hasPendingSnapshot(), "槽为 READY 等 gen2 回调");

        // gen2 落地正确 REQUEUE_DIRTY (inFlightGeneration 未被幽灵自踢覆盖)。
        ChunkSaveState.LandResult gen2 = state.landAndTake();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, gen2.outcome(),
                "gen2 落地必判 REQUEUE_DIRTY, 不被幽灵自踢覆盖 inFlightGeneration");
        assertSame(gen3, gen2.relayPending());
    }

    /**
     * 最小不变式: 任意保存周期开始 (enterSerializing 分配新 cycleSeq) 后, 上一周期留下的 missed (旧周期序号) 都不得
     * 被后续 beginPendingSnapshot 继承进 PREPARING (即 publish 不自踢)。把 "missed 不跨周期" 压成单点约束。
     */
    @Test
    void fresh_cycle_does_not_inherit_prior_cycle_missed_marker() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);

        // 人为制造上一周期 missed: 先进一个在飞周期, 在 EMPTY 槽上让回调标 missed (带该周期序号).
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();   // 上一周期
        state.enterIoPending();
        assertNull(state.takeReadyPendingSnapshot(), "EMPTY 上 take 标 missed (上一周期序号) 返 null");
        state.markDirty();          // 推 generation 让下面 land 走 REQUEUE 不误清
        state.landAndTake();        // 落地 REQUEUE (phase=DIRTY), missed 仍在 (上一周期)

        // 开新周期: enterSerializing 分配新 cycleSeq, 旧 missed 周期序号与之不等.
        state.trySnapshot();
        state.enterSerializing();   // 新周期 (cycleSeq > 上一周期)
        state.enterIoPending();

        // 新周期登记 PREPARING -> publish 必须发布 READY (不继承上一周期 stale missed).
        state.markDirty();
        ChunkSnapshot pending = snapshotForGeneration(state, state.generation());
        state.beginPendingSnapshot(pending);
        assertNull(state.publishPendingSnapshot(),
                "新周期 PREPARING 不继承上一周期 stale missed (代际 fence), publish 发布 READY 返 null");

        // 反向保护: 代际 fence 只针对 missedCycle 字段, 不触碰并发挂着的 READY pendingKind/snapshot。
        ChunkSaveState live = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        ChunkSnapshot ready = snapshotForGeneration(live, 9L);
        live.registerReadyPendingSnapshot(ready);
        live.markDirty();
        live.trySnapshot();
        live.enterSerializing();    // 不得误清并发挂着的 READY
        assertTrue(live.hasPendingSnapshot(), "enterSerializing 不得误清并发挂着的 READY 槽");
        assertSame(ready, live.takeReadyPendingSnapshot(), "READY 槽内容完好");
    }
}
