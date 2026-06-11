package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.state.EntitySaveState;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
 * entity 在途碰撞 + 卸载的接力快照重投端到端回归 (C-entity-unload-collision).
 *
 * <p>现场: vanilla processChunkUnload 在 storeEntities 后立即把实体驱逐出内存且该坐标永不再 storeEntities,
 * 故碰撞那次 chunkEntities 是最新实体列表的唯一副本。旧逻辑 ci.cancel 丢弃它信任在飞旧代, 实体增量
 * (新放的命名生物/盔甲架等) 永久静默丢失。修复: mixin 碰撞分支显式 markDirty 推 generation (entity 无
 * setUnsaved 等价驱动源) + 纯 capture 登记接力槽, 在飞那代落地 REQUEUE_DIRTY 时回调取出重投。
 *
 * <p>关键非对称回归点: 若碰撞分支不 markDirty, 在飞那代落地 generation==inFlightGeneration 误判
 * CLEAN_LANDED (假成功 + 误 evict), 接力链断 —— 本测试断言在飞那代落地必判 REQUEUE_DIRTY。
 *
 * <p>测试技法同 ChunkPendingRelayTest: fake IoSubmitter (可控 future) + fake PendingReoffer (同一
 * submitter 包新 task 立即 execute)。
 */
class EntityPendingRelayTest {

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

    /** 每代一个独立 tag (worker assemble 后), 这里直接构 snapshot, entitiesNbt 以 gen 标记代号。 */
    private EntitySnapshot snapshotForGeneration(EntitySaveState state, long generation) {
        ListTag entities = new ListTag();
        CompoundTag marker = new CompoundTag();
        marker.putLong("gen", generation);
        entities.add(marker);
        return new EntitySnapshot(new ChunkPos(3, -5), DIM, 3700, entities, generation, state);
    }

    private long submittedGeneration(CompoundTag tag) {
        // EntityNbtAssembler 把 entitiesNbt 放进 outer tag 的 "Entities" 列表.
        ListTag entities = tag.getList("Entities", 10);
        return entities.getCompound(0).getLong("gen");
    }

