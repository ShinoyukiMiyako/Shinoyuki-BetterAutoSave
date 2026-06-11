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

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChunkSaveTask IO 失败 tag 原地重投单测 (Major 修复 M2).
 *
 * <p>现场: 旧实现 IO 失败 REQUEUE_DIRTY 投坐标进 ChunkRecoveryQueue, 主线程 drain 时 chunk 已
 * unload (getChunkNow 返 null) 就 WARN 丢弃 → unload 后弃疗. 实际 snapshot/tag 序列化早已完成,
 * 重试 IO 不需要 chunk 对象, 直接用已序列化的 tag 重投即可, 与 chunk 是否仍加载无关.
 *
 * <p>本测试用测试构造注入 fake IoSubmitter (绕开 ServerLevel / IOWorker), 让前 N 次返回失败
 * future, 第 N+1 次成功. fake 返回的是已完成 future, whenComplete 同步内联在测试线程跑, 整条
 * 重投链确定性执行无异步时序.
 *
 * <p>判定标准:
 * - "N 次失败后成功": 删掉 submitIo 失败分支的 submitIo(state, tag) 重投 → 提交次数恒为 1, 第一个断言挂.
 * - "重投复用同一 tag (不重新 capture)": fake 记录每次入参 tag, 断言引用恒等.
 * - "超限走 terminal": maxRetries 内重试耗尽后断言 FAILED + 投恢复队列 + chunksFailed 计数.
 */
class ChunkSaveTaskRetryTest {

    // ResourceKey.create 触发 Registries.DIMENSION 解析需 MC bootstrap, 不能在 static init
    // (早于 @BeforeAll) 构造; 延到 bootstrap 之后赋值.
    private static ResourceKey<Level> DIM;

    private int savedMaxRetries;

    @BeforeAll
    static void bootstrapMinecraft() {
        // 解析 ResourceKey / ResourceLocation 与 dimension().location() 需 vanilla registry 已 bootstrap.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DIM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation("minecraft", "overworld"));
    }

    @BeforeEach
    void markWorkerAndSetRetries() throws Exception {
        // assemble 内 assertOnWorkerThread; 把测试线程标记为 worker.
        ServerThreadAssert.markCurrentThreadAsWorker();
        savedMaxRetries = setMaxRetries(3);
    }

    @AfterEach
    void restore() throws Exception {
        setMaxRetries(savedMaxRetries);
        ServerThreadAssert.unmarkCurrentThreadAsWorker();
    }

