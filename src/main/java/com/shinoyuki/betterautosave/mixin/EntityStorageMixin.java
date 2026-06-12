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
import com.shinoyuki.betterautosave.core.state.EntityStateMap;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.mixin.accessor.EntityStorageAccessor;
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

    // per-level 状态表收口到 EntityStateMap, 支持 CLEAN_LANDED 终态安全剔除,
    // 防 EntityStorage 单例的状态表随进程运行无界增长.
    @Unique
    private final EntityStateMap betterautosave$entityStates = new EntityStateMap();

    @Override
    public EntitySaveState betterautosave$getOrCreateEntityState(long packedPos, String dimensionId, long enqueueSequence) {
        return betterautosave$entityStates.getOrCreate(packedPos, dimensionId, enqueueSequence);
    }

    @Override
    public EntitySaveState betterautosave$getEntityState(long packedPos) {
        return betterautosave$entityStates.get(packedPos);
    }

    @Override
    public void betterautosave$evictEntityStateIfClean(long packedPos, EntitySaveState expected) {
        betterautosave$entityStates.evictIfClean(packedPos, expected);
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
            // SNAPSHOTTING/SERIALIZING/IO_PENDING — 已有某代 IO 在飞。
            // mustDrain 先于接力快照登记置位 (gauge 不变式: pendingSnapshot 非空 -> mustDrain 恒真)。
            if (state.tryMarkMustDrain()) {
                metrics.incMustDrainPending();
            }
            // 这次 storeEntities 几乎必然来自 vanilla processChunkUnload —— 它 storeEntities 后立即把实体驱逐
            // 出内存且该坐标永不再 storeEntities, 故这份 chunkEntities 是最新实体列表的唯一副本。直接 ci.cancel
            // 丢弃它信任在飞旧代快照不行: 实体增量 (新放的命名生物/盔甲架/展示框等) 会永久静默丢失。
            //
            // 关键非对称 (与 chunk 路径): entity 没有 setUnsaved->markDirty 这样的独立 generation 驱动源,
            // generation 只在 storeEntities 撞 CLEAN 时推进。故必须在此**显式 markDirty 推 generation**,
            // 否则在飞那代落地 generation==inFlightGeneration 误判 CLEAN_LANDED (假成功 + 误 evict), 接力链断。
            // markDirty 后对最新 chunkEntities 纯 capture (不碰在飞那代 phase/inFlightGeneration) 登记接力槽。
            state.markDirty();
            try {
                EntitySnapshot pending = EntityCaptureProcedure.capturePending(chunkEntities, level, state);
                // entity 路径不迁 chunk 的 SlotWord 三态协议 (无 dispatchSaveEvent 窗口, 不需要 PREPARING/READY
                // 两相隔离一个不可消费态), 但接力槽与终态回调跑在两条线程上, 仍需一道顺序纪律杜绝 "回调终态取槽" 与
                // "主线程登记" 互相看不见。用既有 atomic 建立写后读对偶 (Dekker 式双向检查):
                //   主线程侧: 先写 pending 槽 (registerPendingSnapshot), 再重读 phase。
                //   回调侧:   先写 phase 终态 (ioCompletedSuccessfully/ioFailed 内 phase.set), 再 getAndSet 取槽。
                // 两侧各自先写后读, 故任一交错至少一方看见对方: 若回调取槽早于主线程写槽, 主线程重读 phase 必见
                // 回调写定的终态 (CLEAN/DIRTY/FAILED) -> 主线程取回自己刚放的 pending 自踢; 若主线程写槽早于回调取槽,
                // 回调 getAndSet 必见这份 pending -> 回调接力。双方都看见时以 getAndSet 析构语义裁定唯一消费者 (谁取到
                // 谁负责接力, 另一方取回 null 不再动作), 防双投。这道纪律消除两个无主丢失窗口:
                //   1) 回调 stale 读旧代判 CLEAN_LANDED 后, 若不取槽直接 evict, 会把主线程刚 register 的更新代 pending
                //      随状态对象送 GC (entity 无恢复队列无 isUnsaved 门, 永久静默丢失);
                //   2) 回调 REQUEUE_DIRTY 取槽时主线程 register 尚未发生, 取到 null 跳过重投, 主线程随后 register 落进
                //      phase=DIRTY 死状态 -> 条目滞留 + mustDrainPending 永久 +1。
                state.registerPendingSnapshot(pending);
                // 重读 phase: 不在在飞态即说明回调已写定终态并可能已取槽。getAndSet 自取回自己刚放的 pending (防与
                // 回调取槽双投): 取回非空 -> 主线程自踢接力; 取回 null -> 回调已接管, 主线程不再动作。
                EntitySaveState.Phase recheck = state.phase();
                boolean inFlight = recheck == EntitySaveState.Phase.SNAPSHOTTING
                        || recheck == EntitySaveState.Phase.SERIALIZING
                        || recheck == EntitySaveState.Phase.IO_PENDING;
                if (!inFlight) {
                    EntitySnapshot taken = state.takePendingSnapshot();
                    if (taken != null) {
                        // 回调已终态退出, 不会再消费这份 pending。主线程自踢接力把更新代落盘。回调终态若已清
                        // mustDrain (CLEAN_LANDED/FAILED_TERMINAL), 接力在途须恢复并补 inc gauge (关服 join 继续等);
                        // 若 mustDrain 仍真 (REQUEUE_DIRTY 不清), tryMark 返 false 不重复 inc。接力链终态唯一清+dec。
                        if (state.tryMarkMustDrain()) {
                            metrics.incMustDrainPending();
                        }
                        pipeline.reofferEntityPendingFromMainThread(worker, this, state, taken);
                    }
                }
            } catch (Throwable t) {
                // 纯 capture 抛 (单个 entity.save 异常已在 capture 内吞, 这里多为 OOM 等): 尚未登记 pending,
                // 接力槽空。entity 路径无 dispatchSaveEvent, 故无 chunk 侧 register->dispatch 的 TOCTOU 窗口;
                // 但 catch 的 gauge 配平与 chunk 同构: 上方 markDirty 已推 generation, 在飞旧代 IO 落地必判
                // REQUEUE_DIRTY (EntitySaveState:118-129 恒不等) 永不清 mustDrain, 槽空回调取 null 不重投 ->
                // 此后无路径清 mustDrain。故必须在此 compareAndClearMustDrain 亲自配平 (撤销路径不留 mustDrain
                // 正偏移)。退回信任在飞旧代 (本次最新实体增量丢失), 记录可见。
                if (state.compareAndClearMustDrain()) {
                    metrics.decMustDrainPending();
                }
                LOGGER.error("[BetterAutoSave] pending relay capture failed for in-flight entity chunk {} dim={}; "
                                + "trusting in-flight snapshot (latest entity increment may be lost)",
                        chunkEntities.getPos(), dimensionId, t);
            }
            // in-flight 碰撞分支也必须复刻 vanilla storeEntities 非空分支末尾的 emptyChunks.remove 副作用
            // (与常规 dispatch 路径 line ~174 同源)。
            // 该分支 ci.cancel() 短路前若漏 remove: 若该坐标在飞期间曾走过空 chunk 早返回路径 (vanilla 空分支
            // emptyChunks.add), 残留的 stale 条目使后续 unload->reload 时 vanilla loadEntities 命中 emptyChunks
            // 快速路径直接返空 chunk, 已落盘 entity 被忽略 -> 静默丢失 (entity 不可重建, 比 POI 更重)。
            ((EntityStorageAccessor) (Object) this).betterautosave$getEmptyChunks().remove(packed);
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
            // 传 this (EntitySaveStateAccess) 让 task 在 CLEAN_LANDED 终态剔除 per-level 状态 map 条目, 防无界增长.
            // 传接力重投 sink, 让 REQUEUE_DIRTY 落地时取 pending 快照重投 (sink 绑 worker + stateOwner, 由 pipeline 构造)。
            EntitySaveTask task = new EntitySaveTask(snapshot, worker, metrics, this,
                    pipeline.entityPendingReoffer(worker, this));
            metrics.incInFlightSerializing();
            metrics.recordEntitySubmitted();
            pipeline.entityWorkerQueue().offer(task);
            // 复制 vanilla 在 storeEntities 非空分支末尾的副作用
            // (vanilla EntityStorage.java:108 emptyChunks.remove). 漏调会让该 chunk
            // 后续 unload→reload 时 vanilla loadEntities 命中 emptyChunks 快速路径
            // 直接返空 chunk, 实际已落盘的 entity 数据被忽略 → 静默丢失.
            ((EntityStorageAccessor) (Object) this).betterautosave$getEmptyChunks().remove(packed);
            ci.cancel();
        } catch (Throwable t) {
            if (state.compareAndClearMustDrain()) {
                metrics.decMustDrainPending();
            }
            // 同 ChunkMapSaveMixin, capture 抛后 phase 已推到 SNAPSHOTTING/SERIALIZING. 不复位会让该 chunk
            // entity 后续永远走早 return 路径既不入 BAS 也不走 vanilla, 数据永久丢失. resetAfterFallback 归零状态机.
            //
            // 与 ChunkMapSaveMixin 的非对称 (勿"补齐"): chunk 路径 catch 额外
            // setUnsaved(true) 是因为 vanilla ChunkMap.save 续跑时按 isUnsaved 门过滤,
            // 不还原门就跳过同步写. entity 路径无 isUnsaved 等价门 — storeEntities 续跑
            // 无条件序列化写盘 (vanilla EntityStorage 不看任何 dirty 标志), resetAfterFallback
            // 归零 phase 已完整. 这里若多调一次 setUnsaved 既无对象可调 (EntityStorage 非
            // per-chunk) 也无意义.
            state.resetAfterFallback();
            metrics.recordEntityFallback();
            LOGGER.error("[BetterAutoSave] EntityStorage.storeEntities async dispatch failed for {} dim={}, falling back to vanilla",
                    chunkEntities.getPos(), dimensionId, t);
        }
    }
}
