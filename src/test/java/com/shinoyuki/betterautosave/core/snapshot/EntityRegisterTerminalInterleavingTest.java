package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.state.EntitySaveState;
import com.shinoyuki.betterautosave.core.state.EntitySaveStateAccess;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * entity 接力槽 "回调终态取槽" 与 "主线程登记" 双向交错的两个无主丢失窗口防御回归.
 *
 * <p>窗口一 (回调 CLEAN_LANDED 误 evict): 回调 stale 读旧代判 CLEAN_LANDED, 若 evict 前不取槽, 会把主线程刚
 * register 的更新代 pending 随状态对象送 GC 永久丢失 (entity 无恢复队列无 isUnsaved 门)。故回调 CLEAN_LANDED
 * 在 evict 前必取一次槽; 取到则接力重投不 evict。
 *
 * <p>窗口二 (主线程 register 落死状态): 回调 REQUEUE_DIRTY 取槽时主线程 register 尚未发生取到 null 跳过重投,
 * 主线程随后 register 落进 phase=DIRTY 死状态 -> 条目滞留 + mustDrainPending 永久 +1。故主线程 register 写槽
 * 后重读 phase, 见不在在飞态即取回自己刚放的 pending 自踢重投。
 *
 * <p>两侧顺序对偶 (回调先写 phase 终态再 getAndSet 取槽; 主线程先写槽再重读 phase) 保证任一交错至少一方看见对方,
 * getAndSet 析构语义裁定唯一消费者防双投。测试用确定性序复刻两个窗口各自的交错点 (回调终态先/主线程 register 先),
 * 断言落盘代号 / mustDrain gauge 精确值 / 状态条目是否被在 pending 非空时误 evict。
 */
class EntityRegisterTerminalInterleavingTest {

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

    private EntitySnapshot snapshotForGeneration(EntitySaveState state, long generation) {
        ListTag entities = new ListTag();
        CompoundTag marker = new CompoundTag();
        marker.putLong("gen", generation);
        entities.add(marker);
        return new EntitySnapshot(new ChunkPos(3, -5), DIM, 3700, entities, generation, state);
    }

    private long submittedGeneration(CompoundTag tag) {
        ListTag entities = tag.getList("Entities", 10);
        return entities.getCompound(0).getLong("gen");
    }

    /** 记录 evict 调用次数的 fake stateOwner (其余 access 不被本测试触发)。 */
    private static final class RecordingStateOwner implements EntitySaveStateAccess {
        final AtomicInteger evictCalls = new AtomicInteger();

        @Override
        public EntitySaveState betterautosave$getOrCreateEntityState(long packedPos, String dimensionId, long enqueueSequence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntitySaveState betterautosave$getEntityState(long packedPos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void betterautosave$evictEntityStateIfClean(long packedPos, EntitySaveState expected) {
            evictCalls.incrementAndGet();
        }
    }

    /**
     * 窗口一: 在飞回调判 CLEAN_LANDED 时接力槽已被主线程 register 一份 pending。回调 CLEAN_LANDED 必须先取槽,
     * 取到非空则接力重投并 <b>不</b> evict 状态条目 (否则 pending 随状态对象 GC 永久丢失)。
     *
     * <p>复刻 stale 读现场: 主线程在回调落地前 register 了一份与在飞同代 (capturedGeneration=1) 的 pending
     * (stale 读使回调仍判 generation 未变 CLEAN_LANDED)。回调落地取槽得到它 -> 接力。
     *
     * <p>判定标准 (删 CLEAN_LANDED 分支新增的 takePendingSnapshot+接力, 退回直接 evict): pending 不被接力,
     * 落盘代仍为在飞代, "接力落 pending 代" 断言挂; 且 evict 被调用 (evictCalls>0) 把 pending 送走断言挂。
     */
    @Test
    void clean_landed_with_registered_pending_relays_and_does_not_evict() {
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        RecordingStateOwner owner = new RecordingStateOwner();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();

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
            metrics.incInFlightSerializing();
            EntitySaveTask relay = new EntitySaveTask(pending, metrics, submitter, owner, reofferHolder[0]);
            relay.execute();
        };

        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter, owner, reofferHolder[0]);
        task.execute();             // gen=1 IO 在飞