    private ChunkSnapshot prebuiltSnapshot(ChunkSaveState state) {
        CompoundTag fullTag = new CompoundTag();
        fullTag.putInt("marker", 42);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, fullTag, 0L, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    private ChunkSaveState dirtyState() {
        // 复刻生产 capture 推进到入队前的状态: markDirty -> trySnapshot -> enterSerializing.
        // enterSerializing 把 inFlightGeneration 锁到当前 generation, 这是 ioCompletedSuccessfully
        // 判定 CLEAN_LANDED (generation 未变) 的前提; 漏掉会被误判 REQUEUE_DIRTY.
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        return state;
    }

    @Test
    void io_failure_then_success_redispatches_same_tag_and_lands() {
        ChunkSaveState state = dirtyState();
        ChunkSnapshot snapshot = prebuiltSnapshot(state);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();

        // capture 阶段 mixin 已 incInFlightSerializing; 对齐 execute 内 decInFlightSerializing 配平.
        metrics.incInFlightSerializing();

        AtomicInteger submitCount = new AtomicInteger();
        CompoundTag[] lastSubmittedTag = new CompoundTag[1];
        // 前 2 次返回失败 future, 第 3 次成功. 已完成 future → whenComplete 同步内联.
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            lastSubmittedTag[0] = tag;
            int n = submitCount.incrementAndGet();
            if (n <= 2) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new java.io.IOException("disk full attempt " + n));
                return failed;
            }
            return CompletableFuture.completedFuture(null);
        };

        ChunkSaveTask task = new ChunkSaveTask(snapshot, metrics, null, recoveryQueue, submitter);
        task.execute();

        // 失败 2 次 + 成功 1 次 = 提交 3 次. 删掉重投逻辑则恒为 1.
        assertEquals(3, submitCount.get(),
                "IO 失败 REQUEUE_DIRTY 必须用已序列化 tag 原地重投, 直到成功");
        // 重投必须复用同一 tag 实例 (不重新 capture / 不需要 chunk 对象).
        assertSame(snapshot.preBuiltFullTag(), lastSubmittedTag[0],
                "重投必须复用首次序列化的同一 tag");
        // 最终落盘成功, 状态机回 CLEAN.
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase(),
                "最终成功后 phase 必须回 CLEAN");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(1L, snap.chunksCompleted(), "最终成功计 chunksCompleted 一次");
        assertEquals(2L, snap.chunksRetried(), "两次失败重投各计 chunksRetried 一次");
        assertEquals(0L, snap.chunksFailed(), "未超限不应计 chunksFailed");
        // 重投不依赖恢复队列: 成功路径不投坐标.
        assertEquals(0, recoveryQueue.size(),
                "tag 重投成功路径不应往坐标恢复队列投递");
        // in-flight gauge 全部配平 (每次 submitIo inc, 每次 whenComplete dec).
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 必须配平归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 必须配平归零");
    }

    @Test
    void io_failure_exhausting_retries_goes_terminal_and_enqueues_recovery() {
        ChunkSaveState state = dirtyState();
        ChunkSnapshot snapshot = prebuiltSnapshot(state);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();
        metrics.incInFlightSerializing();

        AtomicInteger submitCount = new AtomicInteger();
        // 永远失败. maxRetries=3 → 第 1..3 次 REQUEUE_DIRTY 重投, 第 4 次 retryCount=4>3 FAILED_TERMINAL.
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submitCount.incrementAndGet();
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new java.io.IOException("persistent failure"));
            return failed;
        };

        ChunkSaveTask task = new ChunkSaveTask(snapshot, metrics, null, recoveryQueue, submitter);
        task.execute();

        // 1 次首投 + 3 次重投 = 4 次提交后耗尽 (maxRetries=3, 第 4 次 ioFailed 返 FAILED_TERMINAL).
        assertEquals(4, submitCount.get(),
                "maxRetries=3 时应提交 1+3=4 次后走 terminal");
        assertEquals(ChunkSaveState.Phase.FAILED, state.phase(),
                "重试耗尽后 phase 必须 FAILED");

        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(1L, snap.chunksFailed(), "terminal 计 chunksFailed 一次");
        assertEquals(3L, snap.chunksRetried(), "3 次中间 REQUEUE_DIRTY 各计 retried 一次");
        // terminal 才投坐标恢复队列让 vanilla 同步兜底.
        assertEquals(1, recoveryQueue.size(),
                "重试耗尽必须投坐标恢复队列还原 isUnsaved");
        long[] capturedPacked = {Long.MIN_VALUE};
        String[] capturedDim = {null};
        recoveryQueue.drain((dim, packed) -> {
            capturedDim[0] = dim;
            capturedPacked[0] = packed;
            return null;
        });
        assertEquals("minecraft:overworld", capturedDim[0],
                "恢复条目维度口径必须与三条重入门一致");
        assertEquals(new ChunkPos(3, -5).toLong(), capturedPacked[0],
                "恢复条目坐标必须是失败 chunk 的 packedPos");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 必须配平归零");
    }

    /**
     * C-callback-sync-throw-swallowed: submit 同步抛 (而非 future 异常完成) 必须走与 IO 失败等同的补偿,
     * 不静默落进被丢弃的 dependent future。第一次 submit 同步抛 -> onIoFailure REQUEUE_DIRTY 重投,
     * 第二次 submit 成功落地。
     *
     * <p>判定标准 (删 submitIo 的 submit-throw 自包 try 必挂): 不补偿则 ioPending gauge 永久 +1
     * (inc 已发生无 future 可 dec), phase 卡 IO_PENDING, 本断言 inFlightIoPending()==0 与 phase==CLEAN 挂。
     */
    @Test
    void submit_synchronous_throw_is_compensated_and_retried() {
        ChunkSaveState state = dirtyState();
        ChunkSnapshot snapshot = prebuiltSnapshot(state);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();
        metrics.incInFlightSerializing();

        AtomicInteger submitCount = new AtomicInteger();
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            int n = submitCount.incrementAndGet();
            if (n == 1) {
                throw new RuntimeException("mailbox teardown NPE surrogate");
            }
            return CompletableFuture.completedFuture(null);
        };

        ChunkSaveTask task = new ChunkSaveTask(snapshot, metrics, null, recoveryQueue, submitter);
        task.execute();

        assertEquals(2, submitCount.get(),
                "submit 同步抛必须走 onIoFailure REQUEUE_DIRTY 重投 (第 2 次成功)");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase(),
                "submit 同步抛补偿后重投成功, phase 回 CLEAN (非卡 IO_PENDING)");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.inFlightIoPending(),
                "submit 同步抛后 ioPending gauge 必须配平归零 (inc 已被自包 catch dec)");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(1L, snap.chunksRetried(), "submit 同步抛计一次 retried");
    }

    /**
     * C-callback-sync-throw-swallowed: submit 持续同步抛, 重试耗尽走 FAILED_TERMINAL + 投坐标恢复队列,
     * 全程 gauge 配平 (回调线程内递归 submitIo 的同步抛也被自包补偿)。
     *
     * <p>判定标准: 删自包补偿 -> 每次递归 submitIo 的 inc 漏 dec, ioPending 累积泄漏, 本断言挂。
     */
    @Test
    void persistent_submit_throw_goes_terminal_with_balanced_gauges() {
        ChunkSaveState state = dirtyState();
        ChunkSnapshot snapshot = prebuiltSnapshot(state);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();
        metrics.incInFlightSerializing();

        AtomicInteger submitCount = new AtomicInteger();
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            submitCount.incrementAndGet();
            throw new RuntimeException("persistent teardown surrogate");
        };

        ChunkSaveTask task = new ChunkSaveTask(snapshot, metrics, null, recoveryQueue, submitter);
        task.execute();

        // maxRetries=3 -> 1 首投 + 3 重投 = 4 次 submit 后 FAILED_TERMINAL.
        assertEquals(4, submitCount.get(), "submit 持续同步抛应 1+3=4 次后走 terminal");
        assertEquals(ChunkSaveState.Phase.FAILED, state.phase(), "重试耗尽 phase=FAILED");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(1L, snap.chunksFailed(), "terminal 计 chunksFailed 一次");
        assertEquals(0L, snap.inFlightIoPending(),
                "递归 submitIo 同步抛的 inc 全部被自包 catch 配平, ioPending 归零");
        assertEquals(0L, snap.inFlightSerializing(), "serializing gauge 配平归零");
        assertEquals(1, recoveryQueue.size(), "terminal 投坐标恢复队列");
    }

    /**
     * C-callback-sync-throw-swallowed: REQUEUE_DIRTY 落地接力时 reoffer sink 同步抛, 不得静默丢 pending ——
     * safeReoffer 必须清 mustDrain + 配平 gauge (serializing 由 sink 自身配平)。
     *
     * <p>判定标准 (删 safeReoffer 必挂): 不包 try 则 reoffer 抛冒泡进被丢弃 dependent future,
     * mustDrain 永真 gauge 泄漏 (本断言 mustDrainPending()==0 与 mustDrain()==false 挂);
     * 删 sink 自身 inc/offer 配平则 serializing 泄漏。
     */
    @Test
    void reoffer_synchronous_throw_balances_must_drain_and_serializing() {
        ChunkSaveState state = dirtyState();   // gen=1, inFlightGeneration=1
        ChunkSnapshot snapshot = prebuiltSnapshot(state);
        SaveMetrics metrics = new SaveMetrics();
        ChunkRecoveryQueue recoveryQueue = new ChunkRecoveryQueue();
        metrics.incInFlightSerializing();

        CompletableFuture<Void> gen1Future = new CompletableFuture<>();
        java.util.Deque<CompletableFuture<Void>> futures = new java.util.ArrayDeque<>();
        futures.add(gen1Future);
        ChunkSaveTask.IoSubmitter submitter = tag -> {
            CompletableFuture<Void> f = futures.poll();
            return f != null ? f : CompletableFuture.completedFuture(null);
        };
        // reoffer sink 复刻生产: 先 inc serializing, 再抛 (模拟 ctor/offer 同步抛). safeReoffer 应清 mustDrain;
        // sink 自身负责 dec 回它 inc 的 serializing (这里复刻 SnapshotPipeline sink 的自包 try)。
        ChunkSaveTask.PendingReoffer throwingReoffer = pending -> {
            metrics.incInFlightSerializing();
            try {
                throw new RuntimeException("relay sink teardown surrogate");
            } catch (Throwable t) {
                metrics.decInFlightSerializing();
                throw t;
            }
        };

        ChunkSaveTask task = new ChunkSaveTask(snapshot, metrics, null, recoveryQueue, submitter, throwingReoffer);
        task.execute();             // gen=1 IO 在飞

        // 碰撞登记 pending(gen=2), 置 mustDrain.
        state.markDirty();          // gen=2
        assertTrue(state.tryMarkMustDrain());
        metrics.incMustDrainPending();
        state.registerPendingSnapshot(prebuiltSnapshotGen(state, 2L));

        // gen=1 落地 -> REQUEUE_DIRTY -> 取 pending -> safeReoffer -> sink 抛 -> 补偿.
        gen1Future.complete(null);

        assertFalse(state.hasPendingSnapshot(), "reoffer 抛后 pending 已 take 走, 槽空 (不残留)");
        assertFalse(state.mustDrain(), "safeReoffer 必须清 mustDrain (该接力代无法落盘)");
        SaveMetrics.Snapshot snap = metrics.snapshot();
        assertEquals(0L, snap.mustDrainPending(), "mustDrain gauge 配平归零 (不泄漏)");
        assertEquals(0L, snap.inFlightSerializing(),
                "serializing gauge 配平归零 (sink 自身 dec 回 inc)");
        assertEquals(0L, snap.inFlightIoPending(), "ioPending gauge 配平归零");
    }

    private ChunkSnapshot prebuiltSnapshotGen(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
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
