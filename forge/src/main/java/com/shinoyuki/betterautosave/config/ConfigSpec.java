package com.shinoyuki.betterautosave.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ConfigSpec {

    public enum EventCompatMode {
        PARTIAL,
        FULL,
        DISABLED
    }

    /**
     * 异步加载切分模式。无 DISABLED 态: ChunkDataEvent.Load 不可跳 (第三方监听方假设主线程, 见
     * docs/ASYNC_LOAD_DESIGN.md 第六节), 故最弱也是 FULL (整段 read 留主线程, 零行为偏差), 不存在
     * "跳过事件" 的合法态。
     */
    public enum LoadCompatMode {
        PARTIAL,
        FULL
    }

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue CHUNKS_PER_TICK_BASE;
    public static final ForgeConfigSpec.IntValue WORKER_THREADS;
    public static final ForgeConfigSpec.IntValue ENTITY_WORKER_THREADS;
    public static final ForgeConfigSpec.IntValue SAVED_DATA_WORKER_THREADS;
    public static final ForgeConfigSpec.IntValue LOAD_WORKER_THREADS;
    public static final ForgeConfigSpec.BooleanValue ADAPTIVE_ENABLED;
    public static final ForgeConfigSpec.IntValue SHUTDOWN_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue DEADLINE_GUARD_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_RETRIES;
    public static final ForgeConfigSpec.IntValue SAVED_DATA_MAX_FILE_SIZE_MB;
    public static final ForgeConfigSpec.EnumValue<EventCompatMode> EVENT_COMPAT_MODE;
    public static final ForgeConfigSpec.BooleanValue LOAD_ENABLED;
    public static final ForgeConfigSpec.EnumValue<LoadCompatMode> LOAD_EVENT_COMPAT_MODE;
    public static final ForgeConfigSpec.IntValue LOAD_MAX_RETRIES;
    public static final ForgeConfigSpec.IntValue LOAD_MAX_IN_FLIGHT;
    public static final ForgeConfigSpec.BooleanValue LOAD_POI_PREFETCH;
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

        ADAPTIVE_ENABLED = BUILDER
                .comment("Reduce per-tick budget when avg tick time exceeds 51.3ms (TPS<19.5) and skip ticks when above 52.6ms (TPS<19).",
                         "Disable only for benchmarking; production servers should keep this on.")
                .define("adaptiveEnabled", true);

        DEADLINE_GUARD_SECONDS = BUILDER
                .comment("When the autosave cycle has fewer seconds remaining than this value, throttling is bypassed",
                         "to ensure all dirty chunks complete a snapshot within the cycle. Vanilla cycle length is 300s.",
                         "Minimum 5: 0 would disable the deadline guard entirely (remainingSeconds is always >= 0, so the",
                         "'remaining < guard' condition never holds), letting sustained low TPS defer every dirty chunk for a",
                         "whole cycle with no forced flush - a footgun that widens the loss window on kill/OOM, so the floor is 5.")
                .defineInRange("deadlineGuardSeconds", 30, 5, 240);

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

        LOAD_WORKER_THREADS = BUILDER
                .comment("v0.x: threads dedicated to off-thread chunk deserialization (async load).",
                         "Independent pool from chunk-save workers so a save backlog cannot starve loads.",
                         "Deserialize is largely single-thread bound; 2 covers typical loads.")
                .defineInRange("loadWorkerThreads", 2, 1, 8);

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
                         "  Saves the per-chunk event dispatch overhead but breaks any mod that hooks Save.",
                         "COMPAT WARNING: PARTIAL/DISABLED assemble sections without calling ChunkSerializer.write, so a mod",
                         "  that injects extra chunk NBT by mixing into ChunkSerializer.write directly (instead of via",
                         "  ChunkDataEvent.Save or Forge capabilities, both of which PARTIAL still honors) has its serialization",
                         "  bypassed and that data silently dropped every save, with no error. Flip to FULL if you run such a mod",
                         "  (FULL invokes the real write()).")
                .defineEnum("eventCompatMode", EventCompatMode.PARTIAL);

        BUILDER.pop();

        BUILDER.comment("v0.x: Async chunk load (off-thread ChunkSerializer.read)").push("load");

        LOAD_ENABLED = BUILDER
                .comment("Master switch for async chunk loading. When false, ChunkSerializer.read stays entirely on",
                         "the main thread (vanilla behavior), independent of the global 'enabled' save switch.",
                         "Default false: opt-in until the off-thread load path is proven on your modpack.",
                         "TAKES EFFECT AT STARTUP: the async-load mixins are applied (or skipped) based on this value at",
                         "load time (a MixinConfigPlugin reads it from disk), so the load-side bytecode is absent entirely",
                         "when off - this keeps it from clashing at startup with mods that rewrite scheduleChunkLoad /",
                         "ChunkSerializer.read (e.g. C2ME-forge). Because of that, toggling this on/off requires a server",
                         "restart to change mixin application; a runtime config hot-reload alone will not enable it.")
                .define("enabled", false);

        LOAD_EVENT_COMPAT_MODE = BUILDER
                .comment("Async chunk-load split mode (v0.x).",
                         "PARTIAL (default): pure NBT->object deserialization runs on a load worker; POI consistency,",
                         "  light section data, and ChunkDataEvent.Load are deferred back to the main thread.",
                         "FULL: the entire ChunkSerializer.read stays on the main thread (feature off for this dimension's",
                         "  loads, zero behavior delta). Use as a per-config safety fallback if a mod misbehaves.",
                         "There is intentionally no DISABLED: ChunkDataEvent.Load must always fire on the main thread,",
                         "  so the weakest mode is FULL (everything on main), never 'skip the event'.")
                .defineEnum("loadEventCompatMode", LoadCompatMode.PARTIAL);

        LOAD_MAX_RETRIES = BUILDER
                .comment("Times a load worker re-attempts ChunkSerializer.read on the worker thread after a parse",
                         "throws, before giving up and falling back to a vanilla main-thread read.",
                         "Off-thread parse failures are almost always transient (a Codec dispatch cache races a",
                         "concurrent decode despite the guard, or a DataFixer cache hiccup); a single retry usually",
                         "clears it without paying the main-thread fallback cost. 0 disables retry (fall back on first throw).",
                         "The terminal fallback re-reads the same region bytes on the main thread, so no data is lost",
                         "regardless of this value.")
                .defineInRange("loadMaxRetries", 1, 0, 5);

        LOAD_MAX_IN_FLIGHT = BUILDER
                .comment("v2.1: max chunk-load deserializations submitted to load workers concurrently. Caps how many",
                         "off-thread loads finish around the same time, so their main-thread POI/light replay + chunk",
                         "install can't all land in one tick (the burst that shows as 'Can't keep up' under fast flight +",
                         "high view-distance once parallel decoding feeds completions faster than the main thread installs).",
                         "Excess loads queue (workers stay fed) instead of flooding; the main thread drains replays over",
                         "ticks, trading a freeze for smooth per-chunk latency. Higher = more throughput but bigger per-tick",
                         "burst; lower = smoother but slower chunk arrival. Tune per server: raise until a burst reappears.")
                .defineInRange("maxInFlight", 128, 2, 1024);

        LOAD_POI_PREFETCH = BUILDER
                .comment("Tier A: read each loading chunk's POI region off-thread on the load worker, so the deferred",
                         "PoiManager.checkConsistencyWithBlocks no longer blocks the main thread on a synchronous POI",
                         "disk read during replay. The worker reads the POI bytes (IOWorker only, thread-safe) and hands",
                         "them to the main thread, which parses + populates the POI cache before replay; the consistency",
                         "check then hits the cache instead of reading disk. Profiled main-thread savings are large under",
                         "fast flight (the POI getOrLoad join wait is the dominant main-thread chunk-load cost).",
                         "Only takes effect when async load is on and mode is PARTIAL. Default true: set false to fall back",
                         "to vanilla main-thread POI reads if a POI-storage mod misbehaves. Hot-reloadable.")
                .define("asyncPoiPrefetch", true);

        BUILDER.pop();

        BUILDER.comment("Diagnostics").push("diagnostics");

        DIAGNOSTIC_LOGGING = BUILDER
                .comment("Periodically log queue depth, throughput, and latency percentiles to the server log.",
                         "For continuous monitoring prefer the Prometheus exporter below; set false to silence entirely.")
                .define("diagnosticLogging", true);

        DIAGNOSTIC_LOG_INTERVAL_TICKS = BUILDER
                .comment("How often diagnostic summaries are emitted, in server ticks (20 ticks = 1s).",
                         "Default 6000 (5 min). The dev-era default was 200 (10s) - far too chatty for production.")
                .defineInRange("diagnosticLogIntervalTicks", 6000, 20, 72000);

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