        // stale 读现场: 主线程在回调落地前 register 一份 pending (capturedGeneration=1, 与在飞同代, 使回调仍
        // 判 CLEAN_LANDED). generation 不推进 (复刻回调 stale 读: 它没看到更新代).
        EntitySnapshot pendingSameGen = snapshotForGeneration(state, 1L);
        state.registerPendingSnapshot(pendingSameGen);
        assertTrue(state.hasPendingSnapshot(), "register 后槽非空");

        // 回调落地: generation(1)==inFlightGeneration(1) -> CLEAN_LANDED. 必须先取槽 -> 接力, 不 evict.
        firstFuture.complete(null);

        assertEquals(2, submittedTags.size(), "CLEAN_LANDED 取到 pending 必须接力重投一次 (而非 evict 丢弃)");
        assertSame(pendingSameGen.entitiesNbt(),
                submittedTags.get(1).getList("Entities", 10),
                "接力落盘的必须是 register 的 pending 实例 (未随 evict 丢失)");
        // 持 pending 的那次 CLEAN_LANDED 绝不能 evict (否则 pending 随对象 GC); 唯一一次 evict 来自接力链
        // 终态 (槽已空) 的那次 CLEAN_LANDED, 此刻 evict 是正确的有界增长收口。删 evict 前的取槽守卫退回直接
        // evict, 则 evictCalls 会变 2 (持 pending 那次也 evict) 且接力不发生, 上面 submittedTags 断言先挂。
        assertEquals(1, owner.evictCalls.get(),
                "仅接力链终态 (槽空) evict 一次; 持 pending 的 CLEAN_LANDED 不得 evict");
        assertEquals(EntitySaveState.Phase.CLEAN, state.phase(), "接力落地后 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "接力链落地后 mustDrain 清零");
        assertFalse(state.hasPendingSnapshot(), "pending 已被接力 take 走, 槽清空");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.mustDrainPending(), "mustDrainPending gauge 配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
    }

    /**
     * 窗口一对照: 常规 CLEAN_LANDED (槽空, 无碰撞 register) 必须照常 evict 状态条目防无界增长 ——
     * 证明窗口一修复只在 "槽非空" 时拦住 evict, 不破坏常规剔除。
     *
     * <p>判定标准: 删 CLEAN_LANDED 槽空分支的 evict 调用 -> evictCalls==0 断言挂 (退回无界增长)。
     */
    @Test
    void clean_landed_with_empty_slot_still_evicts() {
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        RecordingStateOwner owner = new RecordingStateOwner();

        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        EntitySnapshot gen1 = snapshotForGeneration(state, 1L);
        metrics.incInFlightSerializing();
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();

        EntitySaveTask.IoSubmitter submitter = tag -> CompletableFuture.completedFuture(null);
        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter, owner, pending -> {
        });
        task.execute();             // gen=1 IO 立即完成 CLEAN_LANDED, 槽空

        assertEquals(1, owner.evictCalls.get(),
                "常规 CLEAN_LANDED (槽空) 必须 evict 状态条目防无界增长");
        assertFalse(state.mustDrain(), "CLEAN_LANDED 清 mustDrain");
        assertEquals(0L, metrics.snapshot().mustDrainPending(), "mustDrainPending gauge 配平归零");
    }

