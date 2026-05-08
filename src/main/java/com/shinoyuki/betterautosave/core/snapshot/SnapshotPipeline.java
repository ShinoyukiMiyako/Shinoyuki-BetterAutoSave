package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.scheduler.ChunkSavePriority;
import com.shinoyuki.betterautosave.core.scheduler.ChunkSubmissionSink;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.core.worker.SerializationWorker;
import com.shinoyuki.betterautosave.core.worker.WorkerThreadFactory;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkDataEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
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
    private volatile MinecraftServer server;
    private volatile ChunkResolutionHook chunkResolution;
    private volatile EntityResolutionHook entityResolution;

    public interface ChunkResolutionHook {
        void onPriorityDrained(ChunkSavePriority priority);
    }

    public interface EntityResolutionHook {
        void onPriorityDrained(ChunkSavePriority priority);
    }

    public SnapshotPipeline(SaveScheduler scheduler, AsyncIoBridge ioBridge, SaveMetrics metrics) {
        this.scheduler = scheduler;
        this.ioBridge = ioBridge;
        this.metrics = metrics;
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
                    metrics);
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

        ChunkSaveTask task = new ChunkSaveTask(snapshot, level, ioBridge, metrics);
        metrics.incInFlightSerializing();
        chunkWorkerQueue.offer(task);
        return true;
    }

    @Override
    public void submitChunk(ChunkSavePriority priority) {
        ChunkResolutionHook hook = chunkResolution;
        if (hook == null) {
            return;
        }
        hook.onPriorityDrained(priority);
    }

    @Override
    public void submitEntity(ChunkSavePriority priority) {
        EntityResolutionHook hook = entityResolution;
        if (hook == null) {
            return;
        }
        hook.onPriorityDrained(priority);
    }

    public void setChunkResolutionHook(ChunkResolutionHook hook) {
        this.chunkResolution = hook;
    }

    public void setEntityResolutionHook(EntityResolutionHook hook) {
        this.entityResolution = hook;
    }

    public void triggerDegraded() {
        if (degraded.compareAndSet(false, true)) {
            LOGGER.error("[BetterAutoSave] entered DEGRADED mode; all saves fall back to vanilla synchronous path");
        }
    }

    public boolean isDegraded() {
        return degraded.get();
    }

    public MinecraftServer server() {
        return server;
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

    public BlockingQueue<SaveTask> savedDataWorkerQueue() {
        return savedDataWorkerQueue;
    }

    public boolean drainPending(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (chunkWorkerQueue.isEmpty()
                    && entityWorkerQueue.isEmpty()
                    && savedDataWorkerQueue.isEmpty()
                    && metrics.snapshot().inFlightIoPending() == 0L) {
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
