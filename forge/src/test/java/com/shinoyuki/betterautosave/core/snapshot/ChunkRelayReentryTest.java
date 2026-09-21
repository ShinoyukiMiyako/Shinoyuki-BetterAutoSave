package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.worker.WorkerThreadAssert;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在飞那代 IO 落地取走 READY 接力时, ChunkSaveTask 交给重投 sink 的 state 必须已经处于接力周期。
 *
 * <p>生产的 ChunkSaveTask.onIoSuccess 曾先调 landAndTake (取走接力并置 DIRTY), 再另调
 * reenterSerializingForPending 进接力周期; 两次 CAS 之间 phase=DIRTY 可被主线程 ChunkMap.save 观测, 而碰撞登记的
 * 纯 capture 不清 isUnsaved, 主线程会据此开新周期与接力双在飞。
 *
 * <p><b>守护范围</b>: 本类守的是 ChunkSaveTask 的接线 —— 必须给 landAndTake 传 canReoffer=true, 且不得再另调一次
 * reenter。"取走与进接力周期在同一 CAS 内完成" 这一原子性本身由 common 模块的
 * {@code ChunkTransientDirtyWindowTest} 在原语层守护: 任务层在两次 CAS 之间没有可观测的缝, 旧写法
 * (landAndTake 置 DIRTY + 随后另调 reenter) 到 sink 被调用时状态字与新写法相同, 本类区分不了。
 */
class ChunkRelayReentryTest {

    private static ResourceKey<Level> DIM;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DIM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation("minecraft", "overworld"));
    }

    @BeforeEach
    void setup() {
        WorkerThreadAssert.markCurrentThreadAsWorker();
    }

    @AfterEach
    void teardown() {
        WorkerThreadAssert.unmarkCurrentThreadAsWorker();
    }

    private ChunkSnapshot snapshotForGeneration(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    /**
     * 判定标准: onIoSuccess 若给 landAndTake 传 false 且不补 reenter -> sink 看到的 phase 是 DIRTY, 断言挂;
     * 若在 landAndTake(true) 之后再另调一次 reenterSerializingForPending -> 周期序号前进 2, 断言挂。
     */
    @Test
    void io_success_hands_the_reoffer_sink_a_state_already_in_the_relay_cycle() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        CompletableFuture<Void> firstFuture = new CompletableFuture<>();
        futures.add(firstFuture);
        List<CompoundTag> submittedTags = new ArrayList<>();
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };

        ChunkSaveState.SlotWord[] atReoffer = new ChunkSaveState.SlotWord[1];
        ChunkSaveTask.PendingReoffer[] reofferHolder = new ChunkSaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            if (atReoffer[0] == null) {
                atReoffer[0] = state.slot();
            }
            metrics.incInFlightSerializing();
            new ChunkSaveTask(pending, metrics, null, recoveryQueue, submitter, reofferHolder[0]).execute();
        };

        new ChunkSaveTask(gen1, metrics, null, recoveryQueue, submitter, reofferHolder[0]).execute();

        // gen=1 在飞时的碰撞: 编辑推到 gen=2 并登记就绪接力。
        state.markDirty();
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        state.registerReadyPendingSnapshot(snapshotForGeneration(state, 2L));
        long cycleBeforeLand = state.slot().inFlightCycleSeq();

        firstFuture.complete(null);

        assertNotNull(atReoffer[0], "gen=1 落地判 REQUEUE_DIRTY 且槽为 READY, 必须把接力交给 sink");
        assertEquals(ChunkSaveState.Phase.SERIALIZING, atReoffer[0].phase(),
                "交给 sink 之前必须已进入接力周期, 不得停在 DIRTY (ChunkSaveTask 须给 landAndTake 传 canReoffer=true)");
        assertEquals(2L, atReoffer[0].inFlightGeneration(), "接力周期锁的是接力快照自己的代");
        assertEquals(ChunkSaveState.DrainOwner.RELAY, atReoffer[0].drainOwner());
        assertEquals(cycleBeforeLand + 1, atReoffer[0].inFlightCycleSeq(), "接力恰好开一个新周期, 不重复重入");
        assertEquals(ChunkSaveState.PendingKind.NONE, atReoffer[0].pendingKind());

        assertEquals(2L, submittedTags.get(submittedTags.size() - 1).getLong("gen"), "接力把最新代落盘");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase());
        assertFalse(state.mustDrain());
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.mustDrainPending(), "mustDrain gauge 由接力终态唯一一次 dec 配平");
        assertEquals(0L, snap.inFlightIoPending());
        assertEquals(0L, snap.inFlightSerializing());
    }
}
