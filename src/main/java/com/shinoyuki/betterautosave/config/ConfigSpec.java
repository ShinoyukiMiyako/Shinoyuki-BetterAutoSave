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
    public static final ForgeConfigSpec.IntValue SAVED_DATA_WORKER_THREADS;
    public static final ForgeConfigSpec.BooleanValue ADAPTIVE_ENABLED;
    public static final ForgeConfigSpec.IntValue SHUTDOWN_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue DEADLINE_GUARD_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_RETRIES;
    public static final ForgeConfigSpec.IntValue SAVED_DATA_MAX_FILE_SIZE_MB;
    public static final ForgeConfigSpec.EnumValue<EventCompatMode> EVENT_COMPAT_MODE;
    public static final ForgeConfigSpec.BooleanValue DIAGNOSTIC_LOGGING;
    public static final ForgeConfigSpec.IntValue DIAGNOSTIC_LOG_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue PROMETHEUS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> PROMETHEUS_BIND_ADDRESS;
    public static final ForgeConfigSpec.IntValue PROMETHEUS_PORT;
    public static final ForgeConfigSpec.IntValue HOTTEST_CHUNKS_WINDOW_SIZE;
    public static final ForgeConfigSpec.IntValue HOTTEST_CHUNKS_TRACK_LIMIT;

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

        SAVED_DATA_WORKER_THREADS = BUILDER
                .comment("v0.7: threads dedicated to writing SavedData (.dat) files off the main thread.",
                         "1 is enough for typical loads (SavedData files are few and small).",
                         "Bump to 2 if you run mods with many large SavedData files (e.g. MTR, ANTE).")
                .defineInRange("savedDataWorkerThreads", 1, 1, 4);

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

        SAVED_DATA_MAX_FILE_SIZE_MB = BUILDER
                .comment("v0.7: SavedData files larger than this threshold are written synchronously (vanilla path)",
                         "instead of dispatched to the worker queue.",
                         "Prevents a single oversized file (e.g. corrupted MTR train data) from blocking",
                         "the savedData worker queue for many seconds.",
                         "Default 50 MB covers typical mod-registered SavedData; raise if you have legitimate",
                         "files larger than this and confirmed your worker IO can handle them.")
                .defineInRange("savedDataMaxFileSizeMB", 50, 1, 1024);

        BUILDER.pop();

        BUILDER.comment("Event compatibility").push("compat");

        EVENT_COMPAT_MODE = BUILDER
                .comment("ChunkDataEvent.Save dispatch mode (v0.2).",
                         "PARTIAL (default, recommended): main thread fires the event with a core tag that excludes sections.",
                         "  Most mods only attach sub-tags or read non-section fields and are unaffected.",
                         "  Listeners that call tag.get(\"sections\") will see null - flip to FULL if you have such a listener.",
                         "  Worker thread assembles sections after the event fires; perf gain is highest in this mode.",
                         "FULL: main thread builds the complete tag (sections included) and fires the event with full data.",
                         "  100% vanilla-equivalent semantics. Worker only does IO. Perf gain reduced (sections encoded on main thread).",
                         "DISABLED: skip the event entirely. Worker assembles sections (same path as PARTIAL).",
                         "  Use only when you are certain no listener mod relies on ChunkDataEvent.Save.",
                         "  Saves the per-chunk event dispatch overhead but breaks any mod that hooks Save.")
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

        BUILDER.comment("v0.9: Prometheus metrics HTTP exporter").push("prometheus");

        PROMETHEUS_ENABLED = BUILDER
                .comment("Enable Prometheus metrics HTTP exporter.",
                         "Default false: opt-in. Toggle on if you want to scrape BAS metrics from Grafana / Prometheus.",
                         "When enabled, the server starts an HTTP listener at bindAddress:port serving GET /metrics",
                         "in Prometheus exposition format (text/plain).")
                .define("enabled", false);

        PROMETHEUS_BIND_ADDRESS = BUILDER
                .comment("HTTP server bind address.",
                         "Default 0.0.0.0: accept connections from any network interface (open by default for ease of setup).",
                         "Security note: BAS metrics expose chunk save counters / queue depth / latency histograms.",
                         "These are not directly sensitive but reveal world activity patterns.",
                         "If your server has a public IP, restrict access via firewall (iptables / cloud Security Group)",
                         "or set this to 127.0.0.1 so only local Prometheus can scrape.")
                .define("bindAddress", "0.0.0.0");

        PROMETHEUS_PORT = BUILDER
                .comment("HTTP server port. Default 9450 (avoids 9090 Prometheus / 9100 node_exporter / 25565 MC).",
                         "Pick any free port; Prometheus scrape config must point to this port.")
                .defineInRange("port", 9450, 1024, 65535);

        BUILDER.pop();

        BUILDER.comment("v0.9: hottest-chunks command (per-chunk latency tracking)").push("hottestChunks");

        HOTTEST_CHUNKS_WINDOW_SIZE = BUILDER
                .comment("Sliding window size: per-chunk latency samples retained for p99 calculation.",
                         "Larger window = more stable percentile, more memory. 100 samples per chunk = ~1.6 KB.")
                .defineInRange("windowSize", 100, 10, 1000);

        HOTTEST_CHUNKS_TRACK_LIMIT = BUILDER
                .comment("LRU eviction limit: max number of chunks tracked simultaneously.",
                         "When the limit is hit, the least-recently-saved chunk is evicted.",
                         "10000 covers loaded chunks for typical 60-100 player servers (~ a few MB total memory).",
                         "Raise if you run very large worlds with many active chunks.")
                .defineInRange("trackLimit", 10_000, 100, 1_000_000);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private ConfigSpec() {
    }
}
