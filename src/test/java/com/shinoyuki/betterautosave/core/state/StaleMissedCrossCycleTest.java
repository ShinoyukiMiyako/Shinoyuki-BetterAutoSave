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
 * 不变式: consumerMissed 标记 (EMPTY_CONSUMER_PASSED 载体) 绝不得跨保存周期存活
 * (C-stale-missed-no-epoch, 第九轮 Critical)。
 *
 * <p>核查员的否决理由落地成测试约束 (与第八轮 mustDrain 不变式同级): 一个保存周期里某代 IO 落地判
 * REQUEUE_DIRTY 时回调路过空槽留下的 consumerMissed 标记, 必须在下一个 fresh capture 周期
 * (enterSerializing) 被清掉, 不能被后续 beginPendingSnapshot 继承去触发幽灵自踢。
 *
 * <p>触发链 (修复前的 ABA): gen1 周期 REQUEUE 留 EMPTY_CONSUMER_PASSED -> gen2 enterSerializing 开新
 * 周期 (修复前不清) -> gen3 碰撞 beginPendingSnapshot 继承 stale missed -> publishPendingSnapshot 见
 * missed 提前自踢 (本应发布 READY 等 gen2 回调) -> reenterSerializingForPending 覆盖 inFlightGeneration ->
 * gen2 回调误判 CLEAN_LANDED + 写序反转。
 *
 * <p>判定标准 (删修复必挂): 删 enterSerializing 末尾的 compareAndSet(EMPTY_CONSUMER_PASSED, EMPTY) ->
 * gen2 周期继承 stale missed, 下面 publishPendingSnapshot 返回非 null (提前自踢) 而非 null (发布 READY),
 * 断言挂; 且 gen2 ioCompletedSuccessfully 误判 CLEAN_LANDED 而非 REQUEUE_DIRTY, 断言挂。
 */
class StaleMissedCrossCycleTest {

    private static ResourceKey<Level> DIM;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DIM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation("minecraft", "overworld"));
    }

    private ChunkSnapshot snapshotForGeneration(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    /**
     * 直接复刻三代 ABA 序列 (核查报告 evidence 里 StaleMissedCrossCycleReproTest 的状态机层步骤), 修复后
     * 断言全部翻转: gen3 不继承 stale missed -> publish 发布 READY 不提前自踢 -> gen2 回调正确判 REQUEUE_DIRTY。
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
        // 修复点: 这一步必须清掉 gen1 留下的 EMPTY_CONSUMER_PASSED.
        state.trySnapshot();
        long gen2InFlight = state.enterSerializing();   // inFlightGeneration=2, 修复后清 EMPTY_CONSUMER_PASSED
        assertEquals(2L, gen2InFlight);
        state.enterIoPending();

        // === gen3 碰撞 (gen2 在飞): 编辑 gen=3 + beginPendingSnapshot. ===
        state.markDirty();          // gen=3
        ChunkSnapshot gen3Pending = snapshotForGeneration(state, 3L);
        state.beginPendingSnapshot(gen3Pending);

        // 核心断言 1: gen3 PREPARING 不得继承 stale missed -> publish 发布 READY 等 gen2 回调, 不提前自踢.
        ChunkSnapshot selfReoffer = state.publishPendingSnapshot();
        assertNull(selfReoffer,
                "gen3 不得继承 gen1 周期的 stale missed; publish 必须发布 READY (返 null) 而非提前自踢 (返非 null)");
        assertTrue(state.hasPendingSnapshot(), "publish 后槽为 READY, 等 gen2 回调消费");

        // 核心断言 2: gen2 IO 落地时 generation(3) != inFlightGeneration(2) -> REQUEUE_DIRTY, 不被
        // phantom 自踢提前覆盖的 inFlightGeneration 误判成 CLEAN_LANDED.
        ChunkSaveState.IoOutcome gen2Landed = state.ioCompletedSuccessfully();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, gen2Landed,
                "gen2 落地必须判 REQUEUE_DIRTY (inFlightGeneration 未被 phantom 自踢提前覆盖到 3)");

        // gen2 回调正常消费 READY 的 gen3 接力.
        ChunkSnapshot taken = state.takeReadyPendingSnapshot();
        assertSame(gen3Pending, taken, "gen2 回调消费 READY 槽里的 gen3 接力");
    }

    /**
     * 最小不变式: 任意保存周期开始 (enterSerializing) 后, 不论上一周期是否留下 EMPTY_CONSUMER_PASSED,
     * 后续 beginPendingSnapshot 产生的 PREPARING 都不得带 consumerMissed (即 publish 不自踢)。
     * 这是把"missed 不跨周期"压成一条单点约束。
     */
    @Test
    void enter_serializing_clears_only_stale_missed_singleton_not_live_slots() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);

        // 人为制造 EMPTY_CONSUMER_PASSED: 在 EMPTY 槽上让回调标 missed.
        assertNull(state.takeReadyPendingSnapshot(), "EMPTY 上 take 标 missed 返 null");

        // 开新周期: enterSerializing 必须清掉它.
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();

        // 新周期登记 PREPARING -> publish 必须发布 READY (不继承 stale missed).
        ChunkSnapshot pending = snapshotForGeneration(state, state.generation());
        state.beginPendingSnapshot(pending);
        assertNull(state.publishPendingSnapshot(),
                "新周期 PREPARING 不带继承的 stale missed, publish 发布 READY 返 null");

        // 反向保护: enterSerializing 不得误清并发挂着的 READY (它只精确 CAS 单例 EMPTY_CONSUMER_PASSED)。
        ChunkSaveState live = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        ChunkSnapshot ready = snapshotForGeneration(live, 9L);
        live.registerReadyPendingSnapshot(ready);
        live.markDirty();
        live.trySnapshot();
        live.enterSerializing();    // 槽是 READY 不是 EMPTY_CONSUMER_PASSED, CAS 不命中, 不清
        assertTrue(live.hasPendingSnapshot(), "enterSerializing 不得误清并发挂着的 READY 槽");
        assertSame(ready, live.takeReadyPendingSnapshot(), "READY 槽内容完好");
    }
}
