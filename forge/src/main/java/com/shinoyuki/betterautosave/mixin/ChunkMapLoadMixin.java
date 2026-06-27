package com.shinoyuki.betterautosave.mixin;

import com.mojang.datafixers.util.Either;
import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.load.ChunkLoadTask;
import com.shinoyuki.betterautosave.core.load.LoadDeferredActions;
import com.shinoyuki.betterautosave.core.load.LoadInFlightLimiter;
import com.shinoyuki.betterautosave.core.load.LoadResult;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.mixin.accessor.ChunkMapAccessor;
import com.shinoyuki.betterautosave.mixin.accessor.SectionStorageLoadAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 异步加载 v2 核心钩子 (future 链, 非阻塞): {@code @Redirect} 截走 {@code ChunkMap.scheduleChunkLoad} 内
 * {@code readChunk().thenApply(Status 过滤).thenApplyAsync(read+markPosition, mainThreadExecutor)} 那次
 * {@code thenApplyAsync(Function, Executor)} 调用 (ChunkMap.java:575/584, 反编译金源 bytecode offset 25 唯一一处
 * {@code thenApplyAsync(Function,Executor)}), 在 handler 内重建两段 future 链:
 * <ul>
 *   <li>worker read-stage: 把 vanilla 那段 read lambda 拆出的纯解析投到 load worker ({@link ChunkLoadTask},
 *       read 内 POI/光照/ChunkDataEvent.Load 副作用由 {@link ChunkSerializerLoadMixin} 的 redirect 截走);</li>
 *   <li>main replay-stage: worker 解析完经 {@code thenApplyAsync(mainThreadExecutor)} 在主线程回放截走的副作用 +
 *       {@code markPosition} + {@code Either.left(chunk)}。</li>
 * </ul>
 *
 * <p>v2 相对 v1 的根本差异: v1 用 {@code @WrapOperation} 包内层 read 的 INVOKESTATIC, MixinExtras
 * {@code Operation<ProtoChunk>} 契约要求 handler <b>同步</b>返回 ProtoChunk, 无法把返回值变 future, 故 v1 被迫
 * {@code result.join()} <b>阻塞</b>主线程等 worker —— 20x 飞行压测 profile 实证主线程本就 ~40% 在 park 等管线,
 * join 只是把 "主线程自己 deserialize" 换成 "主线程 park 等 worker deserialize", 墙钟一样、Can't-keep-up stall
 * 照憋。v2 钩点上移到 {@code thenApplyAsync}, handler 把 "一段 future" 换成 "两段 future", 全程
 * {@code thenComposeAsync}/{@code thenApplyAsync} 链接<b>无 join/get</b>: 主线程跑完 handler 返回的是未完成 future,
 * 直接交回 vanilla 链继续 tick / 发别的区块, worker 并行 deserialize, 真正消除 stall。
 *
 * <p>闸门 (任一不过 -> 原样调 {@code prior.thenApplyAsync(readLambda, mainExec)} 即 vanilla 单段主线程 read,
 * 零偏差兜底): 未安装 / load.enabled=false / loadEventCompatMode=FULL / pipeline degraded / load 池未起线程。
 *
 * <p>失败贯通: worker 解析抛 (重试耗尽) 或 degraded 逐出时 {@code ChunkLoadTask.result()} 异常完成, replay-stage
 * 的 {@code thenApplyAsync} 短路, 续上的 {@code exceptionallyAsync(mainThreadExecutor)} 在主线程重读 vanilla read
 * 兜底 (read 只读磁盘字节, 重读零数据丢失); 主线程重读再抛 (真损坏区块) 自然冒泡进 vanilla scheduleChunkLoad 自己
 * 的 {@code exceptionallyAsync(handleChunkLoadFailure)}, 与 vanilla read 抛同样建空 chunk。三个 vanilla 失败分支
 * (isChunkDataValid / handleChunkLoadFailure / createEmptyChunk) 一行不改即生效 (见设计第四节)。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapLoadMixin {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private PoiManager poiManager;

    @Redirect(method = "scheduleChunkLoad",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;"
                            + "thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)"
                            + "Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> betterautosave$asyncLoadChain(
            CompletableFuture<Optional<CompoundTag>> prior,
            Function<Optional<CompoundTag>, Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> readLambda,
            Executor mainExec,
            ChunkPos pos) {
        if (!BetterAutoSaveCore.isInstalled() || !BetterAutoSaveConfig.loadEnabled()
                || BetterAutoSaveConfig.loadEventCompatMode() == ConfigSpec.LoadCompatMode.FULL) {
            return prior.thenApplyAsync(readLambda, mainExec);
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline == null || pipeline.isDegraded() || !pipeline.isLoadPoolActive()) {
            return prior.thenApplyAsync(readLambda, mainExec);
        }
        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        MinecraftServer server = pipeline.server();
        if (metrics == null || server == null) {
            return prior.thenApplyAsync(readLambda, mainExec);
        }
        LoadInFlightLimiter limiter = pipeline.loadInFlightLimiter();

        // 第一段编排仍在 mainThreadExecutor 起跳 (廉价: 只判 Optional + offer 队列, 不阻塞)。prior 是 :566 Status
        // 过滤 thenApply 产出的 future, 此刻可能尚未完成 (readChunk 后台 IO), 故经 thenComposeAsync 续接绝不在 handler
        // 内同步 join prior。
        return prior.thenComposeAsync(opt -> {
            if (opt.isEmpty()) {
                // Status 缺失被 :566 过滤成空 -> 等价 vanilla :582 createEmptyChunk。直接在主线程跑 vanilla readLambda
                // (它对空 Optional 走 createEmptyChunk), 零偏差兜底, 不投 worker。
                return CompletableFuture.completedFuture(readLambda.apply(opt));
            }
            CompoundTag tag = opt.get();
            // L2: 取在飞许可后再提交 worker (满则排队 -> workers 不饿死, 但同 tick 完成数被压到 ~maxInFlight, 泄掉
            // 多 worker 并行解码后 replay+install 全砸一个 tick 的突发)。许可在末尾 whenComplete 的 success 与 fallback
            // 两路都 release —— 漏一次即永久占一个名额, max 个全泄漏后所有加载排队饿死。死锁安全见 LoadInFlightLimiter。
            return limiter.acquire().<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>thenComposeAsync(ignored -> {
                ChunkLoadTask task = new ChunkLoadTask(level, poiManager, pos, tag, metrics,
                        BetterAutoSaveConfig.loadMaxRetries(), BetterAutoSaveConfig.loadPoiPrefetch());
                metrics.recordChunkLoadSubmitted();
                pipeline.loadWorkerQueue().offer(task);
                // worker read-stage -> main replay-stage。replay 与 Either.left(chunk) 同在一个 mainThreadExecutor 任务内
                // 顺序执行: replay 把 worker 截走的 POI/光照/事件副作用落回主线程, 严格先于该任务 return -> 严格先于
                // scheduleChunkLoad 返回的 EMPTY future 完成 -> 严格先于下游 protoChunkToFullChunk 消费 ProtoChunk
                // (CompletableFuture complete -> 续段 happens-before), 与 vanilla read 内同步完成 POI/光照后才返回等价。
                return task.result().<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>thenApplyAsync(loadResult -> {
                    // Tier A: replay 前先用 worker 预读的 POI 字节填缓存 (poiColumnNbt != null = 已预读)。填好后
                    // 紧接的 deferred checkConsistencyWithBlocks -> getOrLoad 命中缓存 O(1), 不在主线程阻塞读盘。
                    // 必须严格先于 replayOnMainThread (deferred 里含 checkConsistency)。null = 未预读, 走 vanilla。
                    if (loadResult.poiColumnNbt() != null) {
                        ((SectionStorageLoadAccess) poiManager)
                                .betterautosave$populateColumnOnMain(pos, loadResult.poiColumnNbt());
                    }
                    LoadDeferredActions.replayOnMainThread(server, loadResult.deferred());
                    ((ChunkMapAccessor) this).betterautosave$markPosition(pos,
                            loadResult.chunk().getStatus().getChunkType());
                    metrics.recordChunkLoadCompleted();
                    return Either.left(loadResult.chunk());
                }, mainExec).exceptionallyAsync(throwable -> {
                    // worker 解析失败 (重试耗尽) / degraded 逐出 -> 异常完成短路到这里。exceptionallyAsync(mainExec) 强制
                    // fallback 重读跑在主线程 (绝不能跑在完成异常的 worker 线程: 那样 readLambda 内 POI/光照 redirect 因
                    // current()==null 走 inline -> worker 跨线程写 PoiManager 崩)。主线程重读 = vanilla 本来的行为, 仅失败
                    // 罕路径, 墙钟与 vanilla 主线程 read 相当。readLambda.apply(opt) 含 markPosition + Either.left, opt 非空
                    // 走真实 read; 若区块真损坏再抛, 冒泡进 vanilla 自己的 exceptionallyAsync(handleChunkLoadFailure) 建空
                    // chunk, 与不装 BAS 等价。
                    metrics.recordChunkLoadFallback();
                    LOGGER.error("[BetterAutoSave] async load deserialize failed for chunk {} dim={}, falling back to "
                            + "vanilla main-thread read", pos, level.dimension().location(), throwable);
                    return readLambda.apply(opt);
                }, mainExec);
            }, mainExec).whenComplete((result, throwable) -> limiter.release());
        }, mainExec);
    }
}