    /**
     * 同坐标第一次 future 未完成时第二次 (卸载) storeEntities 撞在途登记接力, 第一次完成后接力把最新代落盘。
     */
    @Test
    void second_unload_store_while_first_future_incomplete_relays_latest_generation() {
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        CompletableFuture<Void> firstFuture = new CompletableFuture<>();
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        futures.add(firstFuture);
        List<CompoundTag> submittedTags = new ArrayList<>();
        EntitySaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        EntitySaveTask.PendingReoffer[] reofferHolder = new EntitySaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            // 复刻生产 reoffer sink: 真正接力前 inc serializing (与 relay execute 首行 dec 配平).
            metrics.incInFlightSerializing();
            EntitySaveTask relay = new EntitySaveTask(pending, metrics, submitter, null, reofferHolder[0]);
            relay.execute();
        };

        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter, null, reofferHolder[0]);
        task.execute();             // gen=1 IO 在飞

        // mixin 碰撞分支: entity 必须显式 markDirty 推 generation (无 setUnsaved 驱动源) + 登记 pending.
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        EntitySnapshot gen2Pending = snapshotForGeneration(state, 2L);
        state.registerPendingSnapshot(gen2Pending);

        firstFuture.complete(null); // gen=1 落地 -> REQUEUE_DIRTY -> 接力 gen=2

        CompoundTag lastSubmitted = submittedTags.get(submittedTags.size() - 1);
        assertEquals(2L, submittedGeneration(lastSubmitted),
                "entity 接力必须把碰撞后最新代 (gen=2) 落盘, 而非信任在飞旧代 (gen=1)");
        assertEquals(EntitySaveState.Phase.CLEAN, state.phase(), "接力落地后 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "接力链落地后 mustDrain 清零");
        assertFalse(state.hasPendingSnapshot(), "pending 已被消费, 槽清空");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
    }

    /**
     * 关键非对称回归: 碰撞推进 generation 后, 在飞那代落地必须判 REQUEUE_DIRTY (非 CLEAN_LANDED),
     * 否则误判假成功 + 误 evict, 接力链断。直接用状态机断言 (entity 侧无 setUnsaved 等价门, markDirty
     * 是唯一 generation 驱动)。
     */
    @Test
    void collision_markdirty_makes_inflight_landing_requeue_not_clean() {
        EntitySaveState state = new EntitySaveState(0L, "minecraft:overworld", 1L);
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        state.enterIoPending();

        // 碰撞: mixin 必须 markDirty 推 gen (否则下面误判 CLEAN_LANDED).
        state.markDirty();          // gen=2

        assertEquals(EntitySaveState.IoOutcome.REQUEUE_DIRTY, state.ioCompletedSuccessfully(),
                "碰撞 markDirty 后在飞代落地必须 REQUEUE_DIRTY, 否则假 CLEAN_LANDED 误 evict 断接力链");
    }

    /**
     * 隐角 A 三代链 (entity): capture G -> 碰撞 G+1 登记 pending -> pending 在飞中再碰撞 G+2 覆盖 ->
     * 最终落盘 G+2。
     */
    @Test
    void three_generation_collision_chain_lands_final_generation() {
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        CompletableFuture<Void> gen1Future = new CompletableFuture<>();
        CompletableFuture<Void> relayFuture = new CompletableFuture<>();
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        futures.add(gen1Future);
        futures.add(relayFuture);
        List<CompoundTag> submittedTags = new ArrayList<>();
        EntitySaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        EntitySaveTask.PendingReoffer[] reofferHolder = new EntitySaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            // 复刻生产 reoffer sink: 真正接力前 inc serializing (与 relay execute 首行 dec 配平).
            metrics.incInFlightSerializing();
            EntitySaveTask relay = new EntitySaveTask(pending, metrics, submitter, null, reofferHolder[0]);
            relay.execute();
        };

        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter, null, reofferHolder[0]);
        task.execute();             // gen=1 IO 在飞

        // 碰撞 G+1.
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        state.registerPendingSnapshot(snapshotForGeneration(state, 2L));
        // 碰撞 G+2 覆盖.
        state.markDirty();          // gen=3
        EntitySnapshot gen3Pending = snapshotForGeneration(state, 3L);
        state.registerPendingSnapshot(gen3Pending);

        gen1Future.complete(null);  // gen=1 落地 -> 取 pending(gen=3) 接力, 锁 inFlightGeneration=3
        relayFuture.complete(null); // 接力 gen=3 落地 CLEAN_LANDED

        CompoundTag lastSubmitted = submittedTags.get(submittedTags.size() - 1);
        assertEquals(3L, submittedGeneration(lastSubmitted),
                "entity 三代链最终落盘必须是 G+2 (gen=3, 最新者胜)");
        assertSame(gen3Pending.entitiesNbt(), lastSubmitted.getList("Entities", 10),
                "最终落盘的是 gen=3 pending 的 entitiesNbt 实例");
        assertEquals(EntitySaveState.Phase.CLEAN, state.phase());
        assertFalse(state.mustDrain());
        assertFalse(state.hasPendingSnapshot());

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
    }

    /**
     * M-unhandled-abandons-pending (entity 侧, 与 chunk 对称): onUnhandledError 撞上非空 pending 必须接力
     * 重投最新代。entity 侧更严重 (无坐标恢复队列), 漏接力即静默永久丢失。
     *
     * <p>判定标准 (删修复必挂): 删 onUnhandledError 的 takePendingSnapshot + reoffer 分支 -> gen=2 未提交,
     * 接力断言挂; hasPendingSnapshot 残留 true。
     */
    @Test
    void unhandled_error_with_pending_relays_latest_generation() {
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();
        assertTrue(state.tryMarkMustDrain());

        // relay IO 用手动 future, 接力后保持在途, 以便在 "relay 在途" 状态点断言不变式.
        List<CompoundTag> submittedTags = new ArrayList<>();
        CompletableFuture<Void> relayFuture = new CompletableFuture<>();
        EntitySaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            return relayFuture;
        };
        EntitySaveTask.PendingReoffer[] reofferHolder = new EntitySaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            metrics.incInFlightSerializing();
            EntitySaveTask relay = new EntitySaveTask(pending, metrics, submitter, null, reofferHolder[0]);
            relay.execute();
        };

        state.markDirty();          // gen=2 (碰撞登记)
        EntitySnapshot gen2Pending = snapshotForGeneration(state, 2L);
        state.registerPendingSnapshot(gen2Pending);

        // 在飞 task worker execute 抛非受控异常 -> onUnhandledError. 先 dec 它的 serializing inc.
        metrics.decInFlightSerializing();
        EntitySaveTask inFlightTask = new EntitySaveTask(gen1, metrics, submitter, null, reofferHolder[0]);
        inFlightTask.onUnhandledError(new RuntimeException("assemble boom"));

        // 不变式断言点 (relay 在途): entity 侧旧码同样无条件清 mustDrain 不取 pending; 回退则两断言挂.
        assertEquals(1, submittedTags.size(), "接力必须提交一次最新代 IO");
        assertEquals(2L, submittedGeneration(submittedTags.get(0)),
                "entity onUnhandledError 必须接力把碰撞后最新代 (gen=2) 落盘, 而非静默丢弃");
        assertTrue(state.mustDrain(),
                "onUnhandledError 接力 relay 仍在途时 mustDrain 必须维持真 (不变式: 在途 -> mustDrain)");
        assertFalse(state.hasPendingSnapshot(), "pending 已被接力 take 走, 槽清空");

        relayFuture.complete(null);
        assertEquals(EntitySaveState.Phase.CLEAN, state.phase(), "接力落地后 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "接力链落地后 mustDrain 归零");
        assertFalse(state.hasPendingSnapshot());

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
    }

    /**
     * M-unhandled-abandons-pending 安全网 (entity): 有 pending 但 sink 不可达 (null) 时取走 pending 清槽
     * + 清 mustDrain + 配平 gauge。
     */
    @Test
    void unhandled_error_with_pending_but_no_sink_clears_slot_and_must_drain() {
        EntitySaveState state = new EntitySaveState(0L, "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();

        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        state.markDirty();
        state.registerPendingSnapshot(snapshotForGeneration(state, 2L));

        // pendingReoffer = null (3 参构造).
        EntitySaveTask task = new EntitySaveTask(gen1, metrics, tag -> CompletableFuture.completedFuture(null));
        task.onUnhandledError(new RuntimeException("boom"));

        assertFalse(state.hasPendingSnapshot(), "sink 不可达也必须取走 pending 清空槽 (防永久泄漏)");
        assertFalse(state.mustDrain(), "无接力可投时必须清 mustDrain");
        assertEquals(0L, metrics.snapshot().mustDrainPending(), "mustDrain gauge 配平归零");
    }

    /**
     * C-dispatch-register-toctou (entity 对称项): 碰撞分支 markDirty 后 capturePending 抛 (OOM 等), 尚未登记
     * pending。在飞旧代落地必判 REQUEUE_DIRTY (markDirty 已推 generation) 永不清 mustDrain, 槽空回调取 null
     * 不重投 -> 此后无路径清 mustDrain。故 capture-throw 的 catch 必须亲自 compareAndClearMustDrain 配平 gauge,
     * 与 chunk 路径同构。
     *
     * <p>判定标准 (删修复必挂): 删 entity mixin catch 的 compareAndClearMustDrain -> 在飞旧代落地后 mustDrain
     * 永真, mustDrainPending gauge 永久正偏移 (本断言 mustDrainPending()==0 挂)。
     */
    @Test
    void capture_throw_undo_balances_must_drain() {
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        CompletableFuture<Void> gen1Future = new CompletableFuture<>();
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        futures.add(gen1Future);
        List<CompoundTag> submittedTags = new ArrayList<>();
        EntitySaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        EntitySaveTask.PendingReoffer[] reofferHolder = new EntitySaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            metrics.incInFlightSerializing();
            EntitySaveTask relay = new EntitySaveTask(pending, metrics, submitter, null, reofferHolder[0]);
            relay.execute();
        };

        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter, null, reofferHolder[0]);
        task.execute();             // gen=1 IO 在飞 (gen1Future 未完成)

        // mixin 碰撞分支: markDirty 推 gen -> tryMark(inc gauge) -> capturePending 抛 -> catch 自我撤销清 mustDrain.
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        assertEquals(1L, metrics.snapshot().mustDrainPending());
        // capturePending 抛: 未 register, 槽空。catch 配平 (复刻 mixin catch 的 compareAndClearMustDrain).
        if (state.compareAndClearMustDrain()) {
            metrics.decMustDrainPending();
        }
        assertFalse(state.mustDrain(), "capture 抛自我撤销必须清 mustDrain");
        assertEquals(0L, metrics.snapshot().mustDrainPending(), "撤销后 gauge 配平归零");
        assertFalse(state.hasPendingSnapshot(), "capture 抛未 register, 槽本就空");

        // 在飞旧代落地: generation(2) != inFlightGeneration(1) -> REQUEUE_DIRTY, 取 null 不重投, 不清 mustDrain.
        gen1Future.complete(null);
        assertEquals(1, submittedTags.size(), "撤销后无接力重投 (只提交过 gen=1)");
        assertEquals(EntitySaveState.Phase.DIRTY, state.phase(), "在飞旧代 REQUEUE_DIRTY 后 phase=DIRTY");
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

    /**
     * 隐角 C 关服残窗 ERROR 安全网: worker 已停 (joinWorkers 置 workersStopping) 后迟到的接力重投不得
     * 沉默 offer 进无人消费的死队列, 也不得泄漏 serializing gauge。本测试用真实 SnapshotPipeline 的公开
     * entityPendingReoffer sink: 先 joinWorkers(0) (无 worker, 仅置位 workersStopping), 再调 sink,
     * 断言队列保持空 + serializing gauge 不变 (走 ERROR 安全网而非 inc+offer)。
     *
     * <p>判定标准: 删 sink 的 workersStopping ERROR 分支 -> sink 改 inc+offer, 队列非空 + gauge=1, 两断言挂。
     */
    @Test
    void relay_after_workers_stopped_takes_error_safety_net_not_silent_offer() {
        SaveMetrics metrics = new SaveMetrics();
        // scheduler / ioBridge 传 null: joinWorkers 与 entityPendingReoffer 的 workersStopping 分支都不触碰
        // 这两个协作者 (SaveScheduler 在单测环境构造会因未初始化 config 抛异常, 同 SnapshotPipelineDegradedTest)。
        SnapshotPipeline pipeline = new SnapshotPipeline(null, null, metrics);

        // 关服: 无 worker 线程, joinWorkers 仅置 workersStopping=true 并立即返回.
        pipeline.joinWorkers(0L);

        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        EntitySnapshot pending = snapshotForGeneration(state, 9L);
        // 迟到接力: worker 已停, sink 必须走 ERROR 安全网.
        pipeline.entityPendingReoffer(null, null).reoffer(pending);

        assertEquals(0, pipeline.entityWorkerQueue().size(),
                "worker 已停后接力不得 offer 进死队列 (走 ERROR 安全网)");
        assertEquals(0L, metrics.snapshot().inFlightSerializing(),
                "ERROR 安全网路径不得 inc serializing (无 task 会 dec, 否则泄漏毒化 drainPending)");
    }
}
