package com.shinoyuki.betterautosave.core.load;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.core.worker.WorkerThreadAssert;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * 异步加载 PARTIAL 模式下投递到 load worker 的解析任务: 在 worker 线程上跑 vanilla {@code ChunkSerializer.read}
 * 的纯解析部分 (主线程独占副作用被 {@code ChunkSerializerLoadMixin} 的 redirect 截走排进 {@link LoadDeferredActions})。
 *
 * <p>{@code read} 经 MixinExtras {@link Operation} 调起 —— wrap 把原 INVOKESTATIC 的 {@code original} + 四个实参
 * 透传进来, 由本任务在 worker 上 {@code original.call(...)}。这把整段 read 搬上 worker (设计第四节钩子落点),
 * 而 read 内的 POI/光照/事件副作用因 redirect+capture 不在此 worker 执行。
 *
 * <p>结果经 {@link #result} future 交回阻塞 join 的主线程 wrap。worker 解析抛 (损坏区块等) 时 future 异常完成,
 * wrap 据此回退 vanilla 主线程 read (加载侧 fallback = 重读, read 只读磁盘字节不丢数据, 见设计第五节)。
 */
public final class ChunkLoadTask implements SaveTask {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final Operation<ProtoChunk> original;
    private final ServerLevel level;
    private final PoiManager poiManager;
    private final ChunkPos pos;
    private final CompoundTag tag;
    private final LoadDeferredActions deferred;
    private final SaveMetrics metrics;
    // 投递时快照的 worker 内重试上限 (config 是 volatile, 在任务构造点定格一次保证本任务重试次数确定)。
    private final int maxRetries;
    private final CompletableFuture<ProtoChunk> result = new CompletableFuture<>();

    public ChunkLoadTask(Operation<ProtoChunk> original, ServerLevel level, PoiManager poiManager, ChunkPos pos,
                         CompoundTag tag, LoadDeferredActions deferred, SaveMetrics metrics, int maxRetries) {
        this.original = original;
        this.level = level;
        this.poiManager = poiManager;
        this.pos = pos;
        this.tag = tag;
        this.deferred = deferred;
        this.metrics = metrics;
        this.maxRetries = maxRetries;
    }

    public CompletableFuture<ProtoChunk> result() {
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
        // 占用 gauge 在 codec 锁外 inc: 一个 task 从进 execute 起 (含阻塞等 LoadCodecGuard 的时间) 就算占着一条
        // load worker, 退 execute 时 dec。故峰值反映 "几条 worker 同时被加载任务占住", loadWorkerThreads 全占 = 锁
        // 竞争饱和的诊断信号 (与 loadWorkerQueueDepth 的排队积压正交)。dec 在最外 finally 覆盖成功/重试耗尽抛/异常全路径。
        metrics.incInFlightLoadParsing();
        // beginCapture 在重试循环外只挂一次: ThreadLocal 在整段 worker 解析期间恒指向本 sink, 各次尝试经
        // clearForRetry 复位 deferred 列表 (而非重挂 ThreadLocal), 故 redirect handler 任一尝试都命中同一 sink。
        LoadDeferredActions.beginCapture(deferred);
        try {
            // 尝试上限 = 首次 + maxRetries 次重试。off-thread 解析抛几乎都是瞬态 (Codec 分发缓存竞态 / DFU 抖动),
            // 在 worker 内先重试若干次再退主线程, 省去整段主线程 fallback read 的开销。终态仍抛 -> 由
            // SerializationWorker 升级到 onUnhandledError 异常完成 future -> wrap 退回 vanilla 主线程 read (零丢失)。
            int attempt = 0;
            while (true) {
                // 每次尝试前清空上一次失败尝试残留的延迟副作用, 防成功那次回放叠加陈旧 POI/光照写 (脏写)。
                deferred.clearForRetry();
                // 全段 read 在锁内: 串行化 static 分发 Codec 解码防 DFU 缓存竞态 (LoadCodecGuard 取舍说明)。
                LoadCodecGuard.lock();
                try {
                    ProtoChunk parsed = original.call(level, poiManager, pos, tag);
                    result.complete(parsed);
                    return;
                } catch (Throwable t) {
                    if (attempt >= maxRetries) {
                        // 重试耗尽: 上抛交 worker 走 onUnhandledError -> 主线程 fallback。不在此生吞。
                        throw t;
                    }
                    attempt++;
                    metrics.recordChunkLoadRetried();
                    LOGGER.warn("[BetterAutoSave] async load deserialize threw for chunk {} dim={} (attempt {}/{}), "
                                    + "retrying on worker before main-thread fallback",
                            pos, level.dimension().location(), attempt, maxRetries, t);
                } finally {
                    LoadCodecGuard.unlock();
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
        // 异常完成 future 唤醒阻塞 join 的主线程, 让 wrap 走 vanilla 主线程 read 兜底。completeExceptionally 幂等,
        // 即便 execute 已 complete 也不覆盖既有正常结果。
        result.completeExceptionally(cause);
    }

    /**
     * degraded 翻转时本 task 被逐出无人消费的 loadWorkerQueue 的善后。加载侧 abandon 语义最简: read 只读磁盘
     * 字节、不改任何持久状态, 故直接异常完成 future 即可 —— 阻塞 join 的主线程 wrap 据此回退 vanilla 主线程
     * read 同步重读, 零数据丢失 (无存盘侧 ChunkRecoveryQueue/isUnsaved 还原的对称需求, 见设计第四节)。
     * 线程安全 (仅 CompletableFuture.completeExceptionally), 可跑在死 worker 的 uncaught handler 线程。
     */
    public void abandonOnDegrade() {
        result.completeExceptionally(new IllegalStateException(
                "BetterAutoSave entered degraded mode; load task abandoned, falling back to vanilla main-thread read"));
    }
}
