package com.shinoyuki.betterautosave.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

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

    private BetterAutoSaveCommand() {
    }
}
