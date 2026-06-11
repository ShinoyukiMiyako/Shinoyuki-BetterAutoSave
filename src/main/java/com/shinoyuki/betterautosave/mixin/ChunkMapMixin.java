package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.scheduler.ChunkSavePriority;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.FlushHandler;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.state.ChunkSaveStateAccess;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void betterautosave$interceptSaveAllChunks(boolean flush, CallbackInfo ci) {
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }
        if (!BetterAutoSaveConfig.enabled()) {
            return;
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline.isDegraded()) {
            return;
        }

        SaveScheduler scheduler = BetterAutoSaveCore.scheduler();
        if (flush) {
            // Major 修复: 区分关服 flush 与运营中 /save-all flush.
            // - 关服 (isShutdownMode): 必须同步 drainPending 等 BAS in-flight 落盘,
            //   再 return 让 vanilla 同步 flush 兜底剩余, 语义要求不能丢.
            // - 运营中 (!isShutdownMode): drainPending 内 Thread.sleep(50) 循环最多卡主线程
            //   shutdownTimeoutSeconds 秒 ("Can't keep up" + 玩家卡顿). 不阻塞 — 直接 return
            //   让 vanilla 自己的同步 flush 处理当前 dirty chunk (有界, 玩家显式 flush 可接受),
            //   BAS 已 dispatch 的异步任务继续由 worker + tick drain 推进.
            //   saveAllChunks 返回 void, vanilla 调用方不依赖 BAS 是否 drain 完, 立即 return 安全.
            FlushHandler.handleFlush(scheduler.isShutdownMode(),
                    () -> pipeline.drainPending(BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L),
                    () -> LOGGER.info("[BetterAutoSave] /save-all flush during operation: not blocking main thread; "
                            + "async saves continue in background (use /betterautosave status to watch progress)"));
            return;
        }

        if (scheduler.isShutdownMode()) {
            return;
        }

        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        if (metrics == null) {
            return;
        }

        long deadlineMillis = System.currentTimeMillis()
                + (long) BetterAutoSaveConfig.deadlineGuardSeconds() * 1000L;
        String dimensionId = level.dimension().location().toString();
        int enqueued = 0;

        for (ChunkHolder holder : visibleChunkMap.values()) {
            ChunkAccess chunk = holder.getLastAvailable();
            if (!(chunk instanceof LevelChunk)) {
                continue;
            }
            if (!chunk.isUnsaved()) {
                continue;
            }
            // v0.7.1 修复 (M11): 复刻 vanilla saveChunkIfNeeded 的 wasAccessibleSinceLastSave
            // 守卫 (vanilla ChunkMap.java:799). 不可访问的 chunk (unload 后还在 visibleChunkMap)
            // 跳过 → 减少不必要的 capture + IO. vanilla 在 save 后调 refreshAccessibility 翻
            // 标志位, BAS 入队成功后同步调一次保持行为对齐.
            if (!holder.wasAccessibleSinceLastSave()) {
                continue;
            }
            ChunkPos pos = chunk.getPos();
            long packed = pos.toLong();
            long sequence = scheduler.nextEnqueueSequence();
            ChunkSaveState state = ((ChunkSaveStateAccess) chunk).betterautosave$getOrCreateState(
                    packed, dimensionId, sequence);
            ChunkSaveState.Phase phase = state.phase();
            // v0.10.2 修复 (autosave 通道静默吞咽, gaps[1] Major): 在途 (SNAPSHOTTING/SERIALIZING/IO_PENDING)
            // 的 chunk 已有某代 IO 在飞, 旧逻辑仍无条件 enqueue 一个 priority, drain 时 trySnapshot
            // CAS(DIRTY->SNAPSHOTTING) 必失败 (phase 非 DIRTY) -> SaveDispatcher 静默 recordChunkFallback
            // 丢弃, 既浪费 enqueue/drain/解析, 又虚高 fallback 计数掩盖真实 fallback 信号, 且连 mustDrain 都
            // 不标 (关服 join 不为这种在途 chunk 多等)。改为与 eager 路径对齐: 在途短路跳过 enqueue, 只
            // tryMarkMustDrain 让关服 join 知情。该 chunk 的最新代落盘由在飞 task 的 REQUEUE_DIRTY 接力
            // (commit: 接力快照机制) + 卸载碰撞登记的 pending 共同保证, 不依赖本次 enqueue。
            if (state.isInFlight()) {
                if (state.tryMarkMustDrain()) {
                    metrics.incMustDrainPending();
                }
                continue;
            }
            if (phase == ChunkSaveState.Phase.CLEAN) {
                state.markDirty();
            }
            ChunkSavePriority priority = new ChunkSavePriority(packed, dimensionId, sequence, deadlineMillis, 0.0);
            if (scheduler.enqueueChunk(priority)) {
                enqueued++;
                holder.refreshAccessibility();
            }
        }
        if (enqueued > 0) {
            LOGGER.info("[BetterAutoSave] autosave intercepted @ {}", dimensionId);
            LOGGER.info("[BetterAutoSave]   |- mode: {}", BetterAutoSaveConfig.eventCompatMode());
            LOGGER.info("[BetterAutoSave]   `- enqueued {} dirty chunks (deadline +{}s)",
                    enqueued, BetterAutoSaveConfig.deadlineGuardSeconds());
        } else {
            LOGGER.debug("[BetterAutoSave] autosave intercepted @ {} (no dirty chunks)", dimensionId);
        }
        ci.cancel();
    }
}
