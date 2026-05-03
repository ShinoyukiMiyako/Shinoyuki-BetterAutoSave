package com.shinoyuki.betterautosave.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ConfigSpec {

    public enum EventCompatMode {
        PARTIAL,
        FULL,
        DISABLED
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue CHUNKS_PER_TICK_BASE;
    public static final ForgeConfigSpec.IntValue ENTITY_CHUNKS_PER_TICK_BASE;
    public static final ForgeConfigSpec.IntValue WORKER_THREADS;
    public static final ForgeConfigSpec.IntValue ENTITY_WORKER_THREADS;
    public static final ForgeConfigSpec.BooleanValue ADAPTIVE_ENABLED;
    public static final ForgeConfigSpec.IntValue SHUTDOWN_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue DEADLINE_GUARD_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_RETRIES;
    public static final ForgeConfigSpec.EnumValue<EventCompatMode> EVENT_COMPAT_MODE;
    public static final ForgeConfigSpec.BooleanValue DIAGNOSTIC_LOGGING;
    public static final ForgeConfigSpec.IntValue DIAGNOSTIC_LOG_INTERVAL_TICKS;

    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.comment("BetterAutoSave common configuration (shared across all worlds)").push("general");

        ENABLED = BUILDER
                .comment("Master switch. When false, all mod logic is bypassed and chunk saving falls back to vanilla behavior.")
                .define("enabled", true);

        BUILDER.pop();

        BUILDER.comment("Throttle for main-thread snapshot capture").push("throttle");

        CHUNKS_PER_TICK_BASE = BUILDER
                .comment("Base limit on chunks the scheduler will start capturing per server tick.",
                         "Adaptive throttling may halve or skip based on TPS unless the deadline guard fires.")
                .defineInRange("chunksPerTickBase", 4, 1, 64);

        ENTITY_CHUNKS_PER_TICK_BASE = BUILDER
                .comment("Same as chunksPerTickBase, but for entity-section snapshots which run on an independent budget.")
                .defineInRange("entityChunksPerTickBase", 2, 1, 64);

        ADAPTIVE_ENABLED = BUILDER
                .comment("Reduce per-tick budget when avg tick time exceeds 51.3ms (TPS<19.5) and skip ticks when above 52.6ms (TPS<19).",
                         "Disable only for benchmarking; production servers should keep this on.")
                .define("adaptiveEnabled", true);

        DEADLINE_GUARD_SECONDS = BUILDER
                .comment("When the autosave cycle has fewer seconds remaining than this value, throttling is bypassed",
                         "to ensure all dirty chunks complete a snapshot within the cycle. Vanilla cycle length is 300s.")
                .defineInRange("deadlineGuardSeconds", 30, 0, 240);

        BUILDER.pop();

        BUILDER.comment("Worker-thread pool sizes").push("workers");

        WORKER_THREADS = BUILDER
                .comment("Threads dedicated to building chunk NBT off the main thread.",
                         "More threads do not help past 2-3 because vanilla IOWorker serializes region-file writes.")
                .defineInRange("chunkWorkerThreads", 2, 1, 8);

        ENTITY_WORKER_THREADS = BUILDER
                .comment("Threads dedicated to building entity NBT off the main thread.")
                .defineInRange("entityWorkerThreads", 2, 1, 8);

        BUILDER.pop();

        BUILDER.comment("Failure handling and shutdown").push("safety");

        SHUTDOWN_TIMEOUT_SECONDS = BUILDER
                .comment("Total time the server stop sequence will wait for in-flight snapshots to drain",
                         "before falling back to a synchronous vanilla save path for the stragglers.")
                .defineInRange("shutdownTimeoutSeconds", 60, 5, 600);

        MAX_RETRIES = BUILDER
                .comment("Number of times a chunk that fails NBT build or IO submit will be re-queued",
                         "before its state is parked in FAILED and a synchronous fallback is used.")
                .defineInRange("maxRetries", 3, 0, 10);

        BUILDER.pop();

        BUILDER.comment("Event compatibility").push("compat");

        EVENT_COMPAT_MODE = BUILDER
                .comment("ChunkDataEvent.Save dispatch mode.",
                         "PARTIAL: main-thread tag without sections (highest performance). Most mods only attach sub-tags and are unaffected.",
                         "FULL: main-thread builds the complete tag including sections, halving the perf gain but matching vanilla semantics.",
                         "DISABLED: skip the event entirely. Use only when no listener mod relies on it.")
                .defineEnum("eventCompatMode", EventCompatMode.PARTIAL);

        BUILDER.pop();

        BUILDER.comment("Diagnostics").push("diagnostics");

        DIAGNOSTIC_LOGGING = BUILDER
                .comment("Periodically log queue depth, throughput, and latency percentiles to the server log.",
                         "Default true on v0.1 to surface anomalies in production. Set false once stability proven.")
                .define("diagnosticLogging", true);

        DIAGNOSTIC_LOG_INTERVAL_TICKS = BUILDER
                .comment("How often diagnostic summaries are emitted, in server ticks (20 ticks = 1s).")
                .defineInRange("diagnosticLogIntervalTicks", 200, 20, 6000);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private ConfigSpec() {
    }
}
