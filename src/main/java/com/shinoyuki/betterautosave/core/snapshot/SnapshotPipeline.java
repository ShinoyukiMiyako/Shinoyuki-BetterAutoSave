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
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
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

    private final List<SerializationWorker> chunkWorkers = new ArrayList<>();
    private final List<Thread> chunkWorkerThreads = new ArrayList<>();
    private final List<SerializationWorker> entityWorkers = new ArrayList<>();
    private final List<Thread> entityWorkerThreads = new ArrayList<>();

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
        LOGGER.info("BetterAutoSave pipeline started with {} chunk workers and {} entity workers",
                chunkWorkers.size(), entityWorkers.size());
    }

    public CompoundTag captureAndDispatchChunk(LevelChunk chunk, ServerLevel level, ChunkSaveState state) {
        ServerThreadAssert.assertOnServerThread(level.getServer());
        if (degraded.get()) {
            throw new IllegalStateException("Pipeline is in degraded mode");
        }

        long t0 = System.nanoTime();
        long captured = state.enterSerializing();
        CompoundTag tag = ChunkSerializer.write(level, chunk);

        ConfigSpec.EventCompatMode mode = BetterAutoSaveConfig.eventCompatMode();
        if (mode != ConfigSpec.EventCompatMode.DISABLED) {
            long evT0 = System.nanoTime();
            MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, tag));
            metrics.recordEventDispatchNs(System.nanoTime() - evT0);
        }
        metrics.recordCaptureNs(System.nanoTime() - t0);

        ChunkSnapshot snapshot = new ChunkSnapshot(chunk.getPos(), level.dimension(), tag, captured, state);
        ChunkSaveTask task = new ChunkSaveTask(snapshot, level, ioBridge, metrics);
        metrics.recordChunkSubmitted();
        metrics.incInFlightSerializing();
        chunkWorkerQueue.offer(task);
        return tag;
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
            LOGGER.error("BetterAutoSave entered degraded mode; chunk and entity saves will fall back to vanilla synchronous path");
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
        return ok;
    }

    public BlockingQueue<SaveTask> chunkWorkerQueue() {
        return chunkWorkerQueue;
    }

    public BlockingQueue<SaveTask> entityWorkerQueue() {
        return entityWorkerQueue;
    }

    public boolean drainPending(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (chunkWorkerQueue.isEmpty() && entityWorkerQueue.isEmpty() && metrics.snapshot().inFlightIoPending() == 0L) {
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
            if (chunkWorkerQueue.isEmpty() && entityWorkerQueue.isEmpty()) {
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