    /**
     * 窗口二: 回调 REQUEUE_DIRTY 取槽时主线程 register 尚未发生 (取到 null 不重投), 主线程随后 register 落进
     * phase=DIRTY 死状态。主线程 register 写槽后必须重读 phase, 见 DIRTY (不在在飞态) -> 取回自己刚放的
     * pending 自踢重投, 把更新代落盘, 条目不滞留, mustDrainPending 配平归零。
     *
     * <p>直接驱动协调方法 {@link EntityPendingRelayCoordinator#registerAndSelfKick} (mixin 退化为对它的一行委托):
     * 主线程 tryMarkMustDrain -> markDirty 后, 调 registerAndSelfKick 登记 pending + 重读 phase + 自取 + 自踢。
     * 自踢 sink 复刻 reofferEntityPendingFromMainThread (reenter + 同步执行接力)。
     *
     * <p>判定标准 (删协调方法的重读 phase + 自取 + 自踢, 即删委托目标的逻辑): registerAndSelfKick 不自踢, 更新代
     * 不落盘, mustDrainPending 残 1, 条目滞留 phase=DIRTY —— 本测试 "更新代落盘" 与 "mustDrainPending==0" 断言挂。
     */
    @Test
    void requeue_dirty_callback_takes_null_then_main_register_self_kicks() {
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        RecordingStateOwner owner = new RecordingStateOwner();

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
            EntitySaveTask relay = new EntitySaveTask(pending, metrics, submitter, owner, reofferHolder[0]);
            relay.execute();
        };

        EntitySaveTask task = new EntitySaveTask(gen1, metrics, submitter, owner, reofferHolder[0]);
        task.execute();             // gen=1 IO 在飞

        // 主线程碰撞分支前半: tryMarkMustDrain + markDirty 推 G2 (在飞分支显式 markDirty). 此刻尚未 register.
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        state.markDirty();          // gen=2

        // 回调先于 register 落地: generation(2) != inFlightGeneration(1) -> REQUEUE_DIRTY. 取槽 (此刻空) -> null,
        // 不重投. 任务退出, phase=DIRTY, mustDrain 维持真.
        gen1Future.complete(null);
        assertEquals(EntitySaveState.Phase.DIRTY, state.phase(),
                "回调 REQUEUE_DIRTY 后 phase=DIRTY (取到 null 未重投)");
        assertEquals(1, submittedTags.size(), "回调取 null 未重投 (只提交过在飞 gen=1)");
        assertTrue(state.mustDrain(), "REQUEUE_DIRTY 不清 mustDrain, 维持真");

        // 主线程碰撞分支后半: 直接驱动协调方法 (mixin 委托目标). 自踢 sink 复刻 reofferEntityPendingFromMainThread.
        EntitySnapshot g2 = snapshotForGeneration(state, 2L);
        EntitySnapshot[] selfKickTaken = new EntitySnapshot[1];
        boolean selfKicked = EntityPendingRelayCoordinator.registerAndSelfKick(state, g2, metrics,
                (st, taken) -> {
                    selfKickTaken[0] = taken;
                    st.reenterSerializingForPending(taken.capturedGeneration());
                    reofferHolder[0].reoffer(taken);
                });
        assertTrue(selfKicked, "register 重读 phase=DIRTY (回调已退出无在飞消费者) -> 主线程自取自踢");
        assertSame(g2, selfKickTaken[0], "主线程取回自己刚放的 pending (回调未取走)");

        assertEquals(2, submittedTags.size(), "主线程自踢必须把更新代重投一次");
        assertEquals(2L, submittedGeneration(submittedTags.get(1)),
                "主线程自踢必须把更新代 (gen=2) 落盘, 而非滞留死状态丢失");
        assertEquals(EntitySaveState.Phase.CLEAN, state.phase(), "自踢接力落地后 phase 回 CLEAN");
        assertFalse(state.mustDrain(), "自踢接力链落地后 mustDrain 清零");
        assertFalse(state.hasPendingSnapshot(), "pending 已被自踢接力消费, 槽清空");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.mustDrainPending(),
                "mustDrainPending gauge 配平归零 (不因 register 落死状态而永久正偏移)");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
    }

    private static int setMaxRetries(int value) throws Exception {
        Field f = com.shinoyuki.betterautosave.config.BetterAutoSaveConfig.class.getDeclaredField("maxRetries");
        f.setAccessible(true);
        int prev = f.getInt(null);
        f.setInt(null, value);
        return prev;
    }
}
