package com.shinoyuki.betterautosave.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.load.ChunkLoadTask;
import com.shinoyuki.betterautosave.core.load.LoadDeferredActions;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * v0.x 异步加载核心钩子: 用 MixinExtras {@link WrapOperation} 包住 {@code ChunkMap.scheduleChunkLoad} 内对
 * {@code ChunkSerializer.read} 的 INVOKESTATIC, 把整段 read 改投独立 load worker 跑纯解析 (read 内的 POI 一致性 /
 * 光照 section 数据 / ChunkDataEvent.Load 派发由 {@link ChunkSerializerLoadMixin} 的 redirect 截走, 主线程回放)。
 *
 * <p>vanilla 该 INVOKE 已跑在 {@code thenApplyAsync(..., this.mainThreadExecutor)} 续段里 (ChunkMap.java:575/578),
 * 即本 wrap 进入时已在主线程。WrapOperation 契约要求<b>同步</b>返回 ProtoChunk, 无法把返回值变 future, 故 PARTIAL
 * 路径在 wrap 内 {@code result.join()} 阻塞等 worker 解析完, 再在主线程回放延迟副作用后返回。结构性后果: join 期间
 * 主线程被钉住 (BlockableEventLoop 不处理其他 mailbox 任务), CPU 重活搬到 worker 核但主线程空转等待 —— 净收益
 * 取决于 join 等待能否被其他工作填充, 是设计第七节 "实际回收 %" 决策级风险, 须 M0 spark 实测 (见 docs 与本类
 * 末尾说明)。
 *
 * <p>闸门 (任一不过 -> {@code original.call} 即 vanilla 主线程 read, 零偏差兜底): 未安装 / load.enabled=false /
 * loadEventCompatMode=FULL / pipeline degraded / load 池未起线程 / wrap 不在主线程。worker 解析抛或 degraded 逐出
 * 时, future 异常完成, 同样回退 vanilla 主线程 read (read 只读磁盘字节, 重读零数据丢失)。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapLoadMixin {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    @WrapOperation(
            method = "scheduleChunkLoad",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;"
                            + "read(Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
                            + "Lnet/minecraft/world/level/ChunkPos;"
                            + "Lnet/minecraft/nbt/CompoundTag;)"
                            + "Lnet/minecraft/world/level/chunk/ProtoChunk;"))
    private ProtoChunk betterautosave$wrapRead(ServerLevel level, PoiManager poi, ChunkPos pos, CompoundTag tag,
                                               Operation<ProtoChunk> original) {
        if (!BetterAutoSaveCore.isInstalled() || !BetterAutoSaveConfig.loadEnabled()
                || BetterAutoSaveConfig.loadEventCompatMode() == ConfigSpec.LoadCompatMode.FULL) {
            return original.call(level, poi, pos, tag);
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline == null || pipeline.isDegraded() || !pipeline.isLoadPoolActive()) {
            return original.call(level, poi, pos, tag);
        }
        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        MinecraftServer server = pipeline.server();
        if (metrics == null || server == null) {
            return original.call(level, poi, pos, tag);
        }
        // 防御性主线程校验: vanilla thenApplyAsync(mainThreadExecutor) 保证本 wrap 在主线程, 但未来 coremod 若改
        // 续段线程, 在非主线程跑 join + 主线程回放会破坏 LoadDeferredActions 的线程契约。非主线程直接走 vanilla。
        if (!((com.shinoyuki.betterautosave.mixin.accessor.ChunkMapAccessor) this)
                .betterautosave$getMainThreadExecutor().isSameThread()) {
            return original.call(level, poi, pos, tag);
        }

        LoadDeferredActions deferred = new LoadDeferredActions();
        ChunkLoadTask task = new ChunkLoadTask(original, level, poi, pos, tag, deferred, metrics,
                BetterAutoSaveConfig.loadMaxRetries());
        metrics.recordChunkLoadSubmitted();
        pipeline.loadWorkerQueue().offer(task);
        try {
            // join 阻塞主线程等 worker 解析。worker 异常完成 (解析抛 / degraded 逐出) -> join 抛 CompletionException。
            ProtoChunk parsed = task.result().join();
            // 主线程顺序回放 worker 期间被 redirect 截走的 POI/光照/ChunkDataEvent.Load 副作用, 在该 ProtoChunk
            // 被 protoChunkToFullChunk 消费前落到 ServerLevel 全局状态 (与 vanilla read 同步完成等价)。回放抛
            // (第三方 Load listener 故障等) 自然冒泡进 vanilla scheduleChunkLoad 的 exceptionallyAsync, 与 vanilla
            // read 抛同样落 handleChunkLoadFailure, 不在此生吞。
            deferred.replayOnMainThread(server);
            metrics.recordChunkLoadCompleted();
            return parsed;
        } catch (Throwable t) {
            // 仅 worker 解析阶段失败回退 vanilla 主线程 read (replay 阶段的异常已在 try 内 replay 后, 不进本 catch
            // 重跑 —— replay 抛走上面的自然冒泡)。此处 original.call 在主线程重读: 若 chunk 真损坏会再抛, 冒泡进
            // vanilla handleChunkLoadFailure 建空 chunk, 与不装 BAS 等价。
            metrics.recordChunkLoadFallback();
            LOGGER.error("[BetterAutoSave] async load deserialize failed for chunk {} dim={}, falling back to vanilla "
                            + "main-thread read", pos, level.dimension().location(), t);
            return original.call(level, poi, pos, tag);
        }
    }
}
