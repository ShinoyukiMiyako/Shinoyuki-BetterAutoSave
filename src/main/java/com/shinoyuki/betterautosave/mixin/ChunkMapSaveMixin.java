package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.dispatch.SaveDispatcher;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.ChunkCaptureProcedure;
import com.shinoyuki.betterautosave.core.snapshot.ChunkSnapshot;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.state.ChunkSaveStateAccess;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v0.4: 拦 vanilla {@code ChunkMap.save(ChunkAccess)} HEAD,把 unload 路径
 * (scheduleUnload 内 lambda) 与 eager save 路径 (saveChunkIfNeeded 内 cooldown
 * 触发) 一并接管走 {@link SnapshotPipeline} 异步管线, 主线程 NBT 编码消除。
 *
 * <p>注意 vanilla 还有第三个调用点: {@code saveAllChunks(true)} (关服 flush)
 * 中的 {@code .filter(this::save)}. 该路径必须走 vanilla 同步, 由
 * {@link SaveScheduler#isShutdownMode()} 守卫避开 — 关服已在
 * {@code BetterAutoSaveMod.onServerStopping} 调 {@code enterShutdownMode()}.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSaveMixin {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private PoiManager poiManager;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void betterautosave$interceptSave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
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

        if (!(chunk instanceof LevelChunk levelChunk)) {
            // 非 LevelChunk (ProtoChunk / ImposterProtoChunk) 不接管, 让 vanilla
            // 自己处理 (POI flush + isUnsaved 检查 + 可能的 ChunkSerializer.write).
            metrics.recordChunkMapSaveBypass();
            return;
        }
        if (!chunk.isUnsaved()) {
            // chunk 已 clean (BAS 异步路径已 save 过, 或 vanilla 上次已 save).
            // 关键优化 (v0.5.1): vanilla saveChunkIfNeeded 内的 cooldown 仅在 save
            // 返 true 时才更新; 我们 return 不 cancel 让 vanilla 第二行返 false,
            // cooldown 不更新 -> 下 tick 又被检查 -> mixin bypass 暴涨 (生产 ~100k/s).
            //
            // 手动 flush POI (与 vanilla 行为等价, vanilla save 第一行就 flush
            // POI 不论 isUnsaved), 然后 cancel + setReturnValue(true), 让
            // saveChunkIfNeeded 把 cooldown 设到 10s 后, 该 chunk 安静一段时间.
            //
            // 副作用: setReturnValue(true) 让 saveChunkIfNeeded 的 l 计数器累加,
            // 更快达到 20 跳出循环, 减少 visibleChunkMap 全扫成本.
            poiManager.flush(chunk.getPos());
            metrics.recordChunkMapSaveBypass();
            cir.setReturnValue(true);
            return;
        }

        long packed = chunk.getPos().toLong();
        String dimensionId = level.dimension().location().toString();
        long sequence = scheduler.nextEnqueueSequence();
        ChunkSaveState state = ((ChunkSaveStateAccess) chunk).betterautosave$getOrCreateState(
                packed, dimensionId, sequence);

        ChunkSaveState.Phase currentPhase = state.phase();
        if (currentPhase == ChunkSaveState.Phase.CLEAN) {
            state.markDirty();
        } else if (currentPhase != ChunkSaveState.Phase.DIRTY
                && currentPhase != ChunkSaveState.Phase.FAILED) {
            // SNAPSHOTTING / SERIALIZING / IO_PENDING — async pipeline 已在跑某代 IO。
            // mustDrain 必须先于接力快照登记置位 (gauge 不变式: pendingSnapshot 非空 -> mustDrain 恒真),
            // 关服 join 才会等接力链落地。
            if (state.tryMarkMustDrain()) {
                metrics.incMustDrainPending();
            }
            // 在飞期间该 chunk 又被编辑 (generation 前进) 又触发 save (典型: 卸载序列调 ChunkMap.save)。
            // 仅 setReturnValue(true) 信任在飞旧代快照不行: 卸载随即把 chunk 驱逐出内存, 编辑后那代增量会
            // 永久静默丢失。这里对最新内存做一次纯 capture (不碰在飞那代的 phase/inFlightGeneration, 见
            // capturePending) 登记进接力槽; 在飞那代 IO 落地判 REQUEUE_DIRTY 时回调取出重投, 把最新代落盘。
            // 多代碰撞链每次命中都登记更新代 (最新者胜, 旧 pending 作废), inFlightGeneration 随接力 dispatch 推进。
            if (state.generation() != state.inFlightGeneration()) {
                ConfigSpec.EventCompatMode mode = BetterAutoSaveConfig.eventCompatMode();
                ChunkSnapshot pending;
                try {
                    pending = ChunkCaptureProcedure.capturePending(levelChunk, level, state, mode);
                } catch (Throwable t) {
                    // 纯 capture 抛 (OOM 等): 尚未登记 pending, 接力槽空。在飞旧代 IO 仍会落地, 但 generation 已前进
                    // 必判 REQUEUE_DIRTY (ChunkSaveState:122 恒不等), 而 REQUEUE_DIRTY 不清 mustDrain
                    // (ChunkSaveState:125-127), 槽空回调取 null 也不重投 -> 此后无任何路径清 mustDrain。故必须在此
                    // 亲自配平 gauge (碰撞分支落地恒为 REQUEUE_DIRTY, 永不清 mustDrain, 不能指望在飞旧代落地代为清)。
                    // 退回信任在飞旧代 (本次最新代增量丢失), 记录可见。
                    if (state.compareAndClearMustDrain()) {
                        metrics.decMustDrainPending();
                    }
                    LOGGER.error("[BetterAutoSave] pending relay capture failed for in-flight chunk {} dim={}; "
                                    + "trusting in-flight snapshot (latest-generation increment may be lost)",
                            chunk.getPos(), dimensionId, t);
                    pending = null;
                }
                if (pending != null) {
                    // 槽位三态协议 begin -> dispatch -> publish。不能用 register -> dispatch 裸双步:
                    // 若 registerPendingSnapshot 前置于 dispatchSaveEvent (为关 lost-wakeup), "已登记"会被等同于
                    // "可消费"——dispatchSaveEvent 同步跑第三方 Forge listener (耗时无上界) 期间, 在飞那代 IO 在并发
                    // IOWorker 线程落地判 REQUEUE_DIRTY, 会把这份 listener 仍在原地改写的未就绪可变 tag 取走 reoffer
                    // 给序列化 worker assemble —— 三线程无栅栏共享同一 CompoundTag 的 HashMap, 静默数据损坏。
                    //
                    // 状态机拆"已登记"与"可消费"两个正交维度: beginPendingSnapshot 挂 PREPARING (回调能发现关
                    // lost-wakeup, 但 PREPARING 不可消费, 回调只标 missedCycle 离开关未就绪暴露); dispatch 跑完
                    // (listener 改写完成) 才 publishPendingSnapshot 发布 READY 让回调消费。若 dispatch 期间回调路过
                    // (publish 返回非 null), 补踢责任落到主线程自己 (合法 offer 方, 不引入 worker 阻塞)。
                    //
                    // begin 返回 true 表示它发现 drainOwner 被清空 (终态消费者已先于本 begin 路过 EMPTY 并经 ioFailed
                    // 清掉 drainOwner 且 EMPTY_DEAD 分支 honor 了那次 dec) 而把 drainOwner 重新拉为 IN_FLIGHT —— 此时
                    // 必须补 inc gauge 一次, 否则 "槽即将非空 (PREPARING) 但 gauge=0" 会破坏 drain 不变式。常规碰撞
                    // (drainOwner 进入时已 IN_FLIGHT) begin 返 false, 不重复 inc。
                    if (state.beginPendingSnapshot(pending)) {
                        metrics.incMustDrainPending();
                    }
                    boolean dispatchThrew = false;
                    try {
                        // 接力链落盘的"碰撞后最新代" tag 也必须经过 Forge ChunkDataEvent.Save listener —— 否则依赖
                        // 该事件向 tag 写增量的第三方 mod (容量数据 ForgeCaps 之外的监听者) 在最新代 tag 上从未经过其
                        // listener, 增量永久静默丢失。在主线程 (mixin save 拦截在
                        // 主线程) 用 pending 当代 tag 派发, 满足 Forge listener 线程契约, 与常规路径 capture 后即派发同序。
                        ChunkCaptureProcedure.dispatchSaveEvent(levelChunk, level, pending, mode, metrics);
                    } catch (Throwable t) {
                        // dispatch 抛 (第三方 listener 故障): abortPendingSnapshot 把 PREPARING 撤销归 EMPTY。回调在
                        // PREPARING 期间从不消费 (只标 missed), 故槽必仍 PREPARING, 撤销恒取回非 null。撤销后净效果等价
                        // "从未登记": 在飞旧代落地判 REQUEUE_DIRTY 永不清 mustDrain, 槽空回调取 null 不重投 -> 此后无路径
                        // 清 mustDrain, 故必须在此 compareAndClearMustDrain 亲自配平 gauge。退回信任在飞旧代, 记录可见。
                        dispatchThrew = true;
                        if (state.abortPendingSnapshot() != null) {
                            if (state.compareAndClearMustDrain()) {
                                metrics.decMustDrainPending();
                            }
                            LOGGER.error("[BetterAutoSave] pending relay event dispatch failed for in-flight chunk "
                                            + "{} dim={}; trusting in-flight snapshot (latest-generation increment may be lost)",
                                    chunk.getPos(), dimensionId, t);
                        }
                    }
                    if (!dispatchThrew) {
                        // dispatch 成功: 发布消费权 PREPARING -> READY。publish 返回非 null 表示 dispatch 期间有回调
                        // 路过 PREPARING (标了本周期 missedCycle) 后离开未消费 —— 那个回调是本代在飞 IO 的唯一消费者,
                        // 走后不再来, 补踢责任归主线程: 自己 reenterSerializing + reoffer 把就绪的 pending 接力落盘。
                        // 返回 null 表示已发布 READY, 等在飞回调消费, 主线程无需补踢。
                        ChunkSnapshot toReoffer = state.publishPendingSnapshot();
                        if (toReoffer != null) {
                            pipeline.reofferChunkPendingFromMainThread(level, state, toReoffer);
                        }
                    }
                }
            }
            // in-flight 碰撞分支也必须复刻 vanilla ChunkMap.save 首行 poiManager.flush(pos) 副作用。
            // 该分支 setReturnValue(true) 短路返回前完全跳过 flush, 而
            // generation 已前进 (gen2 含可能新增的 POI dirty section): 在飞那代 IO 落地与接力链重投都只写
            // chunk tag, 无任何路径把 gen2 的 POI 推进 IOWorker -> 崩溃窗口内 POI region 与 chunk region
            // 短暂不一致 (滞后一 cycle)。flush 幂等且廉价 (SectionStorage.flush 首行 hasWork 早退, 无 dirty
            // 即 no-op), 故无条件补一次零额外代价。与常规 dispatch 路径 (line 153 附近) 的 flush 同源。
            poiManager.flush(chunk.getPos());
            metrics.recordChunkMapSaveAsync();
            cir.setReturnValue(true);
            return;
        } else if (currentPhase == ChunkSaveState.Phase.FAILED) {
            // 之前用尽重试, vanilla 同步 save 兜底, 不接管。
            metrics.recordChunkMapSaveFallback();
            return;
        }

        if (state.tryMarkMustDrain()) {
            metrics.incMustDrainPending();
        }

        // 复制 vanilla ChunkMap.save 第一行 poiManager.flush 副作用.
        // vanilla ChunkMap.java:825 无条件在 save 第一行调 flush(pos) 把该 chunk pos
        // 上 dirty 的 PoiSection 推到 PoiManager IOWorker mailbox. v0.4 异步 dispatch
        // 路径漏掉这步, 破坏 vanilla "POI 不晚于 chunk 进 IOWorker" 顺序保证.
        // 实际不丢数据 (POI 可由 chunk 重建, vanilla PoiManager 是 lazy load), 但顺序
        // 破坏导致崩溃后 POI region file 滞后一个 cycle, 短暂不一致.
        poiManager.flush(chunk.getPos());

        try {
            boolean dispatched = pipeline.captureAndDispatchChunk(levelChunk, level, state);
            if (dispatched) {
                metrics.recordChunkMapSaveAsync();
                cir.setReturnValue(true);
                return;
            }
            // trySnapshot CAS 失败: 已被另一线程接管, 复用其结果。
            metrics.recordChunkMapSaveAsync();
            cir.setReturnValue(true);
        } catch (Throwable t) {
            // dispatch 异常: 清回 mustDrain 让 vanilla 同步路径处理, 不挂 gauge。
            if (state.compareAndClearMustDrain()) {
                metrics.decMustDrainPending();
            }
            // capture 抛后 phase 已被 enterSerializing 推到 SERIALIZING, 或 trySnapshot 已推到 SNAPSHOTTING.
            // catch 不复位 phase 会让该 chunk 后续永远走 mixin 在飞分支早 return 路径 (phase 非 DIRTY/FAILED),
            // 既不入 BAS worker 也不走 vanilla 同步, 数据永久丢失而无 telemetry.
            //
            // capture 第一行已 setUnsaved(false). 这里不 cancel cir, vanilla ChunkMap.save 方法体续跑会撞
            // isUnsaved 门 (此刻 false) -> 直接 return false 跳过本次同步序列化 -> 本次数据丢失; 该 chunk 此后
            // 不再编辑则 unload 与关服 flush 同样按 isUnsaved 门跳过, 永久丢失. 只 resetAfterFallback 归零
            // phase 不够 (vanilla 重入门看的是 isUnsaved 而非 phase). 改用 recoverAfterDispatchFailure 同时
            // 还原 isUnsaved=true, 让续跑的 vanilla 方法体过门 -> 当场同步写盘救回本次数据. 与
            // SaveDispatcher.onPriorityDrained catch 同源.
            // catch 内已有的 compareAndClearMustDrain 保留: recoverAfterDispatchFailure 不碰 mustDrain, 无重复.
            SaveDispatcher.recoverAfterDispatchFailure(state, levelChunk::setUnsaved);
            metrics.recordChunkMapSaveFallback();
            LOGGER.error("[BetterAutoSave] ChunkMap.save async dispatch failed for {} dim={}, falling back to vanilla",
                    chunk.getPos(), dimensionId, t);
        }
    }
}
