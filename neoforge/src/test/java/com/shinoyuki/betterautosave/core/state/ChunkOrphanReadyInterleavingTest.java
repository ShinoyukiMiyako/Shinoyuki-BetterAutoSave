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

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * chunk 在飞已 CLEAN 终态退出 (无在飞消费者) 交错下的 READY 孤儿防御回归.
 *
 * <p>致命交错: 在飞那代回调读 generation==inFlightGeneration 准备 CLEAN_LANDED 期间, 主线程纯编辑推 generation
 * (markDirty 在 phase != CLEAN 时只推 generation 不碰 word), 回调 CAS 仍命中落地 CLEAN_LANDED 干净退出
 * (phase=CLEAN, drainOwner=NONE, task 不再消费 READY); 主线程随后碰撞分支 capturePending(更新代) + begin + publish。
 * begin 看到 phase=CLEAN 在同一 CAS 立 noInFlightConsumer 标记; publish 据它把 pending 交还主线程自踢 (返回非 null),
 * 不发布一个永无消费者的 READY 孤儿。若 publish 在此交错下发布 READY, 槽残孤儿 + mustDrainPending 永久 +1 + 卸载增量丢失。
 *
 * <p>begin 落在 phase==CLEAN 上是该交错的唯一判别点 (在飞回调干净退出后 phase 必为 CLEAN)。本测试确定性复刻
 * 这个输入态: 在飞回调 landAndTake 判 CLEAN_LANDED 干净退出后, 主线程碰撞分支 begin + publish 落在 phase==CLEAN,
 * 断言 publish 自踢交还不发孤儿; 另加反序对照 (begin 先于 land, phase==IO_PENDING) 守护既有安全序行为不被改动。
 * 断言落在落盘代号 / mustDrain gauge 精确值 / 槽是否残留 READY 孤儿。
 */
