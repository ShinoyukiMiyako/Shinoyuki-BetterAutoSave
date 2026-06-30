package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.state.EntitySaveState;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.core.worker.WorkerThreadAssert;
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
 * entity 在途碰撞 + 卸载的接力快照重投端到端回归.
 *
 * <p>现场: vanilla processChunkUnload 在 storeEntities 后立即把实体驱逐出内存且该坐标永不再 storeEntities,
 * 故碰撞那次 chunkEntities 是最新实体列表的唯一副本。若 ci.cancel 丢弃它而信任在飞旧代, 实体增量
 * (新放的命名生物/盔甲架等) 将永久静默丢失。因此 mixin 碰撞分支显式 markDirty 推 generation (entity 无
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
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
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
     * 三代链 (entity): capture G -> 碰撞 G+1 登记 pending -> pending 在飞中再碰撞 G+2 覆盖 ->
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
     * entity 侧 (与 chunk 对称): onUnhandledError 撞上非空 pending 必须接力
     * 重投最新代。entity 侧更严重 (无坐标恢复队列), 漏接力即静默永久丢失。
     *
     * <p>判定标准: 删 onUnhandledError 的 takePendingSnapshot + reoffer 分支 -> gen=2 未提交,
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

        // 不变式断言点 (relay 在途): onUnhandledError 接力取走 pending 并维持 mustDrain, 故下面两断言成立.
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
     * onUnhandledError 安全网 (entity): 有 pending 但 sink 不可达 (null) 时取走 pending 清槽
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
        // 安全网 REQUEUE 无后续 submitIo 重建 phase。ioFailed 不再写 DIRTY, 故安全网必须自行
        // markNoInFlightDirty 把 phase 从进入时的 SERIALIZING 推到 DIRTY, 否则 state 永卡在飞态。
        // 删 markNoInFlightDirty -> phase 停 SERIALIZING, 本断言挂。
        assertEquals(EntitySaveState.Phase.DIRTY, state.phase(),
                "安全网 REQUEUE 必须发布真终态 DIRTY (无在飞消费者, 下周期重新捕获), 不得停在 SERIALIZING");
    }

    /**
     * entity 对称项: 碰撞分支 markDirty 后 capturePending 抛 (OOM 等), 尚未登记
     * pending。在飞旧代落地必判 REQUEUE_DIRTY (markDirty 已推 generation) 永不清 mustDrain, 槽空回调取 null
     * 不重投 -> 此后无路径清 mustDrain。故 capture-throw 的 catch 必须亲自 compareAndClearMustDrain 配平 gauge,
     * 与 chunk 路径同构。
     *
     * <p>判定标准: 删 entity mixin catch 的 compareAndClearMustDrain -> 在飞旧代落地后 mustDrain
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

    /**
     * store 同步抛 (entity): store 同步抛必须走 onIoFailure 补偿, 不静默落进被丢弃的
     * dependent future。第一次 store 同步抛 -> REQUEUE_DIRTY 重投, 第二次成功。
     *
     * <p>判定标准 (删 submitIo 自包 try 必挂): ioPending gauge 永久 +1, phase 卡 IO_PENDING。
     */
    @Test
    void store_synchronous_throw_is_compensated_and_retried() {
        EntitySaveState state = new EntitySaveState(0L, "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        java.util.concurrent.atomic.AtomicInteger submitCount = new java.util.concurrent.atomic.AtomicInteger();
        EntitySaveTask.IoSubmitter submitter = tag -> {
            int n = submitCount.incrementAndGet();
            if (n == 1) {
                throw new RuntimeException("entity mailbox teardown surrogate");
            }
            return CompletableFuture.completedFuture(null);
        };

        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter);
        task.execute();

        assertEquals(2, submitCount.get(), "store 同步抛走 onIoFailure REQUEUE_DIRTY 重投 (第 2 次成功)");
        assertEquals(EntitySaveState.Phase.CLEAN, state.phase(), "补偿后重投成功 phase 回 CLEAN");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightIoPending(), "store 同步抛后 ioPending gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
    }

    /**
     * reoffer 同步抛 (entity): REQUEUE_DIRTY 接力时 reoffer sink 同步抛, safeReoffer 必须清
     * mustDrain + 配平 gauge (serializing 由 sink 自身 inc/offer 自包 try 配平)。entity 无坐标恢复, 漏防即静默丢失。
     *
     * <p>判定标准 (删 safeReoffer 必挂): mustDrain 永真 + mustDrainPending gauge 泄漏。
     */
    @Test
    void reoffer_synchronous_throw_balances_must_drain_and_serializing() {
        EntitySaveState state = new EntitySaveState(0L, "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        CompletableFuture<Void> gen1Future = new CompletableFuture<>();
        Deque<CompletableFuture<Void>> futures = new ArrayDeque<>();
        futures.add(gen1Future);
        EntitySaveTask.IoSubmitter submitter = tag -> {
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        // 复刻 SnapshotPipeline sink 自包 try: 先 inc, 抛前 dec 回, 再上抛交 safeReoffer 清 mustDrain.
        EntitySaveTask.PendingReoffer throwingReoffer = pending -> {
            metrics.incInFlightSerializing();
            try {
                throw new RuntimeException("entity relay sink teardown surrogate");
            } catch (Throwable t) {
                metrics.decInFlightSerializing();
                throw t;
            }
        };

        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter, null, throwingReoffer);
        task.execute();             // gen=1 IO 在飞

        state.markDirty();          // gen=2 碰撞登记
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        state.registerPendingSnapshot(snapshotForGeneration(state, 2L));

        gen1Future.complete(null);  // gen=1 落地 REQUEUE_DIRTY -> 取 pending -> safeReoffer -> sink 抛 -> 补偿

        assertFalse(state.hasPendingSnapshot(), "reoffer 抛后 pending 已 take 走, 槽空");
        assertFalse(state.mustDrain(), "safeReoffer 必须清 mustDrain");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.mustDrainPending(), "mustDrain gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零 (sink 自身 dec)");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
    }

    /**
     * 关服残窗 ERROR 安全网: worker 已停 (joinWorkers 置 workersStopping) 后迟到的接力重投不得
     * 沉默 offer 进无人消费的死队列, 也不得泄漏 serializing gauge。本测试用真实 SnapshotPipeline 的公开
     * entityPendingReoffer sink: 先 joinWorkers(0) (无 worker, 仅置位 workersStopping), 再调 sink,
     * 断言队列保持空 + serializing gauge 不变 (走 ERROR 安全网而非 inc+offer)。
     *
     * <p>同时覆盖 mustDrain 维度: 复刻碰撞登记 (state markMustDrain + gauge inc), 断言迟到接力走 ERROR
     * 安全网时真正清 mustDrain + 配平 gauge, 不留孤儿正偏移。
     *
     * <p>判定标准: 删 sink 的 workersStopping ERROR 分支 -> sink 改 inc+offer, 队列非空 + serializing gauge=1
     * 挂; 删 sink 内新加的 compareAndClearMustDrain -> mustDrain()==false 与 mustDrainPending()==0 挂。
     */
    @Test
    void relay_after_workers_stopped_takes_error_safety_net_and_clears_must_drain() {
        SaveMetrics metrics = new SaveMetrics();
        // scheduler / ioBridge 传 null: joinWorkers 与 entityPendingReoffer 的 workersStopping 分支都不触碰
        // 这两个协作者 (SaveScheduler 在单测环境构造会因未初始化 config 抛异常, 同 SnapshotPipelineDegradedTest)。
        SnapshotPipeline pipeline = new SnapshotPipeline(null, null, metrics);

        // 关服: 无 worker 线程, joinWorkers 仅置 workersStopping=true 并立即返回.
        pipeline.joinWorkers(0L);

        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        EntitySnapshot pending = snapshotForGeneration(state, 9L);
        // 复刻碰撞登记: mixin 在飞碰撞分支置 mustDrain + inc gauge (槽非空 -> mustDrain 不变式).
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        assertEquals(1L, metrics.snapshot().mustDrainPending());

        // 迟到接力: worker 已停, sink 必须走 ERROR 安全网 + 清 mustDrain.
        pipeline.entityPendingReoffer(null, null).reoffer(pending);

        assertEquals(0, pipeline.entityWorkerQueue().size(),
                "worker 已停后接力不得 offer 进死队列 (走 ERROR 安全网)");
        assertEquals(0L, metrics.snapshot().inFlightSerializing(),
                "ERROR 安全网路径不得 inc serializing (无 task 会 dec, 否则泄漏毒化 drainPending)");
        assertFalse(state.mustDrain(),
                "关服残窗放弃接力时必须清 mustDrain (该接力是唯一会把 mustDrain 带向终态的路径)");
        assertEquals(0L, metrics.snapshot().mustDrainPending(),
                "mustDrain gauge 必须配平归零 (不留孤儿正偏移毒化诊断/Prometheus)");
    }

    /**
     * chunk 对称: chunk 接力 sink 的 workersStopping 残窗同样真正清
     * mustDrain + 配平 gauge。chunk 路径需 ServerLevel 才能构 task, 但 workersStopping 分支在构 task 之前
     * 早返回, 故 level 传 null 不触发 NPE (与 entity 对称用例同技法)。
     *
     * <p>判定标准: 删 chunk sink 的 compareAndClearMustDrain -> mustDrain()==false 与 mustDrainPending()==0 挂。
     */
    @Test
    void chunk_relay_after_workers_stopped_clears_must_drain() {
        SaveMetrics metrics = new SaveMetrics();
        SnapshotPipeline pipeline = new SnapshotPipeline(null, null, metrics);
        pipeline.joinWorkers(0L);

        com.shinoyuki.betterautosave.core.state.ChunkSaveState state =
                new com.shinoyuki.betterautosave.core.state.ChunkSaveState(
                        new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", 9L);
        ChunkSnapshot pending = ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, 9L, state,
                com.shinoyuki.betterautosave.config.ConfigSpec.EventCompatMode.FULL);
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();

        pipeline.chunkPendingReoffer(null).reoffer(pending);

        assertEquals(0, pipeline.chunkWorkerQueue().size(), "worker 已停后 chunk 接力不得 offer 进死队列");
        assertEquals(0L, metrics.snapshot().inFlightSerializing(), "ERROR 安全网不得 inc serializing");
        assertFalse(state.mustDrain(), "chunk 关服残窗放弃接力必须清 mustDrain");
        assertEquals(0L, metrics.snapshot().mustDrainPending(), "chunk mustDrain gauge 配平归零");
    }

    /**
     * c0 修复回归 (entity 终态接力): gen1 的 IO 反复失败到耗尽 maxRetries 走 FAILED_TERMINAL 时, 若碰撞
     * 已登记 gen2 pending, 必须接力把 gen2 重投落盘, 而非取走即丢。entity 无 vanilla 兜底 (实体已被驱逐出
     * 内存), gen2 是该坐标最新增量的唯一副本, 漏接力即永久静默丢失。与 chunk handleTerminalFailure CONSUMED 对称。
     *
     * <p>判定标准: 删 handleTerminalFailure 的接力分支 (回到取走即丢) -> gen2 从未提交 IO, submitCount 恒为
     * 4 且末次提交是 gen1 (gen=1), 下面 submitCount/末代号断言挂; entitiesCompleted 变 0、entitiesFailed 变 1。
     */
    @Test
    void terminal_io_failure_with_pending_relays_latest_generation() {
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        // maxRetries=3: gen1 attempt 1..3 inline 失败 REQUEUE 重投, attempt 4 用手动 future 维持在途, 以便
        // 在 "gen1 终态前" 登记 gen2; attempt 5 (gen2 接力) 成功落盘。
        CompletableFuture<Void> gen1FinalFuture = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger submitCount = new java.util.concurrent.atomic.AtomicInteger();
        List<CompoundTag> submittedTags = new ArrayList<>();
        EntitySaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            int n = submitCount.incrementAndGet();
            if (n <= 3) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new java.io.IOException("disk full attempt " + n));
                return failed;
            }
            if (n == 4) {
                return gen1FinalFuture;
            }
            return CompletableFuture.completedFuture(null);
        };
        EntitySaveTask.PendingReoffer[] reofferHolder = new EntitySaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            metrics.incInFlightSerializing();
            EntitySaveTask relay = new EntitySaveTask(pending, metrics, submitter, null, reofferHolder[0]);
            relay.execute();
        };

        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter, null, reofferHolder[0]);
        task.execute();             // gen1 attempt 1..4, 末次停在 gen1FinalFuture 在途

        // 碰撞登记 gen2 (gen1 仍在终态前的在途 attempt 4).
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        EntitySnapshot gen2Pending = snapshotForGeneration(state, 2L);
        state.registerPendingSnapshot(gen2Pending);

        // gen1 attempt 4 失败 -> retryCount=4>3 FAILED_TERMINAL -> 接力 gen2 -> attempt 5 成功.
        gen1FinalFuture.completeExceptionally(new java.io.IOException("disk full attempt 4 final"));

        assertEquals(5, submitCount.get(),
                "gen1 提交 4 次 (1 首投+3 重投) 耗尽后, 终态必须接力 gen2 再提交 1 次 = 5");
        CompoundTag lastSubmitted = submittedTags.get(submittedTags.size() - 1);
        assertEquals(2L, submittedGeneration(lastSubmitted),
                "entity 终态接力必须把碰撞后最新代 (gen=2) 落盘, 而非取走丢弃");
        assertEquals(EntitySaveState.Phase.CLEAN, state.phase(), "gen2 接力落地后 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "接力链落地后 mustDrain 清零");
        assertFalse(state.hasPendingSnapshot(), "pending 已被接力消费, 槽清空");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(1L, snap.entitiesCompleted(), "gen2 接力落盘计 entitiesCompleted 一次");
        assertEquals(0L, snap.entitiesFailed(), "gen2 接力成功, 旧代终态不再算丢失, entitiesFailed 为 0");
        assertEquals(4L, snap.entitiesRetried(), "gen1 三次 REQUEUE + 一次终态接力 = entitiesRetried 四次");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(0L, snap.mustDrainPending(), "mustDrain gauge 配平归零");
    }

    /**
     * c0 修复收敛性 (entity 终态接力级联): gen2 接力的 IO 也失败时, 因 retryCount 不重置 (沿用 gen1 已耗尽
     * 预算), 接力 IO 一次即 FAILED_TERMINAL 且槽已空 -> 一步收敛到真终态, 不无限接力。锁住 "不重置 retryCount"
     * 的收敛纪律 (重置会让持续 IO 故障下永不终态、无限接力)。
     *
     * <p>判定标准: 若 handleTerminalFailure 重置了 retryCount, gen2 接力会再吃满 maxRetries 次重投,
     * submitCount 远超 5; 本断言 submitCount==5 (gen1 四次 + gen2 一次) 锁死一步收敛。
     */
    @Test
    void terminal_relay_when_relay_also_fails_converges_in_one_step() {
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();

        CompletableFuture<Void> gen1FinalFuture = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger submitCount = new java.util.concurrent.atomic.AtomicInteger();
        List<CompoundTag> submittedTags = new ArrayList<>();
        // 所有 attempt 都失败 (gen2 接力 IO 也失败); attempt 4 用手动 future 维持在途以便登记 gen2.
        EntitySaveTask.IoSubmitter submitter = tag -> {
            submittedTags.add(tag);
            int n = submitCount.incrementAndGet();
            if (n == 4) {
                return gen1FinalFuture;
            }
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new java.io.IOException("persistent failure attempt " + n));
            return failed;
        };
        EntitySaveTask.PendingReoffer[] reofferHolder = new EntitySaveTask.PendingReoffer[1];
        reofferHolder[0] = pending -> {
            metrics.incInFlightSerializing();
            EntitySaveTask relay = new EntitySaveTask(pending, metrics, submitter, null, reofferHolder[0]);
            relay.execute();
        };

        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter, null, reofferHolder[0]);
        task.execute();             // gen1 attempt 1..4, 末次停在 gen1FinalFuture 在途

        state.markDirty();          // gen=2 碰撞登记
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        state.registerPendingSnapshot(snapshotForGeneration(state, 2L));

        // gen1 attempt 4 失败 -> 终态接力 gen2 -> gen2 attempt 5 也失败 -> retryCount=5>3 立即终态, 槽空收敛.
        gen1FinalFuture.completeExceptionally(new java.io.IOException("persistent failure attempt 4 final"));

        assertEquals(5, submitCount.get(),
                "gen1 四次 + gen2 接力一次 = 5 次提交后一步收敛, retryCount 不重置故不无限接力");
        CompoundTag lastSubmitted = submittedTags.get(submittedTags.size() - 1);
        assertEquals(2L, submittedGeneration(lastSubmitted),
                "gen2 接力即便最终失败, 也必须真实提交过一次 IO (拿到唯一落盘机会)");
        assertEquals(EntitySaveState.Phase.FAILED, state.phase(), "级联耗尽后 phase 终态 FAILED");
        assertFalse(state.mustDrain(), "真终态收敛后 mustDrain 清零");
        assertFalse(state.hasPendingSnapshot(), "pending 已被接力 take 走, 槽清空");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(1L, snap.entitiesFailed(), "gen2 接力最终失败计 entitiesFailed 一次");
        assertEquals(0L, snap.entitiesCompleted(), "全程无成功落盘");
        assertEquals(4L, snap.entitiesRetried(), "gen1 三次 REQUEUE + 一次终态接力 = entitiesRetried 四次");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(0L, snap.mustDrainPending(), "mustDrain gauge 配平归零");
    }

    private static int setMaxRetries(int value) throws Exception {
        Field f = com.shinoyuki.betterautosave.config.BetterAutoSaveConfig.class.getDeclaredField("maxRetries");
        f.setAccessible(true);
        int prev = f.getInt(null);
        f.setInt(null, value);
        return prev;
    }
}
