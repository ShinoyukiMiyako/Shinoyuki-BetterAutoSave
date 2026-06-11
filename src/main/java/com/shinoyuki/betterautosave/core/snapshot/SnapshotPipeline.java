package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.scheduler.ChunkSavePriority;
import com.shinoyuki.betterautosave.core.scheduler.ChunkSubmissionSink;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.state.EntitySaveStateAccess;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.core.worker.SerializationWorker;
import com.shinoyuki.betterautosave.core.worker.WorkerThreadFactory;
import com.shinoyuki.betterautosave.diagnostic.ChunkLatencyTracker;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkDataEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SnapshotPipeline implements ChunkSubmissionSink {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final SaveScheduler scheduler;
    private final AsyncIoBridge ioBridge;
    private final SaveMetrics metrics;
    private final BlockingQueue<SaveTask> chunkWorkerQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<SaveTask> entityWorkerQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<SaveTask> savedDataWorkerQueue = new LinkedBlockingQueue<>();

    private final List<SerializationWorker> chunkWorkers = new ArrayList<>();
    private final List<Thread> chunkWorkerThreads = new ArrayList<>();
    private final List<SerializationWorker> entityWorkers = new ArrayList<>();
    private final List<Thread> entityWorkerThreads = new ArrayList<>();
    private final List<SerializationWorker> savedDataWorkers = new ArrayList<>();
    private final List<Thread> savedDataWorkerThreads = new ArrayList<>();

    private final AtomicBoolean degraded = new AtomicBoolean(false);
    // Critical 修复 2: chunk IO 失败后由 worker 线程投递, 主线程 tick drain 还原 unsaved 标志.
    private final ChunkRecoveryQueue chunkRecoveryQueue = new ChunkRecoveryQueue();
    // Minor 修复 4: SavedData 在途文件名去重, 防多 worker 并发写同名 .dat.
    // 主线程 mixin 入队前 add, worker task finally remove.
    private final Set<String> savedDataInFlight = ConcurrentHashMap.newKeySet();
    private volatile MinecraftServer server;
    private volatile ChunkResolutionHook chunkResolution;
    private volatile ChunkLatencyTracker latencyTracker;

    public interface ChunkResolutionHook {
        void onPriorityDrained(ChunkSavePriority priority);
    }

    public SnapshotPipeline(SaveScheduler scheduler, AsyncIoBridge ioBridge, SaveMetrics metrics) {
        this.scheduler = scheduler;
        this.ioBridge = ioBridge;
        this.metrics = metrics;
    }

    /**
     * v0.9: 注入 ChunkLatencyTracker. 用 setter 而非构造参数避免破坏现有
     * SnapshotPipeline(scheduler, ioBridge, metrics) 调用方契约.
     * tracker 可为 null, ChunkSaveTask 会做 null 检查.
     */
    public void setLatencyTracker(ChunkLatencyTracker latencyTracker) {
        this.latencyTracker = latencyTracker;
    }

    public void start(MinecraftServer server) {
        this.server = server;
        this.scheduler.attachSink(this);

        WorkerThreadFactory chunkFactory = new WorkerThreadFactory("BetterAutoSave-Chunk-Worker", this::triggerDegraded);
        for (int i = 0; i < BetterAutoSaveConfig.workerThreads(); i++) {
            SerializationWorker w = new SerializationWorker(
                    "BetterAutoSave-Chunk-Worker-" + (i + 1),
                    chunkWorkerQueue,
                    metrics);
            Thread t = chunkFactory.newThread(w);
            chunkWorkers.add(w);
            chunkWorkerThreads.add(t);
            t.start();
        }

        WorkerThreadFactory entityFactory = new WorkerThreadFactory("BetterAutoSave-Entity-Worker", this::triggerDegraded);
        for (int i = 0; i < BetterAutoSaveConfig.entityWorkerThreads(); i++) {
            SerializationWorker w = new SerializationWorker(
                    "BetterAutoSave-Entity-Worker-" + (i + 1),
                    entityWorkerQueue,
                    metrics);
            Thread t = entityFactory.newThread(w);
            entityWorkers.add(w);
            entityWorkerThreads.add(t);
            t.start();
        }

        WorkerThreadFactory savedDataFactory = new WorkerThreadFactory("BetterAutoSave-SavedData-Worker", this::triggerDegraded);
        for (int i = 0; i < BetterAutoSaveConfig.savedDataWorkerThreads(); i++) {
            SerializationWorker w = new SerializationWorker(
                    "BetterAutoSave-SavedData-Worker-" + (i + 1),
                    savedDataWorkerQueue,
                    metrics,
                    // v0.10.2 修复 (M7): SavedData 无 SaveScheduler 逐 tick drain 回写时机, worker
                    // 消费后回写深度, 消除主线程 offer 峰值长期陈旧. chunk 由 scheduler 回写、entity
                    // 已无深度指标 (v0.10.2 删死调度链), 故仅 savedData worker 注入回写 sink.
                    metrics::setSavedDataQueueDepth);
            Thread t = savedDataFactory.newThread(w);
            savedDataWorkers.add(w);
            savedDataWorkerThreads.add(t);
            t.start();
        }
        LOGGER.debug("[BetterAutoSave] worker pool ready: {} chunk + {} entity + {} savedData",
                chunkWorkers.size(), entityWorkers.size(), savedDataWorkers.size());
    }

    public boolean captureAndDispatchChunk(LevelChunk chunk, ServerLevel level, ChunkSaveState state) {
        ServerThreadAssert.assertOnServerThread(level.getServer());
        if (degraded.get()) {
            throw new IllegalStateException("Pipeline is in degraded mode");
        }

        if (!state.trySnapshot()) {
            return false;
        }

        chunk.setUnsaved(false);

        ConfigSpec.EventCompatMode mode = BetterAutoSaveConfig.eventCompatMode();

        long t0 = System.nanoTime();
        ChunkSnapshot snapshot;
        try {
            snapshot = ChunkCaptureProcedure.capture(chunk, level, state, mode);
        } finally {
            metrics.recordCaptureNs(System.nanoTime() - t0);
        }

        // ChunkDataEvent.Save 派发: 必须在主线程, 在 worker 拼 sections 之前。
        // PARTIAL  -> 用 preBuiltCoreTag (无 sections), 90%+ 监听 mod 不读 sections 不受影响
        // FULL     -> 用 preBuiltFullTag (vanilla 完整 tag), 100% 兼容但主线程负担与 v0.1 接近
        // DISABLED -> 完全跳过事件; 仅当用户确认无监听 mod 依赖 Save 时启用
        if (mode != ConfigSpec.EventCompatMode.DISABLED) {
            CompoundTag eventTag = snapshot.preBuiltFullTag() != null
                    ? snapshot.preBuiltFullTag()
                    : snapshot.preBuiltCoreTag();
            long evT0 = System.nanoTime();
            MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, eventTag));
            metrics.recordEventDispatchNs(System.nanoTime() - evT0);
        }

        ChunkSaveTask task = new ChunkSaveTask(snapshot, level, ioBridge, metrics, latencyTracker, chunkRecoveryQueue,
                chunkPendingReoffer(level));
        metrics.incInFlightSerializing();
        chunkWorkerQueue.offer(task);
        return true;
    }

    /**
     * v0.10.2 修复 (C-chunk-unload-collision): 构造接力快照重投 sink。把 pending 快照包成新 ChunkSaveTask
     * offer 回 chunkWorkerQueue —— assemble 由序列化 worker 做, **不**在调用本 sink 的 IOWorker 邮箱线程内联
     * (内联会堵全服写盘, 这是已批复的备选案)。inFlightSerializing 的 inc 由调用方 (ChunkSaveTask 回调) 在
     * reoffer 前做, 与新 task execute 首行的 dec 配平; 新 task 同样持有本 sink, 支持任意深度的接力链 (隐角 A)。
     */
    private ChunkSaveTask.PendingReoffer chunkPendingReoffer(ServerLevel level) {
        return pending -> {
            ChunkSaveTask relay = new ChunkSaveTask(pending, level, ioBridge, metrics, latencyTracker,
                    chunkRecoveryQueue, chunkPendingReoffer(level));
            chunkWorkerQueue.offer(relay);
        };
    }

    @Override
    public void submitChunk(ChunkSavePriority priority) {
        ChunkResolutionHook hook = chunkResolution;
        if (hook == null) {
            return;
        }
        hook.onPriorityDrained(priority);
    }

    public void setChunkResolutionHook(ChunkResolutionHook hook) {
        this.chunkResolution = hook;
    }

    public void triggerDegraded() {
        if (degraded.compareAndSet(false, true)) {
            LOGGER.error("[BetterAutoSave] entered DEGRADED mode; all saves fall back to vanilla synchronous path");
            // 单向闩锁首次翻转才 fire, 保证下游 (BetterBackup) 每进程最多收一次降级信号.
            SaveListenerRegistry.firePipelineDegraded();
        }
    }

    public boolean isDegraded() {
        return degraded.get();
    }

    public MinecraftServer server() {
        return server;
    }

    public ChunkRecoveryQueue chunkRecoveryQueue() {
        return chunkRecoveryQueue;
    }

    /** Minor 修复 4: SavedData 在途文件名去重集合, mixin 入队前 add, task finally remove. */
    public Set<String> savedDataInFlight() {
        return savedDataInFlight;
    }

    /**
     * Critical 修复 2: 主线程 tick 路径调, drain IO 失败待恢复队列.
     * 对每条记录按 dim 找 ServerLevel -> getChunkNow 拿已加载 chunk -> setUnsaved(true)
     * 还原 vanilla 重入门. chunk 已 unload 返 null, 由 ChunkRecoveryQueue 内 log WARN.
     * 必须在主线程调 — getChunkNow / setUnsaved 都非线程安全.
     *
     * @return 实际恢复的 chunk 数 (0 表示队列空, 无开销)
     */
    public int drainChunkRecoveryQueue() {
        if (chunkRecoveryQueue.size() == 0) {
            return 0;
        }
        MinecraftServer localServer = server;
        if (localServer == null) {
            return 0;
        }
        return chunkRecoveryQueue.drain((dimensionId, packedPos) -> {
            ServerLevel level = null;
            for (ServerLevel l : localServer.getAllLevels()) {
                if (l.dimension().location().toString().equals(dimensionId)) {
                    level = l;
                    break;
                }
            }
            if (level == null) {
                return null;
            }
            ChunkPos pos = new ChunkPos(packedPos);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                return null;
            }
            return chunk::setUnsaved;
        });
    }

    public boolean joinWorkers(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (SerializationWorker w : chunkWorkers) {
            w.requestStop();
        }
        for (SerializationWorker w : entityWorkers) {
            w.requestStop();
        }
        for (SerializationWorker w : savedDataWorkers) {
            w.requestStop();
        }
        boolean ok = true;
        for (Thread t : chunkWorkerThreads) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                ok = false;
                break;
            }
            try {
                t.join(remaining);
                if (t.isAlive()) {
                    ok = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ok = false;
                break;
            }
        }
        for (Thread t : entityWorkerThreads) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                ok = false;
                break;
            }
            try {
                t.join(remaining);
                if (t.isAlive()) {
                    ok = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ok = false;
                break;
            }
        }
        for (Thread t : savedDataWorkerThreads) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                ok = false;
                break;
            }
            try {
                t.join(remaining);
                if (t.isAlive()) {
                    ok = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ok = false;
                break;
            }
        }
        return ok;
    }

    public BlockingQueue<SaveTask> chunkWorkerQueue() {
        return chunkWorkerQueue;
    }

    public BlockingQueue<SaveTask> entityWorkerQueue() {
        return entityWorkerQueue;
    }

    /**
     * v0.10.2 修复 (C-entity-unload-collision): 构造 entity 接力快照重投 sink, 与
     * {@link #chunkPendingReoffer} 对称。把 pending EntitySnapshot 包成新 EntitySaveTask offer 回
     * entityWorkerQueue (assemble 由序列化 worker 做)。entity task 在 mixin 构造 (持有 per-level
     * EntityStorage 的 IOWorker 与 stateOwner), 故由 mixin 调本工厂传入这两者。新 task 同样持有本 sink,
     * 支持任意深度接力链 (隐角 A)。inFlightSerializing 的 inc 由调用方在 reoffer 前做, 与新 task
     * execute 首行 dec 配平。
     */
    public EntitySaveTask.PendingReoffer entityPendingReoffer(IOWorker entityIoWorker, EntitySaveStateAccess stateOwner) {
        return pending -> {
            EntitySaveTask relay = new EntitySaveTask(pending, entityIoWorker, metrics, stateOwner,
                    entityPendingReoffer(entityIoWorker, stateOwner));
            entityWorkerQueue.offer(relay);
        };
    }

    public BlockingQueue<SaveTask> savedDataWorkerQueue() {
        return savedDataWorkerQueue;
    }

    public boolean drainPending(long timeoutMs) {
        // v0.10.2 修复 (M9): degraded 下提前明示返回, 不空耗满 timeout.
        // worker 线程因 Error 死亡时 WorkerThreadFactory 的 uncaught handler 调 triggerDegraded.
        // 全 chunk worker 死亡后 chunkWorkerQueue 无人消费, 在队 task 在 captureAndDispatchChunk
        // 已 incInFlightSerializing 但永不 execute -> inFlightSerializing 永久 >0, 下面的轮询条件
        // 永假, drainPending 必空耗满 shutdownTimeoutSeconds (默认 60s) 才返 false, 平白拖慢关服。
        // degraded 态下所有 save 已走 vanilla 同步路径 (各 mixin degraded 闸门), 异步在途无法也无需
        // 等其 drain — 直接返 false 让调用方走既有 warn + vanilla flush 兜底 (与 [2] 的 degraded 后
        // 仍 drain 恢复队列协同: 失败 chunk 的 isUnsaved 还原 + vanilla flush 落盘)。
        if (isDegraded()) {
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            // v0.7.1 修复 (C3): 加 inFlightSerializing 检查. 之前漏检导致
            // worker poll 后 task.execute 内 assemble 期间 (queue 已 empty +
            // ioPending 未 inc) 50ms 轮询窗口误返 true, 关服 drainPending 协议失真.
            // assemble 在大 chunk 下耗几十-几百 ms, 是真实窗口本体而非 yield.
            SaveMetrics.Snapshot snap = metrics.snapshot();
            if (chunkWorkerQueue.isEmpty()
                    && entityWorkerQueue.isEmpty()
                    && savedDataWorkerQueue.isEmpty()
                    && snap.inFlightSerializing() == 0L
                    && snap.inFlightIoPending() == 0L) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public boolean awaitWorkerIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (chunkWorkerQueue.isEmpty()
                    && entityWorkerQueue.isEmpty()
                    && savedDataWorkerQueue.isEmpty()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