class ChunkOrphanReadyInterleavingTest {

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
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(7, -3), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    /**
     * 协议契约 (确定性): 在飞回调已 CLEAN_LANDED 干净退出 (phase=CLEAN) 后, 主线程碰撞分支 begin(更新代) 落在
     * phase==CLEAN 上 —— 无在飞消费者。begin 必须立 noInFlightConsumer 标记, publish 把 pending 交还主线程自踢
     * (返回非 null), 自踢 sink 落盘更新代, mustDrainPending 配平归零, 槽无 READY 残留。
     *
     * <p>为隔离协议本身, 直接构造 "phase==CLEAN 上 begin" 序: 不经 mixin 的 generation 守门 (那是 mixin 层职责,
     * 由下面的端到端并发用例覆盖)。这里锁定状态机在 "无在飞消费者" 输入下的输出契约。
     *
     * <p>判定标准 (删 begin 的 noInFlightConsumer 立标 或 publish 的第三触发点): publish 返回 null 发布 READY 孤儿,
     * 本测试 publish 返回非 null 断言挂 + 槽残 READY (hasPendingSnapshot 仍 true) + mustDrainPending 残 1。
     */
    @Test
    void begin_on_clean_phase_hands_pending_back_no_orphan() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(7, -3).toLong(), "minecraft:overworld", 1L);
        AtomicLong gauge = new AtomicLong();

        // G1 在飞: mixin 在飞分支 tryMarkMustDrain + inc gauge.
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        state.enterIoPending();
        if (state.tryMarkMustDrain()) {
            gauge.incrementAndGet();
        }
        assertEquals(1L, gauge.get());

        // 在飞回调干净 CLEAN_LANDED 退出 (generation 未推进时落地): phase=CLEAN, 清 drainOwner, gauge dec 归零.
        ChunkSaveState.LandResult clean = state.landAndTake();
        assertEquals(ChunkSaveState.IoOutcome.CLEAN_LANDED, clean.outcome());
        if (state.lastTransitionClearedMustDrain()) {
            gauge.decrementAndGet();
        }
        assertEquals(0L, gauge.get(), "CLEAN_LANDED 终态 dec, gauge 归零");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase(), "在飞回调干净退出后 phase=CLEAN");

        // 主线程碰撞分支续跑 (它在碰撞分支已提交, 不重检 phase): tryMarkMustDrain + begin + publish 落在 phase==CLEAN.
        boolean reMarked = state.tryMarkMustDrain();
        if (reMarked) {
            gauge.incrementAndGet();
        }
        assertTrue(reMarked, "在飞分支重新 tryMarkMustDrain 置位 (CLEAN_LANDED 已清 drainOwner)");

        // pending 的 capturedGeneration 取当前 generation, 使自踢接力 reenter 锁的代与现态一致 (本用例隔离
        // 协议契约, 不掺 mixin 的 generation 守门; 更新代落地由并发用例覆盖)。
        long pendingGen = state.generation();
        ChunkSnapshot pending = snapshotForGeneration(state, pendingGen);
        boolean reacquired = state.beginPendingSnapshot(pending);
        assertFalse(reacquired, "begin 进入时 drainOwner 已 IN_FLIGHT, 无需补 inc");
        assertEquals(1L, gauge.get());
        assertTrue(state.slot().pendingNoInFlightConsumer(),
                "begin 落在 phase==CLEAN 必须立 noInFlightConsumer 标记 (无在飞消费者)");
        assertTrue(state.mustDrain(), "begin 后槽非空 -> drainOwner != NONE (不变式)");

        ChunkSnapshot toReoffer = (ChunkSnapshot) state.publishPendingSnapshot();
        assertNotNull(toReoffer,
                "begin 落 CLEAN (无在飞消费者) 时 publish 必须把 pending 交还主线程自踢, 不发布 READY 孤儿");
        assertSame(pending, toReoffer, "自踢交还的是登记的 pending");
        assertFalse(state.hasPendingSnapshot(), "自踢取走 pending 后槽归 NONE, 无 READY 孤儿残留");

        // 主线程自踢接力 (复刻 reofferChunkPendingFromMainThread): reenter(RELAY) + sink 落盘 + 终态收口.
        state.reenterSerializingForPending(toReoffer.capturedGeneration());
        state.enterIoPending();
        long selfKickedGeneration = toReoffer.preBuiltFullTag().getLong("gen");
        ChunkSaveState.LandResult relay = state.landAndTake();
        assertEquals(ChunkSaveState.IoOutcome.CLEAN_LANDED, relay.outcome(),
                "自踢接力 IO 落地 generation==inFlightGeneration -> CLEAN_LANDED");
        if (state.lastTransitionClearedMustDrain()) {
            gauge.decrementAndGet();
        }

        assertEquals(pendingGen, selfKickedGeneration, "自踢 sink 必须落盘登记的接力代");
        assertEquals(0L, gauge.get(), "全链落地后 mustDrainPending 必须配平归零 (无孤儿正偏移泄漏)");
        assertFalse(state.mustDrain(), "终局 drainOwner=NONE");
        assertFalse(state.hasPendingSnapshot(), "终局槽 NONE, 无残留");
    }

    /**
     * 反序对照 (回归守护): begin 先于在飞 land。begin 在 phase==IO_PENDING 上挂 PREPARING (marker 为 false),
     * 在飞 land 走 REQUEUE_DIRTY 见 PREPARING 标本周期 missed 离开 (不取未就绪 tag), publish 经 missedCycle
     * 自踢接力 —— 证明修复没动这条既有安全序行为 (publish 仍走 missed 触发点而非新 marker)。
     *
     * <p>判定标准: marker 必须为 false (begin 在 IO_PENDING 上不立 marker); 自踢由 missed 触发而非 marker。
     */
    @Test
    void begin_before_land_uses_missed_trigger_not_marker() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(7, -3).toLong(), "minecraft:overworld", 1L);
        AtomicLong gauge = new AtomicLong();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1, cycle C1
        long c1 = state.slot().inFlightCycleSeq();
        state.enterIoPending();
        if (state.tryMarkMustDrain()) {
            gauge.incrementAndGet();
        }

        // 碰撞分支: markDirty G2 + begin (在飞 IO_PENDING 仍在跑, phase != CLEAN).
        state.markDirty();          // gen=2
        ChunkSnapshot g2 = snapshotForGeneration(state, 2L);
        state.beginPendingSnapshot(g2);
        assertEquals(ChunkSaveState.Phase.IO_PENDING, state.phase(),
                "begin 时在飞 IO 仍在跑, phase=IO_PENDING");
        assertFalse(state.slot().pendingNoInFlightConsumer(),
                "begin 在 phase != CLEAN (在飞消费者仍在) 时不得立 noInFlightConsumer 标记");

        // 在飞 G1 land (begin 之后): REQUEUE_DIRTY 见 PREPARING 标本周期 missed 离开, 不取未就绪 tag.
        ChunkSaveState.LandResult land = state.landAndTake();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, land.outcome(),
                "G2 已推进, 在飞 G1 落地 REQUEUE_DIRTY");
        assertNull(land.relayPending(),
                "PREPARING 未就绪, land 不取走 tag (relayPending 为 null)");
        assertEquals(c1, state.slot().missedCycle(),
                "land 见 PREPARING 标本周期 (C1) missed");
        assertTrue(state.hasPendingSnapshot(), "PREPARING 仍挂在槽 (回调未消费)");

        // publish: 经 missedCycle==inFlightCycleSeq 自踢 (既有安全序触发点), 不依赖新 marker.
        ChunkSnapshot toReoffer = (ChunkSnapshot) state.publishPendingSnapshot();
        assertSame(g2, toReoffer,
                "begin 先序: publish 经既有 missedCycle 触发点自踢, 行为不变");
        assertFalse(state.hasPendingSnapshot(), "自踢后槽 NONE");

        // 接力链经自踢落盘 G2, gauge 配平.
        state.reenterSerializingForPending(toReoffer.capturedGeneration());
        state.enterIoPending();
        ChunkSaveState.LandResult relay = state.landAndTake();
        assertEquals(ChunkSaveState.IoOutcome.CLEAN_LANDED, relay.outcome(),
                "自踢接力落地 CLEAN_LANDED");
        if (state.lastTransitionClearedMustDrain()) {
            gauge.decrementAndGet();
        }
        assertEquals(0L, gauge.get(), "反序安全路径 gauge 同样配平归零");
        assertFalse(state.mustDrain());
        assertFalse(state.hasPendingSnapshot());
    }
}
