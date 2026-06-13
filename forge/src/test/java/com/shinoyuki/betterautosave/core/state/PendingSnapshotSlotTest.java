package com.shinoyuki.betterautosave.core.state;

import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.snapshot.ChunkSnapshot;
import com.shinoyuki.betterautosave.core.snapshot.EntitySnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接力快照槽 (pendingSnapshot) 状态机语义 + gauge 不变式回归.
 *
 * <p>覆盖:
 * - 覆盖语义 (最新者胜): 槽已占用再登记 = 直接替换, takePendingSnapshot 取走清空;
 * - reenterSerializingForPending 把 inFlightGeneration 锁到 pending 自己的代 (接力落地的代推进基元);
 * - gauge 不变式: pendingSnapshot 非空 或 在途 IO 时 mustDrain 恒真 —— 逐条复刻碰撞登记/接力落地序列,
 *   断言任一时刻 (mustDrain==false) 蕴含 (pending==null 且 phase 非在途)。
 */
class PendingSnapshotSlotTest {

    private static ResourceKey<Level> DIM;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DIM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation("minecraft", "overworld"));
    }

    private ChunkSnapshot chunkSnapshot(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(1, 1), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    private EntitySnapshot entitySnapshot(EntitySaveState state, long generation) {
        ListTag entities = new ListTag();
        return new EntitySnapshot(new ChunkPos(1, 1), DIM, 3700, entities, generation, state);
    }

    // ===== 覆盖语义: 最新者胜 =====

    @Test
    void chunk_register_pending_latest_wins() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        assertFalse(state.hasPendingSnapshot(), "初始无 pending");

        // registerReadyPendingSnapshot = 无 dispatch 窗口的原子就绪登记 (READY 态), 复刻覆盖语义最新者胜。
        ChunkSnapshot genA = chunkSnapshot(state, 5L);
        state.registerReadyPendingSnapshot(genA);
        assertTrue(state.hasPendingSnapshot());

        // 槽已占用再登记 -> 直接替换, 旧 pending 作废 (它是更新 pending 的真子集).
        ChunkSnapshot genB = chunkSnapshot(state, 7L);
        state.registerReadyPendingSnapshot(genB);

        ChunkSnapshot taken = (ChunkSnapshot) state.takeReadyPendingSnapshot();
        assertSame(genB, taken, "takeReadyPendingSnapshot 必须返回最新登记的那份 (最新者胜)");
        assertNull(state.takeReadyPendingSnapshot(), "取走后槽必须清空");
        assertFalse(state.hasPendingSnapshot());
    }

    @Test
    void entity_register_pending_latest_wins() {
        EntitySaveState state = new EntitySaveState(0L, "minecraft:overworld", 1L);
        EntitySnapshot genA = entitySnapshot(state, 3L);
        EntitySnapshot genB = entitySnapshot(state, 4L);
        state.registerPendingSnapshot(genA);
        state.registerPendingSnapshot(genB);

        assertSame(genB, state.takePendingSnapshot(), "entity 槽同样最新者胜");
        assertNull(state.takePendingSnapshot());
    }

    // ===== reenter 锁 pending 自身代: reenterSerializingForPending 锁 pending 自己的代 =====

    @Test
    void chunk_reenter_locks_pending_generation_not_current() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        state.enterIoPending();
        state.markDirty();          // gen=2 (碰撞)
        state.markDirty();          // gen=3 (再碰撞)

        // 接力快照在 gen=2 时被纯 capture (capturedGeneration=2), 但此刻 state.generation()=3.
        // reenter 必须锁到 pending 自己的代 (2), 而非当前代 (3).
        state.reenterSerializingForPending(2L);
        assertEquals(ChunkSaveState.Phase.SERIALIZING, state.phase());
        assertEquals(2L, state.inFlightGeneration(),
                "reenter 必须把 inFlightGeneration 锁到 pending 的代 (2), 不是当前 generation (3)");

        // 接力 IO 落地: generation(3) != inFlightGeneration(2) -> REQUEUE_DIRTY (还有更新 pending 待接力).
        state.enterIoPending();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, state.ioCompletedSuccessfully(),
                "pending 代落地时若 generation 已更前进, 必须判 REQUEUE_DIRTY 续接力");
    }

    @Test
    void chunk_reenter_lands_clean_when_no_newer_edit() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        state.enterIoPending();
        state.markDirty();          // gen=2 (唯一一次碰撞)

        // 接力快照 capturedGeneration=2, 之后无更新编辑.
        state.reenterSerializingForPending(2L);
        state.enterIoPending();
        assertEquals(ChunkSaveState.IoOutcome.CLEAN_LANDED, state.ioCompletedSuccessfully(),
                "pending 代 == 当前 generation 时接力落地必须 CLEAN_LANDED 收口");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase());
    }

    // ===== gauge 不变式: pendingSnapshot 非空 或 在途 IO -> mustDrain 恒真 =====

    /**
     * 逐条复刻"碰撞登记 -> 在飞落地 -> 接力 -> 接力落地"全序列, 在每个状态点断言不变式:
     * (mustDrain == false) 蕴含 (pendingSnapshot == null 且 phase 不在 SNAPSHOTTING/SERIALIZING/IO_PENDING)。
     * 等价于: 只要 pending 非空 或 在途 IO, mustDrain 必为真。删任一处 mustDrain 维持/置位则某点不变式挂。
     */
    @Test
    void chunk_gauge_invariant_pending_or_inflight_implies_must_drain() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);

        // 1) 首次 dispatch: markDirty -> snapshot -> serialize -> ioPending, mixin 置 mustDrain.
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();
        assertTrue(state.tryMarkMustDrain());
        assertInvariant(state, "首次在途 IO");

        // 2) 碰撞: 主线程 markDirty 推 gen, mustDrain 已置, 登记接力快照 (无 dispatch 窗口的就绪登记 = READY).
        state.markDirty();
        state.registerReadyPendingSnapshot(chunkSnapshot(state, state.generation()));
        assertInvariant(state, "碰撞登记 pending 后 (在途 IO + pending 双非空)");

        // 3) 在飞那代落地 REQUEUE_DIRTY: 不清 mustDrain. 回调取 pending 重投.
        ChunkSaveState.IoOutcome landed = state.ioCompletedSuccessfully();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, landed);
        assertFalse(state.lastTransitionClearedMustDrain(), "REQUEUE_DIRTY 不得清 mustDrain");
        ChunkSnapshot pending = (ChunkSnapshot) state.takeReadyPendingSnapshot();
        assertInvariant(state, "取走 pending 但即将重投 (phase 仍 DIRTY, 但 mustDrain 维持)");

        // 4) 接力重投: reenter 锁 pending 代 + ioPending. mustDrain 维持.
        state.reenterSerializingForPending(pending.capturedGeneration());
        state.enterIoPending();
        assertInvariant(state, "接力在途 IO");

        // 5) 接力落地 CLEAN_LANDED: 此时无更新编辑, 终态清 mustDrain.
        ChunkSaveState.IoOutcome relayLanded = state.ioCompletedSuccessfully();
        assertEquals(ChunkSaveState.IoOutcome.CLEAN_LANDED, relayLanded);
        assertTrue(state.lastTransitionClearedMustDrain(), "终态 CLEAN_LANDED 必须清 mustDrain");
        assertFalse(state.mustDrain(), "接力链落地后 mustDrain 必须归 false");
        assertFalse(state.hasPendingSnapshot(), "落地后无残留 pending");
        assertInvariant(state, "接力链全部落地后 (无在途无 pending, mustDrain 已清)");
    }

    /** 不变式断言: mustDrain==false 蕴含 pending==null 且 phase 非在途。 */
    private static void assertInvariant(ChunkSaveState state, String at) {
        if (!state.mustDrain()) {
            assertFalse(state.hasPendingSnapshot(),
                    at + ": mustDrain=false 时不得有 pending (不变式: pending 非空 -> mustDrain 真)");
            ChunkSaveState.Phase p = state.phase();
            boolean inFlight = p == ChunkSaveState.Phase.SNAPSHOTTING
                    || p == ChunkSaveState.Phase.SERIALIZING
                    || p == ChunkSaveState.Phase.IO_PENDING;
            assertFalse(inFlight,
                    at + ": mustDrain=false 时不得在途 (不变式: 在途 IO -> mustDrain 真)");
        }
    }

    /**
     * 用 AtomicLong gauge 复刻 mixin inc / 终态 dec, 断言接力链全程 gauge 与 boolean 真值一致,
     * 且链终落地后归零 (不泄漏正偏移)。
     */
    @Test
    void chunk_must_drain_gauge_balances_across_relay_chain() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        AtomicLong gauge = new AtomicLong();

        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();
        if (state.tryMarkMustDrain()) {
            gauge.incrementAndGet();
        }
        assertEquals(1L, gauge.get());

        // 碰撞登记 pending (mustDrain 已是 true, tryMark 返 false 不重复 inc).
        state.markDirty();
        if (state.tryMarkMustDrain()) {
            gauge.incrementAndGet();
        }
        state.registerReadyPendingSnapshot(chunkSnapshot(state, state.generation()));
        assertEquals(1L, gauge.get(), "碰撞登记不得重复 inc gauge");

        // 在飞落地 REQUEUE_DIRTY: 不 dec. 接力重投.
        state.ioCompletedSuccessfully();
        if (state.lastTransitionClearedMustDrain()) {
            gauge.decrementAndGet();
        }
        assertEquals(1L, gauge.get(), "REQUEUE_DIRTY 不得 dec gauge");
        ChunkSnapshot pending = (ChunkSnapshot) state.takeReadyPendingSnapshot();
        state.reenterSerializingForPending(pending.capturedGeneration());
        state.enterIoPending();

        // 接力落地 CLEAN_LANDED: 终态 dec.
        state.ioCompletedSuccessfully();
        if (state.lastTransitionClearedMustDrain()) {
            gauge.decrementAndGet();
        }
        assertEquals(0L, gauge.get(), "接力链终落地后 mustDrain gauge 必须配平归零");
        assertFalse(state.mustDrain());
    }
}
