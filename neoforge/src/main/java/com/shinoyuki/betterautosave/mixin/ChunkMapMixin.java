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
            boolean blocked = FlushHandler.handleFlush(scheduler.isShutdownMode(),
                    () -> pipeline.drainPending(BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L),
                    () -> LOGGER.info("[BetterAutoSave] /save-all flush during operation: dirty chunks queued to "
                            + "async pipeline (use /betterautosave status to watch progress)"));
            if (blocked) {
                // 关服 flush: handleFlush 已同步 drainPending 等 BAS in-flight 落盘. 不 cancel,
                // 让 vanilla saveAllChunks(true) 同步 flush 兜底剩余 dirty chunk. 此时
                // ChunkMapSaveMixin 的 isShutdownMode 守卫放行 vanilla save(), 原版 isUnsaved 语义
                // 自然收敛 do-while, 无死循环. 关服后已无后续 server tick, 入队的 ChunkSavePriority
                // 无 onServerTick 消费即永不 submitChunk 落盘, 故关服绝不能改走 enqueue+cancel.
                return;
            }
            // 运营中 /save-all flush: 不放行 vanilla. 运营态 ChunkMapSaveMixin 对 clean chunk
            // 也 setReturnValue(true) (vanilla 原返 false), 使 vanilla saveAllChunks(true) 的
            // do-while "本轮任一 save() 返 true 即再来一轮" 判据恒真 -> 主线程死循环 -> watchdog
            // 60s 强杀. 改走与周期 autosave 同一入队路径把 dirty chunk 交给 BAS 异步管线, 再
            // ci.cancel() 跳过 vanilla do-while. (不在 save 层改返回值: clean 分支
            // setReturnValue(true) 是 saveChunkIfNeeded cooldown 推进的承重件, @Inject(HEAD)
            // 无调用方上下文区分不开 saveAllChunks 与 saveChunkIfNeeded 两个来源.)
            SaveMetrics flushMetrics = BetterAutoSaveCore.metrics();
            if (flushMetrics == null) {
                return;
            }
            betterautosave$enqueueDirtyChunks(scheduler, flushMetrics);
            ci.cancel();
            return;
        }

        if (scheduler.isShutdownMode()) {
            return;
        }

        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        if (metrics == null) {
            return;
        }

        betterautosave$enqueueDirtyChunks(scheduler, metrics);
        ci.cancel();
    }

    /**
     * 把 visibleChunkMap 中 dirty 且本周期可访问的 LevelChunk 入队 BAS 异步管线.
     * 周期 autosave (flush=false) 与运营中 /save-all flush 共用同一入队路径, 数据安全语义一致.
     *
     * @return 实际成功入队的 chunk 数
     */
    private int betterautosave$enqueueDirtyChunks(SaveScheduler scheduler, SaveMetrics metrics) {
        long deadlineMillis = System.currentTimeMillis()
                + (long) BetterAutoSaveConfig.deadlineGuardSeconds() * 1000L;
        String dimensionId = level.dimension().location().toString();
        int enqueued = 0;

        for (ChunkHolder holder : visibleChunkMap.values()) {
            ChunkAccess chunk = holder.getLatestChunk();
            if (!(chunk instanceof LevelChunk)) {
                continue;
            }
            if (!chunk.isUnsaved()) {
                continue;
            }
            // 复刻 vanilla saveChunkIfNeeded 的 wasAccessibleSinceLastSave
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
            // 在途 (SNAPSHOTTING/SERIALIZING/IO_PENDING) 的 chunk 已有某代 IO 在飞。若仍无条件 enqueue 一个
            // priority, drain 时 trySnapshot CAS(DIRTY->SNAPSHOTTING) 必失败 (phase 非 DIRTY) -> SaveDispatcher
            // 静默 recordChunkFallback 丢弃, 既浪费 enqueue/drain/解析, 又虚高 fallback 计数掩盖真实 fallback 信号,
            // 且连 mustDrain 都不标 (关服 join 不为这种在途 chunk 多等)。与 eager 路径对齐: 在途短路跳过 enqueue,
            // 只 tryMarkMustDrain 让关服 join 知情。该 chunk 的最新代落盘由在飞 task 的 REQUEUE_DIRTY 接力
            // + 卸载碰撞登记的 pending 共同保证, 不依赖本次 enqueue。
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
        return enqueued;
    }
}
