package com.shinoyuki.betterautosave.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.state.ChunkSaveStateAccess;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.mixin.accessor.ChunkMapAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

public final class BetterAutoSaveCommand {

    private static final int OP_LEVEL = 2;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("betterautosave")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .then(Commands.literal("debug").executes(BetterAutoSaveCommand::debug))
                        .then(Commands.literal("metrics").executes(BetterAutoSaveCommand::metrics))
                        .then(Commands.literal("flush").executes(BetterAutoSaveCommand::flush))
                        .then(Commands.literal("status").executes(BetterAutoSaveCommand::status))
                        .then(Commands.literal("force-async").executes(BetterAutoSaveCommand::forceAsync))
                        .then(Commands.literal("drain-unload").executes(BetterAutoSaveCommand::drainUnload))
        );
    }

    private static int debug(CommandContext<CommandSourceStack> ctx) {
        if (!BetterAutoSaveCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is not installed"));
            return 0;
        }
        SaveMetrics.Snapshot s = BetterAutoSaveCore.metrics().snapshot();
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        StringBuilder out = new StringBuilder();
        out.append("=== BetterAutoSave Debug ===\n");
        out.append("Mode: ").append(pipeline.isDegraded() ? "DEGRADED" : "ACTIVE").append('\n');
        out.append("EventCompatMode: ").append(BetterAutoSaveConfig.eventCompatMode()).append('\n');
        out.append("ChunksPerTickBase: ").append(BetterAutoSaveConfig.chunksPerTickBase()).append('\n');
        out.append("WorkerThreads: ").append(BetterAutoSaveConfig.workerThreads()).append('\n');
        out.append("\n-- Counters --\n");
        out.append("Submitted: ").append(s.chunksSubmitted()).append('\n');
        out.append("Completed: ").append(s.chunksCompleted()).append('\n');
        out.append("Failed: ").append(s.chunksFailed()).append('\n');
        out.append("Retried: ").append(s.chunksRetried()).append('\n');
        out.append("Fallback: ").append(s.chunksFallback()).append('\n');
        out.append("\n-- ChunkMap.save (v0.4) --\n");
        out.append("Async: ").append(s.chunkMapSaveAsync()).append('\n');
        out.append("Fallback: ").append(s.chunkMapSaveFallback()).append('\n');
        out.append("Bypass: ").append(s.chunkMapSaveBypass()).append('\n');
        out.append("MustDrain pending: ").append(s.mustDrainPending()).append('\n');
        out.append("\n-- Queue --\n");
        out.append("Worker queue depth: ").append(s.workerQueueDepth()).append('\n');
        out.append("Entity queue depth: ").append(s.entityQueueDepth()).append('\n');
        out.append("In-flight serializing: ").append(s.inFlightSerializing()).append('\n');
        out.append("In-flight IO_PENDING: ").append(s.inFlightIoPending()).append('\n');
        out.append("\n-- Latency (ns -> us) --\n");
        out.append("Capture p50/p99/max: ")
                .append(s.mainThreadCapture().p50Ns() / 1000).append("/")
                .append(s.mainThreadCapture().p99Ns() / 1000).append("/")
                .append(s.mainThreadCapture().maxNs() / 1000).append("\n");
        out.append("Worker p50/p99/max: ")
                .append(s.workerNbtBuild().p50Ns() / 1000).append("/")
                .append(s.workerNbtBuild().p99Ns() / 1000).append("/")
                .append(s.workerNbtBuild().maxNs() / 1000).append("\n");
        out.append("IO p50/p99/max: ")
                .append(s.ioStore().p50Ns() / 1000).append("/")
                .append(s.ioStore().p99Ns() / 1000).append("/")
                .append(s.ioStore().maxNs() / 1000).append("\n");
        ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
        return 1;
    }

    private static int metrics(CommandContext<CommandSourceStack> ctx) {
        if (!BetterAutoSaveCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is not installed"));
            return 0;
        }
        SaveMetrics.Snapshot s = BetterAutoSaveCore.metrics().snapshot();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "submitted=" + s.chunksSubmitted()
                        + " completed=" + s.chunksCompleted()
                        + " failed=" + s.chunksFailed()
                        + " queueDepth=" + s.workerQueueDepth()
                        + " inFlight=" + s.inFlightSerializing() + "/" + s.inFlightIoPending()
        ), false);
        return 1;
    }

    private static int flush(CommandContext<CommandSourceStack> ctx) {
        if (!BetterAutoSaveCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is not installed"));
            return 0;
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        long timeoutMs = (long) BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L;
        boolean drained = pipeline.drainPending(timeoutMs);
        if (drained) {
            ctx.getSource().sendSuccess(() -> Component.literal("BetterAutoSave drained all in-flight saves"), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(
                "BetterAutoSave drain timed out after " + timeoutMs + " ms; vanilla flush will catch remainder"));
        return 0;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        if (!BetterAutoSaveCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is not installed"));
            return 0;
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        String mode = pipeline.isDegraded() ? "DEGRADED" : (BetterAutoSaveConfig.enabled() ? "ACTIVE" : "DISABLED");
        ctx.getSource().sendSuccess(() -> Component.literal("BetterAutoSave: " + mode), false);
        return 1;
    }

    /**
     * 诊断命令: 强制对当前维度的所有 visibleChunkMap LevelChunk 走一次完整异步路径,
     * 绕开 vanilla autosave 的 6000-tick 周期与 chunk dirty 状态。每个 chunk 都会
     * markDirty + setUnsaved(true) 然后调 pipeline.captureAndDispatchChunk, dev 单机
     * 环境下用于验证 worker NBT 拼装路径是否真实跑起来。生产环境使用相当于一次手动
     * autosave (除节流外行为等价), 仍受 BAS 常规 fallback / state 守卫保护。
     */
    private static int forceAsync(CommandContext<CommandSourceStack> ctx) {
        if (!BetterAutoSaveCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is not installed"));
            return 0;
        }
        if (!BetterAutoSaveConfig.enabled()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is disabled in config"));
            return 0;
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline.isDegraded()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is in DEGRADED mode"));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        String dimensionId = level.dimension().location().toString();
        SaveScheduler scheduler = BetterAutoSaveCore.scheduler();
        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;

        int dispatched = 0;
        int fallback = 0;
        int errors = 0;
        for (ChunkHolder holder : ((ChunkMapAccessor) chunkMap).betterautosave$getVisibleChunkMap().values()) {
            ChunkAccess chunk = holder.getLastAvailable();
            if (!(chunk instanceof LevelChunk levelChunk)) {
                continue;
            }
            long packed = chunk.getPos().toLong();
            long sequence = scheduler.nextEnqueueSequence();
            ChunkSaveState state = ((ChunkSaveStateAccess) chunk).betterautosave$getOrCreateState(
                    packed, dimensionId, sequence);
            chunk.setUnsaved(true);
            state.markDirty();
            metrics.recordChunkSubmitted();
            try {
                if (pipeline.captureAndDispatchChunk(levelChunk, level, state)) {
                    dispatched++;
                } else {
                    metrics.recordChunkFallback();
                    fallback++;
                }
            } catch (Throwable t) {
                metrics.recordChunkFailed();
                errors++;
                BetterAutoSaveMod.LOGGER.error(
                        "[BetterAutoSave] force-async failed for chunk {} dim={}",
                        chunk.getPos(), dimensionId, t);
            }
        }

        int finalDispatched = dispatched;
        int finalFallback = fallback;
        int finalErrors = errors;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "force-async @ " + dimensionId
                        + ": dispatched=" + finalDispatched
                        + " fallback=" + finalFallback
                        + " errors=" + finalErrors), true);
        return finalDispatched;
    }

    /**
     * v0.4: 等所有 mustDrain (经 ChunkMap.save mixin 接管走异步的) chunks 落盘。
     * 主用途: 关服前手动检查 unload + eager save 路径异步任务是否全部完成,
     * 或 stress test 后验证 mustDrainPending 收敛到 0。
     *
     * 实现: 轮询 metrics.mustDrainPending() 直至 0 或 timeout (复用
     * shutdownTimeoutSeconds), 不阻塞主线程超过 50ms 一段。
     */
    private static int drainUnload(CommandContext<CommandSourceStack> ctx) {
        if (!BetterAutoSaveCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is not installed"));
            return 0;
        }
        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        long initial = metrics.snapshot().mustDrainPending();
        if (initial == 0L && pipeline.chunkWorkerQueue().isEmpty()
                && metrics.snapshot().inFlightIoPending() == 0L) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "BetterAutoSave drain-unload: nothing pending"), false);
            return 1;
        }

        long timeoutMs = (long) BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            SaveMetrics.Snapshot snap = metrics.snapshot();
            if (snap.mustDrainPending() == 0L
                    && pipeline.chunkWorkerQueue().isEmpty()
                    && snap.inFlightIoPending() == 0L) {
                long elapsed = System.currentTimeMillis() - t0;
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "BetterAutoSave drain-unload: drained " + initial
                                + " mustDrain chunk(s) in " + elapsed + "ms"), true);
                return 1;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.getSource().sendFailure(Component.literal(
                        "BetterAutoSave drain-unload: interrupted"));
                return 0;
            }
        }
        SaveMetrics.Snapshot finalSnap = metrics.snapshot();
        ctx.getSource().sendFailure(Component.literal(
                "BetterAutoSave drain-unload: timed out after " + timeoutMs + "ms"
                        + " (mustDrain=" + finalSnap.mustDrainPending()
                        + ", queue=" + pipeline.chunkWorkerQueue().size()
                        + ", ioPending=" + finalSnap.inFlightIoPending() + ")"));
        return 0;
    }

    private BetterAutoSaveCommand() {
    }
}
