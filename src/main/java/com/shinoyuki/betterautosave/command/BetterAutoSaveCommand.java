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
        out.append("\n-- Entity (v0.6) --\n");
        out.append("EntityWorkerThreads: ").append(BetterAutoSaveConfig.entityWorkerThreads()).append('\n');
        out.append("EntityChunksPerTickBase: ").append(BetterAutoSaveConfig.entityChunksPerTickBase()).append('\n');
        out.append("Submitted: ").append(s.entitiesSubmitted()).append('\n');
        out.append("Completed: ").append(s.entitiesCompleted()).append('\n');
        out.append("Failed: ").append(s.entitiesFailed()).append('\n');
        out.append("Retried: ").append(s.entitiesRetried()).append('\n');
        out.append("Fallback: ").append(s.entitiesFallback()).append('\n');
        out.append("\n-- SavedData (v0.7) --\n");
        out.append("SavedDataWorkerThreads: ").append(BetterAutoSaveConfig.savedDataWorkerThreads()).append('\n');
        out.append("SavedDataMaxFileSizeMB: ").append(BetterAutoSaveConfig.savedDataMaxFileSizeMB()).append('\n');
        out.append("Submitted: ").append(s.savedDataSubmitted()).append('\n');
        out.append("Completed: ").append(s.savedDataCompleted()).append('\n');
        out.append("Failed: ").append(s.savedDataFailed()).append('\n');
        out.append("Fallback: ").append(s.savedDataFallback()).append('\n');
        out.append("\n-- Queue --\n");
        out.append("Worker queue depth: ").append(s.workerQueueDepth()).append('\n');
        out.append("Entity queue depth: ").append(s.entityQueueDepth()).append('\n');
        out.append("In-flight serializing: ").append(s.inFlightSerializing()).append('\n');
        out.append("In-flight IO_PENDING: ").append(s.inFlightIoPending()).append('\n');
        out.append("\n-- Latency (us, '>60s' = overflow bucket) --\n");
        out.append("Capture p50/p99/max: ")
                .append(SaveMetrics.formatLatencyUs(s.mainThreadCapture().p50Ns())).append("/")
                .append(SaveMetrics.formatLatencyUs(s.mainThreadCapture().p99Ns())).append("/")
                .append(SaveMetrics.formatLatencyUs(s.mainThreadCapture().maxNs())).append("\n");
        out.append("Worker p50/p99/max: ")
                .append(SaveMetrics.formatLatencyUs(s.workerNbtBuild().p50Ns())).append("/")
                .append(SaveMetrics.formatLatencyUs(s.workerNbtBuild().p99Ns())).append("/")
                .append(SaveMetrics.formatLatencyUs(s.workerNbtBuild().maxNs())).append("\n");
        out.append("IO p50/p99/max: ")
                .append(SaveMetrics.formatLatencyUs(s.ioStore().p50Ns())).append("/")
                .append(SaveMetrics.formatLatencyUs(s.ioStore().p99Ns())).append("/")
                .append(SaveMetrics.formatLatencyUs(s.ioStore().maxNs())).append("\n");
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

    /**
     * v0.5.1: flush 命令异步化. 之前 pipeline.drainPending 内部跑 Thread.sleep(50)
     * 循环, 命令在主线程执行, 主线程被 sleep 锁死最多 60s ("Can't keep up").
     * 与 drain-unload 同款 bug, 同款修复方式: 派后台 daemon 线程轮询.
     */
    private static int flush(CommandContext<CommandSourceStack> ctx) {
        if (!BetterAutoSaveCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is not installed"));
            return 0;
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        SaveMetrics.Snapshot snap0 = metrics.snapshot();
        if (pipeline.chunkWorkerQueue().isEmpty()
                && pipeline.entityWorkerQueue().isEmpty()
                && pipeline.savedDataWorkerQueue().isEmpty()
                && snap0.inFlightIoPending() == 0L) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "BetterAutoSave flush: nothing in-flight"
                            + " (cumulative chunkMapSaveAsync=" + snap0.chunkMapSaveAsync()
                            + " chunksCompleted=" + snap0.chunksCompleted() + ")"), false);
            return 1;
        }

        long timeoutMs = (long) BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L;
        net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
        CommandSourceStack source = ctx.getSource();
        int initialChunkQ = pipeline.chunkWorkerQueue().size();
        int initialEntityQ = pipeline.entityWorkerQueue().size();
        int initialSavedDataQ = pipeline.savedDataWorkerQueue().size();
        long initialIo = snap0.inFlightIoPending();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "BetterAutoSave flush: watching chunkQueue=" + initialChunkQ
                        + " entityQueue=" + initialEntityQ
                        + " savedDataQueue=" + initialSavedDataQ
                        + " ioPending=" + initialIo
                        + " (timeout " + timeoutMs + "ms, async)"), false);

        Thread watcher = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            long deadline = t0 + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (pipeline.chunkWorkerQueue().isEmpty()
                        && pipeline.entityWorkerQueue().isEmpty()
                        && pipeline.savedDataWorkerQueue().isEmpty()
                        && metrics.snapshot().inFlightIoPending() == 0L) {
                    long elapsed = System.currentTimeMillis() - t0;
                    server.execute(() -> source.sendSuccess(() -> Component.literal(
                            "BetterAutoSave flush: drained in " + elapsed + "ms"), true));
                    return;
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    server.execute(() -> source.sendFailure(Component.literal(
                            "BetterAutoSave flush: interrupted")));
                    return;
                }
            }
            SaveMetrics.Snapshot finalSnap = metrics.snapshot();
            int qC = pipeline.chunkWorkerQueue().size();
            int qE = pipeline.entityWorkerQueue().size();
            int qS = pipeline.savedDataWorkerQueue().size();
            server.execute(() -> source.sendFailure(Component.literal(
                    "BetterAutoSave flush: timed out after " + timeoutMs + "ms"
                            + " (chunkQueue=" + qC + " entityQueue=" + qE + " savedDataQueue=" + qS
                            + " ioPending=" + finalSnap.inFlightIoPending()
                            + "); vanilla flush will catch remainder")));
        }, "BetterAutoSave-Flush-Watch");
        watcher.setDaemon(true);
        watcher.start();
        return 1;
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
     * 实现: 命令立即返回, 派后台 daemon 线程轮询, 完成时通过 server.execute
     * 回主线程发消息。**绝对不能在主线程 sleep**, vanilla 命令处理跑在 server thread,
     * 主线程 sleep 直接锁服几十秒 (v0.4.0 已知 bug)。
     */
    private static int drainUnload(CommandContext<CommandSourceStack> ctx) {
        if (!BetterAutoSaveCore.isInstalled()) {
            ctx.getSource().sendFailure(Component.literal("BetterAutoSave is not installed"));
            return 0;
        }
        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        SaveMetrics.Snapshot snap0 = metrics.snapshot();
        long initial = snap0.mustDrainPending();
        if (initial == 0L && pipeline.chunkWorkerQueue().isEmpty()
                && snap0.inFlightIoPending() == 0L) {
            // 报告累计计数让用户确认 v0.4 mixin 在工作 (生产 100k+ 调用属正常),
            // 而不是误以为 mod 没生效.
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "BetterAutoSave drain-unload: nothing pending"
                            + " (cumulative chunkMapSaveAsync=" + snap0.chunkMapSaveAsync()
                            + " bypass=" + snap0.chunkMapSaveBypass()
                            + " fallback=" + snap0.chunkMapSaveFallback()
                            + ")"), false);
            return 1;
        }

        long timeoutMs = (long) BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L;
        net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
        CommandSourceStack source = ctx.getSource();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "BetterAutoSave drain-unload: watching "
                        + initial + " mustDrain chunk(s), queue="
                        + pipeline.chunkWorkerQueue().size()
                        + ", ioPending=" + snap0.inFlightIoPending()
                        + " (timeout " + timeoutMs + "ms, async)"), false);

        Thread watcher = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            long deadline = t0 + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                SaveMetrics.Snapshot s = metrics.snapshot();
                if (s.mustDrainPending() == 0L
                        && pipeline.chunkWorkerQueue().isEmpty()
                        && s.inFlightIoPending() == 0L) {
                    long elapsed = System.currentTimeMillis() - t0;
                    server.execute(() -> source.sendSuccess(() -> Component.literal(
                            "BetterAutoSave drain-unload: drained " + initial
                                    + " mustDrain chunk(s) in " + elapsed + "ms"), true));
                    return;
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    server.execute(() -> source.sendFailure(Component.literal(
                            "BetterAutoSave drain-unload: interrupted")));
                    return;
                }
            }
            SaveMetrics.Snapshot finalSnap = metrics.snapshot();
            int queueSize = pipeline.chunkWorkerQueue().size();
            server.execute(() -> source.sendFailure(Component.literal(
                    "BetterAutoSave drain-unload: timed out after " + timeoutMs + "ms"
                            + " (mustDrain=" + finalSnap.mustDrainPending()
                            + ", queue=" + queueSize
                            + ", ioPending=" + finalSnap.inFlightIoPending() + ")")));
        }, "BetterAutoSave-DrainUnload-Watch");
        watcher.setDaemon(true);
        watcher.start();
        return 1;
    }

    private BetterAutoSaveCommand() {
    }
}
