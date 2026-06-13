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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EntitySaveTask IO 失败 tag 原地重投单测.
 *
 * <p>现场: 若 entity IO 失败 REQUEUE_DIRTY 仅 recordEntityRetried 后 return, FAILED_TERMINAL
 * 仅 recordEntityFailed 后 return — 两者皆不重投已序列化的 tag, 也无坐标恢复队列. 而 vanilla
 * processChunkUnload 先 storeEntities (BAS 同步 capture 完成 tag) 后立即驱逐实体内存 +
 * chunkLoadStatuses.remove, 卸载后该坐标永不再被 autoSave/saveAll 调 storeEntities. 此时 BAS 持有
 * 的 tag 是唯一副本, IO 失败后随失败 task 丢弃 → 玩家命名生物/盔甲架/展示框等持久实体凭空消失.
 *
 * <p>本测试用测试构造注入 fake IoSubmitter (绕开 IOWorker), 让前 N 次返回失败 future, 第 N+1 次
 * 成功. fake 返回已完成 future, whenComplete 同步内联在测试线程跑, 整条重投链确定性执行.
 *
 * <p>判定标准:
 * - 删掉 submitIo 失败分支的 submitIo(state, tag) 重投 → 提交次数恒为 1, 第一个断言挂.
 * - 重投复用同一 tag (不重新 assemble): fake 记录每次入参 tag, 断言引用恒等.
 */
class EntitySaveTaskRetryTest {

    private static ResourceKey<Level> DIM;

    private int savedMaxRetries;

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DIM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
    }

    @BeforeEach
    void markWorkerAndSetRetries() throws Exception {
        // assemble 内 assertOnWorkerThread; 把测试线程标记为 worker.
        WorkerThreadAssert.markCurrentThreadAsWorker();
        savedMaxRetries = setMaxRetries(3);
    }

    @AfterEach
    void restore() throws Exception {
        setMaxRetries(savedMaxRetries);
        WorkerThreadAssert.unmarkCurrentThreadAsWorker();
    }

    private EntitySaveState dirtyState() {
        // 复刻生产 capture 推进到入队前的状态: markDirty -> trySnapshot -> enterSerializing.
        // enterSerializing 把 inFlightGeneration 锁到当前 generation, 这是 ioCompletedSuccessfully
        // 判定 CLEAN_LANDED (generation 未变) 的前提.
        EntitySaveState state = new EntitySaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        return state;
    }

    private EntitySnapshot snapshotFor(EntitySaveState state) {
        ListTag entities = new ListTag();
        CompoundTag one = new CompoundTag();
        one.putString("id", "minecraft:armor_stand");
        entities.add(one);
        return new EntitySnapshot(new ChunkPos(3, -5), DIM, 3700, entities, 0L, state);
    }

    @Test
    void io_failure_then_success_redispatches_same_tag_and_lands() {
        EntitySaveState state = dirtyState();
        EntitySnapshot snapshot = snapshotFor(state);
        SaveMetrics metrics = new SaveMetrics();

        // capture 阶段 mixin 已 incInFlightSerializing; 对齐 execute 内 decInFlightSerializing 配平.
        metrics.incInFlightSerializing();

        AtomicInteger submitCount = new AtomicInteger();
        CompoundTag[] firstSubmittedTag = new CompoundTag[1];
        boolean[] sameTagEachTime = {true};
        // 前 2 次失败, 第 3 次成功. 已完成 future → whenComplete 同步内联.
        EntitySaveTask.IoSubmitter submitter = tag -> {
            if (firstSubmittedTag[0] == null) {
                firstSubmittedTag[0] = tag;
            } else if (firstSubmittedTag[0] != tag) {
                sameTagEachTime[0] = false;
            }
            int n = submitCount.incrementAndGet();
            if (n <= 2) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new java.io.IOException("disk full attempt " + n));
                return failed;
            }
            return CompletableFuture.completedFuture(null);
        };

        EntitySaveTask task = new EntitySaveTask(snapshot, metrics, submitter);
        task.execute();

        // 失败 2 次 + 成功 1 次 = 提交 3 次. 删掉重投逻辑则恒为 1.
        assertEquals(3, submitCount.get(),
                "entity IO 失败 REQUEUE_DIRTY 必须用已序列化 tag 原地重投, 直到成功");
        // 重投必须复用同一 tag 实例 (不重新 assemble / 不需要 chunk 仍加载).
        org.junit.jupiter.api.Assertions.assertTrue(sameTagEachTime[0],
                "重投必须复用首次序列化的同一 tag 实例");
        // 最终落盘成功, 状态机回 CLEAN.
        assertEquals(EntitySaveState.Phase.CLEAN, state.phase(),
                "最终成功后 phase 必须回 CLEAN");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(1L, snap.entitiesCompleted(), "最终成功计 entitiesCompleted 一次");
        assertEquals(2L, snap.entitiesRetried(), "两次失败重投各计 entitiesRetried 一次");
        assertEquals(0L, snap.entitiesFailed(), "未超限不应计 entitiesFailed");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 必须配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 必须配平归零");
    }

    @Test
    void io_failure_exhausting_retries_goes_terminal_and_records_failed() {
        EntitySaveState state = dirtyState();
        EntitySnapshot snapshot = snapshotFor(state);
        SaveMetrics metrics = new SaveMetrics();
        metrics.incInFlightSerializing();

        AtomicInteger submitCount = new AtomicInteger();
        // 永远失败. maxRetries=3 → 第 1..3 次 REQUEUE_DIRTY 重投, 第 4 次 retryCount=4>3 FAILED_TERMINAL.
        EntitySaveTask.IoSubmitter submitter = tag -> {
            submitCount.incrementAndGet();
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new java.io.IOException("persistent failure"));
            return failed;
        };

        EntitySaveTask task = new EntitySaveTask(snapshot, metrics, submitter);
        task.execute();

        // 1 次首投 + 3 次重投 = 4 次提交后耗尽.
        assertEquals(4, submitCount.get(),
                "maxRetries=3 时应提交 1+3=4 次后走 terminal");
        assertEquals(EntitySaveState.Phase.FAILED, state.phase(),
                "重试耗尽后 phase 必须 FAILED");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(1L, snap.entitiesFailed(), "terminal 计 entitiesFailed 一次");
        assertEquals(3L, snap.entitiesRetried(), "3 次中间 REQUEUE_DIRTY 各计 retried 一次");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 必须配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 必须配平归零");
    }

    private static int setMaxRetries(int value) throws Exception {
        Field f = com.shinoyuki.betterautosave.config.BetterAutoSaveConfig.class
                .getDeclaredField("maxRetries");
        f.setAccessible(true);
        int prev = f.getInt(null);
        f.setInt(null, value);
        return prev;
    }
}
