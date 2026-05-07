package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.EntityCaptureProcedure;
import com.shinoyuki.betterautosave.core.snapshot.EntitySaveTask;
import com.shinoyuki.betterautosave.core.snapshot.EntitySnapshot;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.core.state.EntitySaveState;
import com.shinoyuki.betterautosave.core.state.EntitySaveStateAccess;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.entity.ChunkEntities;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.6: 拦 vanilla {@code EntityStorage.storeEntities(ChunkEntities)} HEAD,
 * 把主线程 entity.save 循环 + outer tag 构建 + IO 提交移到 BAS 异步管线.
 *
 * <p>结构与 {@link ChunkMapSaveMixin} 同构, 但 EntityStorage 是 per-level
 * 单例 (而非 ChunkAccess 那样 per-chunk), 因此 EntitySaveState 用
 * ConcurrentHashMap by packedPos 索引而不是 chunk 实例字段.
 *
 * <p>关服守卫: SaveScheduler.isShutdownMode() — 关服时让 vanilla 同步
 * 路径处理, 与 chunk 路径策略一致.
 *
 * <p>空 chunk 不接管: vanilla 内有 emptyChunks 优化 + null tag 写盘逻辑,
 * 绕开会破坏该优化, 直接放行让 vanilla 处理.
 */
@Mixin(EntityStorage.class)
public abstract class EntityStorageMixin implements EntitySaveStateAccess {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private IOWorker worker;

    @Unique
    private final ConcurrentHashMap<Long, EntitySaveState> betterautosave$entityStates = new ConcurrentHashMap<>();

    @Override
    public EntitySaveState betterautosave$getOrCreateEntityState(long packedPos, String dimensionId, long enqueueSequence) {
        return betterautosave$entityStates.computeIfAbsent(packedPos,
                k -> new EntitySaveState(k, dimensionId, enqueueSequence));
    }

    @Override
    public EntitySaveState betterautosave$getEntityState(long packedPos) {
        return betterautosave$entityStates.get(packedPos);
    }

    @Inject(method = "storeEntities(Lnet/minecraft/world/level/entity/ChunkEntities;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void betterautosave$interceptStoreEntities(ChunkEntities<Entity> chunkEntities, CallbackInfo ci) {
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }
        if (!BetterAutoSaveConfig.enabled()) {
            return;
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline == null || pipeline.isDegraded()) {
            return;
        }
        SaveScheduler scheduler = BetterAutoSaveCore.scheduler();
        if (scheduler == null || scheduler.isShutdownMode()) {
            return;
        }
        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        if (metrics == null) {
            return;
        }

        // 空 chunk 让 vanilla 处理 (emptyChunks 优化 + null tag 写盘).
        if (chunkEntities.isEmpty()) {
            return;
        }

        long packed = chunkEntities.getPos().toLong();
        String dimensionId = level.dimension().location().toString();
        long sequence = scheduler.nextEnqueueSequence();
        EntitySaveState state = betterautosave$getOrCreateEntityState(packed, dimensionId, sequence);

        EntitySaveState.Phase currentPhase = state.phase();
        if (currentPhase == EntitySaveState.Phase.CLEAN) {
            state.markDirty();
        } else if (currentPhase != EntitySaveState.Phase.DIRTY
                && currentPhase != EntitySaveState.Phase.FAILED) {
            // SNAPSHOTTING/SERIALIZING/IO_PENDING — 已在管线, 仅 mark mustDrain
            if (state.tryMarkMustDrain()) {
                metrics.incMustDrainPending();
            }
            metrics.recordEntitySubmitted();
            ci.cancel();
            return;
        } else if (currentPhase == EntitySaveState.Phase.FAILED) {
            // 用尽重试, 让 vanilla 兜底
            metrics.recordEntityFallback();
            return;
        }

        if (state.tryMarkMustDrain()) {
            metrics.incMustDrainPending();
        }

        try {
            if (!state.trySnapshot()) {
                // 已被另一线程接管 (DIRTY -> SNAPSHOTTING CAS 失败)
                metrics.recordEntitySubmitted();
                ci.cancel();
                return;
            }
            EntitySnapshot snapshot = EntityCaptureProcedure.capture(chunkEntities, level, state);
            EntitySaveTask task = new EntitySaveTask(snapshot, worker, metrics);
            metrics.incInFlightSerializing();
            metrics.recordEntitySubmitted();
            pipeline.entityWorkerQueue().offer(task);
            ci.cancel();
        } catch (Throwable t) {
            if (state.compareAndClearMustDrain()) {
                metrics.decMustDrainPending();
            }
            metrics.recordEntityFallback();
            LOGGER.error("[BetterAutoSave] EntityStorage.storeEntities async dispatch failed for {} dim={}, falling back to vanilla",
                    chunkEntities.getPos(), dimensionId, t);
        }
    }
}
