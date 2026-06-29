package com.shinoyuki.betterautosave.core.load;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.core.worker.WorkerThreadAssert;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 异步加载 v2 PARTIAL 模式投递到 load worker 的 read-stage 任务: 在 worker 线程上跑 vanilla
 * {@code ChunkSerializer.read} 的纯解析部分 (read 内主线程独占副作用被 {@code ChunkSerializerLoadMixin} 的
 * redirect 截走排进本任务私有的 {@link LoadDeferredActions} sink)。
 *
 * <p>v2 与 v1 的差异: v1 经 MixinExtras {@code Operation} 调起被 wrap 的 read INVOKESTATIC, 结果经 future 交回
 * <b>阻塞 join</b> 的主线程 wrap; v2 钩点上移到 {@code thenApplyAsync}, read 由本任务<b>直接</b>调
 * {@code ChunkSerializer.read} (静态 read 的内层 redirect 不依赖调用形态, 仍按 {@code current()} 判据 defer),
 * 结果经 {@link #result} 这条 {@code CompletableFuture<LoadResult>} 交回 —— 主线程<b>不 join</b>, 而是
 * {@code thenApplyAsync(mainThreadExecutor)} compose 进 future 链续上 replay-stage。worker 并行 deserialize 时
 * 主线程继续 tick / 发别的区块。
 *
 * <p>截走的延迟副作用<b>不</b>靠 join 的 happens-before 隐式共享, 而是 worker read 成功那刻经
 * {@link LoadDeferredActions#drainCaptured()} 取快照、封进 {@link LoadResult} 作为 future 值显式带出
 * (见 LoadDeferredActions / LoadResult 线程模型注释)。worker 解析抛 (损坏区块等) 时 future 异常完成, 由
 * {@code ChunkMapLoadMixin} 的 replay-stage exceptionallyAsync 退回 vanilla 主线程 read (read 只读磁盘字节,
 * 重读零数据丢失, 见设计第五节)。
 */
public final class ChunkLoadTask implements SaveTask {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    // Tier A POI 预读 join 的有限超时上限。read 成功后在 worker 上 join 预读 future 取字节; 正常 IOWorker 几十 ms
    // 内完成, 但关服窄窗 (IOWorker 已 close 但提交闸门未拦到的极罕赛跑) 下 tryRead 的 future 可能永不完成 ->
    // 无界 join() 会把这条 load worker 永久钉死、令 drainPending 的 inFlightLoadParsing 永不归零拖死关服。有限超时
    // 退回 poiNbt=null (主线程 replay 按 vanilla 自读 POI, 零数据影响) + WARN 诊断, 是关服不挂死的兜底。
    private static final long POI_PREFETCH_JOIN_TIMEOUT_SECONDS = 10L;

    private final ServerLevel level;
    private final PoiManager poiManager;
    private final ChunkPos pos;
    private final CompoundTag tag;
    private final SaveMetrics metrics;
    // 投递时快照的 worker 内重试上限 (config 是 volatile, 在任务构造点定格一次保证本任务重试次数确定)。
    private final int maxRetries;
    // Tier A: 是否在 worker 上预读该列 POI region 字节带回主线程 (正常加载按 config; 在线回退恒 false ——
    // 回退的是 live chunk, POI 多半已在内存, 预读必被 populate 护栏跳过而纯浪费一次 IOWorker 读)。
    private final boolean poiPrefetch;
    private final CompletableFuture<LoadResult> result = new CompletableFuture<>();

    public ChunkLoadTask(ServerLevel level, PoiManager poiManager, ChunkPos pos, CompoundTag tag,
                         SaveMetrics metrics, int maxRetries, boolean poiPrefetch) {
        this.level = level;
        this.poiManager = poiManager;
        this.pos = pos;
        this.tag = tag;
        this.metrics = metrics;
        this.maxRetries = maxRetries;
        this.poiPrefetch = poiPrefetch;
    }

    public CompletableFuture<LoadResult> result() {
        return result;
    }

    @Override
    public String taskName() {
        return "chunk-load[" + level.dimension().location() + " " + pos + "]";
    }

    @Override
    public void execute() throws Exception {
        // SerializationWorker.run 已 markCurrentThreadAsWorker; 再断言一次把 "解析误跑在非 worker 线程" 显式炸出
        // (设计第六节断言护栏, 与存盘 worker 入口对称)。
        WorkerThreadAssert.assertOnWorkerThread();
        long t0 = System.nanoTime();
        // 占用 gauge inc: 一个 task 从进 execute 起就算占着一条 load worker, 退 execute 时 dec。v2.1 L1 后 read 整段
        // 无锁并行, 故峰值到 loadWorkerThreads 表 "几条 worker 同时在真并行解析" (而非 v2 那样卡在整段读锁上); 仅结构
        // 解码切片仍经 LoadCodecGuard 串行, 不计入本 gauge 的占用语义。与 loadWorkerQueueDepth 的排队积压正交。dec 在
        // 最外 finally 覆盖成功/重试耗尽抛/异常全路径。
        metrics.incInFlightLoadParsing();
        // Tier A: read 之前先 fire 该列 POI region 异步读盘, 与下面区块反序列化在本 worker 上并行 (loadAsync 立即返回
        // future, 不阻塞)。read 成功后再 join 取字节。只触底层 IOWorker (线程安全), 不碰 storage, 故 off-thread 安全。
        CompletableFuture<Optional<CompoundTag>> poiFuture = poiPrefetch
                ? ((SectionStorageLoadAccess) poiManager).betterautosave$readColumnNbtFuture(pos)
                : null;
        // 每次 read 一个 worker 线程私有的 sink (绝不池级单例): beginCapture 在重试循环外只挂一次, ThreadLocal 在
        // 整段 worker 解析期间恒指向本 sink, 各次尝试经 clearForRetry 复位捕获列表 (而非重挂 ThreadLocal), 故 redirect
        // handler 任一尝试都命中同一 sink。worker 是池化复用线程, endCapture 的 remove 必须在 finally 确保下一区块的
        // read 不误命中本区块残留的 ThreadLocal sink。
        LoadDeferredActions sink = new LoadDeferredActions();
        LoadDeferredActions.beginCapture(sink);
        try {
            // 尝试上限 = 首次 + maxRetries 次重试。off-thread 解析抛几乎都是瞬态 (Codec 分发缓存竞态 / DFU 抖动),
            // 在 worker 内先重试若干次再退主线程, 省去整段主线程 fallback read 的开销。终态仍抛 -> 由
            // SerializationWorker 升级到 onUnhandledError 异常完成 future -> replay-stage exceptionallyAsync 退回
            // vanilla 主线程 read (零丢失)。
            int attempt = 0;
            while (true) {
                // 每次尝试前清空上一次失败尝试残留的延迟副作用, 防成功那次带出叠加陈旧 POI/光照写 (脏写)。
                sink.clearForRetry();
                try {
                    // v2.1 L1: read 整段不再持锁。worker 间唯一跨线程竞态 (结构拼图解码经共享 dispatch Codec /
                    // RegistryOps 缓存) 由 ChunkSerializerLoadMixin 的 @WrapOperation 在 read 内部精确包住
                    // unpackStructureStart/unpackStructureReferences 时才取 LoadCodecGuard, 其余 section/调色板/biome/
                    // heightmap/方块实体/ForgeCaps 解码 thread-confined 无锁并行 (取代 v2 "一次只解一个" 的粗粒度串行)。
                    ProtoChunk parsed = ChunkSerializer.read(level, poiManager, pos, tag);
                    // 成功那刻取走截走副作用的快照, 与 chunk 一并封进 LoadResult 显式交给 future (不靠 join 隐式共享)。
                    // 此刻本次 read 的 redirect 全部命中完毕 (结构解码锁也已在 read 内释放), 捕获列表完整。
                    List<Runnable> deferred = sink.drainCaptured();
                    // Tier A: read 已成功, 在 worker 上 join 预读的 POI 字节 (worker 闲, 阻塞无害)。POI 预读失败
                    // 严格隔离: 退回 null 让主线程 replay 按 vanilla 自己读 POI (零数据影响), 绝不让 POI 故障触发
                    // 上面区块 read 的重试 —— 故此 join 的 try/catch 在区块 read 成功之后、与 read 的 catch 分离。
                    Optional<CompoundTag> poiNbt = null;
                    if (poiFuture != null) {
                        try {
                            // 有限超时 join (非无界 join()): 关服窄窗下 IOWorker 已关、tryRead 的 future 可能永不完成,
                            // 无界等会把本 load worker 永久钉死拖死关服 (inFlightLoadParsing 永不归零)。超时即放弃预读。
                            poiNbt = poiFuture.get(POI_PREFETCH_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        } catch (TimeoutException poiTimeout) {
                            LOGGER.warn("[BetterAutoSave] async POI prefetch timed out after {}s for chunk {} dim={} "
                                            + "(IOWorker likely closing during shutdown), main thread will read POI inline",
                                    POI_PREFETCH_JOIN_TIMEOUT_SECONDS, pos, level.dimension().location());
                        } catch (Throwable poiErr) {
                            LOGGER.warn("[BetterAutoSave] async POI prefetch failed for chunk {} dim={}, main thread "
                                    + "will read POI inline", pos, level.dimension().location(), poiErr);
                        }
                    }
                    result.complete(new LoadResult(parsed, deferred, poiNbt));
                    return;
                } catch (Throwable t) {
                    if (attempt >= maxRetries) {
                        // 重试耗尽: 上抛交 worker 走 onUnhandledError -> replay-stage fallback。不在此生吞。
                        throw t;
                    }
                    attempt++;
                    metrics.recordChunkLoadRetried();
                    LOGGER.warn("[BetterAutoSave] async load deserialize threw for chunk {} dim={} (attempt {}/{}), "
                                    + "retrying on worker before main-thread fallback",
                            pos, level.dimension().location(), attempt, maxRetries, t);
                }
            }
        } finally {
            LoadDeferredActions.endCapture();
            metrics.recordLoadDeserializeNs(System.nanoTime() - t0);
            metrics.decInFlightLoadParsing();
        }
    }

    @Override
    public void onUnhandledError(Throwable cause) {
        // execute 抛已被 SerializationWorker 升级到这里; result 此刻仍未完成 (complete 在 try 内 read 成功后才调)。
        // 异常完成 future 让 replay-stage 的 thenApplyAsync 短路、exceptionallyAsync 退回 vanilla 主线程 read 兜底。
        // completeExceptionally 幂等, 即便 execute 已 complete 也不覆盖既有正常结果。
        result.completeExceptionally(cause);
    }

    /**
     * degraded 翻转时本 task 被逐出无人消费的 loadWorkerQueue 的善后。加载侧 abandon 语义最简: read 只读磁盘
     * 字节、不改任何持久状态, 故直接异常完成 future 即可 —— replay-stage 的 exceptionallyAsync 据此退回 vanilla
     * 主线程 read 重读, 零数据丢失 (无存盘侧 ChunkRecoveryQueue/isUnsaved 还原的对称需求, 见设计第四节)。
     * 线程安全 (仅 CompletableFuture.completeExceptionally), 可跑在死 worker 的 uncaught handler 线程。
     */
    public void abandonOnDegrade() {
        result.completeExceptionally(new IllegalStateException(
                "BetterAutoSave entered degraded mode; load task abandoned, falling back to vanilla main-thread read"));
    }
}
