package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.io.AtomicNbtWriter;
import com.shinoyuki.betterautosave.core.snapshot.SavedDataDispatch;
import com.shinoyuki.betterautosave.core.snapshot.SavedDataSaveTask;
import com.shinoyuki.betterautosave.core.snapshot.SavedDataSnapshot;
import com.shinoyuki.betterautosave.core.snapshot.SavedDataSyncFallback;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.mixin.accessor.DimensionDataStorageInvoker;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.7: 拦 vanilla {@link DimensionDataStorage#save()} HEAD, 把 SavedData
 * (.dat 文件) 的 NBT 序列化主线程同步路径替换为 BAS savedDataWorkerQueue
 * 异步路径.
 *
 * <p>主线程做的工作 (跟 vanilla 等价):
 * <ol>
 *   <li>遍历 dataStorage.cache, 找 dirty SavedData</li>
 *   <li>调 {@code savedData.save(new CompoundTag())} 让 mod 把内部状态序列化为 tag
 *       (这一步必须主线程 — mod 实现可能持有非线程安全 mutable state)</li>
 *   <li>包装外层 tag (含 {@code data} 子 tag + DataVersion)</li>
 *   <li>构 SavedDataSnapshot 入 worker queue</li>
 *   <li>setDirty(false) 乐观清理</li>
 * </ol>
 *
 * <p>主线程**不再做**的工作 (移到 worker):
 * <ul>
 *   <li>{@code NbtIo.writeCompressed} (gzip 压缩 + 文件 IO, 大文件几十 ms-几百 ms)</li>
 * </ul>
 *
 * <p><b>关服守卫</b>: {@link SaveScheduler#isShutdownMode()} 已在
 * {@code BetterAutoSaveMod.onServerStopping} 设置, 关服路径
 * {@code MinecraftServer.stopServer -> ServerLevel.save -> dataStorage.save}
 * 走 vanilla 同步路径, 保证 SavedData 100% flush 落盘.
 *
 * <p><b>大文件 fallback</b>: 现存 .dat 文件大小超过
 * {@link BetterAutoSaveConfig#savedDataMaxFileSizeMB()} 时直接走 vanilla 同步,
 * 防止单个几十 MB 文件堵死 worker queue (例如损坏的 MTR train data).
 *
 * <p><b>异常 fallback</b>: 主线程构 tag 阶段异常 (mod 实现 bug) 也走 vanilla
 * 同步, 数据安全等价 vanilla. fallback 计数监控用.
 */
@Mixin(DimensionDataStorage.class)
public abstract class DimensionDataStorageMixin {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    @Shadow
    @Final
    private Map<String, SavedData> cache;

    // 1.21.1 SavedData.save(CompoundTag/File, HolderLookup.Provider) 需注册表; vanilla
    // DimensionDataStorage 在构造期持有该 Provider, mixin 取之传给 save 与同步 fallback。
    @Shadow
    @Final
    private HolderLookup.Provider registries;

    /**
     * 历史落盘 size 跟踪, 替代纯 file.length() 守卫.
     * 纯 file.exists() && file.length() > maxBytes 守卫的缺陷:
     * - 文件不存在时短路 false → 第一次写大文件无保护
     * - NFS / SMB 远程 fs file.length() 缓存延迟可能误判
     *
     * worker 完成 IO 后回写 size 到此 map. 守卫优先用历史 size,
     * 没历史记录退化为 file.length() (只覆盖第一次写场景).
     */
    @Unique
    private final ConcurrentHashMap<String, Long> betterautosave$lastWrittenSize = new ConcurrentHashMap<>();

    @Inject(method = "save()V",
            at = @At("HEAD"),
            cancellable = true)
    private void betterautosave$interceptSave(CallbackInfo ci) {
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
        // 无需 server 引用 — SavedDataSaveTask 失败重试由 worker 直接 setDirty,
        // 不走 server.execute 异步派回主线程.
        DimensionDataStorageInvoker invoker = (DimensionDataStorageInvoker) (Object) this;
        long maxBytes = (long) BetterAutoSaveConfig.savedDataMaxFileSizeMB() * 1024L * 1024L;

        for (Map.Entry<String, SavedData> entry : cache.entrySet()) {
            String name = entry.getKey();
            SavedData savedData = entry.getValue();
            if (savedData == null || !savedData.isDirty()) {
                continue;
            }
            File file = invoker.betterautosave$getDataFile(name);
            // 在途去重 key 用目标文件完整路径, 不用裸文件名: savedDataInFlight 是全服单份跨所有维度, 而各
            // 维度各有一份同名 SavedData (如每维度都有 "chunks"/"Forced"), 落到 <dim>/data/ 下不同文件.
            // 裸名做 key 会让下界/末地的 "chunks" 撞上主世界已在途的 "chunks" 被整周期跳过, 落盘频率随维度数
            // 下降; 文件路径做 key 则各维度互不干扰. add 成功才往下走; 失败 (该文件真的在途) continue 且保持
            // dirty, 下个 autosave 周期自然重试. savedDataWorkerThreads 可配 1-4, 去重防同一 .dat 被多 worker
            // 并发写交错损坏.
            String inFlightKey = file.getPath();
            if (!pipeline.savedDataInFlight().add(inFlightKey)) {
                continue;
            }

            // 大文件 fallback: 现存文件已超阈值, 走 vanilla 同步避免 worker queue
            // 被几十 MB 单文件堵死.
            // 优先用历史 size (worker 上次写完回写), 兜底再用
            // file.length(). 历史 size 在 NFS / SMB 远程 fs 上比 file.length() 可靠.
            // 首次写仍无保护 (没有历史也没有现存文件), 文档化为已知限制.
            Long historySize = betterautosave$lastWrittenSize.get(name);
            long sizeForGuard = historySize != null ? historySize : (file.exists() ? file.length() : 0L);
            if (sizeForGuard > maxBytes) {
                metrics.recordSavedDataFallback();
                // 同步 fallback 不入 worker, 占位释放责任在本地. 若 save(file) 无 finally, 抛 Throwable
                // (vanilla SavedData.save(File) 仅 catch IOException, mod 的 save(CompoundTag) 抛
                // RuntimeException 会透出) 则 remove(name) 被跳过 → 该名称永久占位, 后续每周期 add 失败被
                // 跳过, 该 SavedData 失去 BAS 增量保护仅剩关服兜底. SavedDataSyncFallback.syncWrite 用
                // try-finally 释放占位. 异常仍按 vanilla 等价语义透出.
                SavedDataSyncFallback.syncWrite(savedData, file, this.registries, pipeline.savedDataInFlight(), inFlightKey);
                continue;
            }

            // 把 mod 序列化阶段 (savedData.save(CompoundTag)) 跟 BAS dispatch 阶段分两个 try-catch.
            // 若合并成一个 try, 一旦 mod save(CompoundTag) 抛, fallback 走 savedData.save(file) 又调一次
            // save(CompoundTag) → mod 非幂等实现副作用双发, 同时 vanilla SavedData.save(File) 仅 catch
            // IOException 不 catch RuntimeException, 异常透出导致 dataStorage.save forEach 中断.
            //
            // 拆分后:
            // - mod 序列化抛 → 跳过该条 + log + fallback 计数, 不影响其他 entry
            // - BAS dispatch 抛 → 用已序列化好的脱钩字节直接原子写盘兜底, 不再调 vanilla
            //   savedData.save(file) (避免双重 save(CompoundTag))
            byte[] nbtBytes;
            try {
                CompoundTag tag = new CompoundTag();
                tag.put("data", savedData.save(new CompoundTag(), this.registries));
                NbtUtils.addCurrentDataVersion(tag);
                // mod 的 save(CompoundTag, Provider) 可能无视入参、返回其持有的内部 live Tag。BAS 把 vanilla 主线程
                // 同步写改成 worker 异步写: worker 序列化期间 mod 继续 mutate 那棵 live 子树 -> CME (只 catch
                // IOException, 逃到重试再撞) 或写出 torn / 半更新 NBT (静默损坏)。一次性序列化成未压缩字节即彻底脱钩
                // (字节不可能 alias 任何 live 对象), 取代过去的 tag.copy() 深拷贝: 同样安全且更彻底, 但不分配平行 NBT
                // 对象树, 对超大 SavedData 不再是主线程秒级尖峰 (issue #12); worker 端只 gzip + 写。序列化抛
                // (病态 mod tag) 走下方同一 fallback。
                nbtBytes = AtomicNbtWriter.serializeUncompressed(tag);
            } catch (Throwable t) {
                metrics.recordSavedDataFallback();
                // mod 序列化抛, 未入 worker, 释放在途占位.
                pipeline.savedDataInFlight().remove(inFlightKey);
                LOGGER.error("[BetterAutoSave] SavedData {} mod serialization threw, skipping this cycle (data still dirty for next cycle)",
                        name, t);
                continue;
            }

            try {
                SavedDataSnapshot snapshot = new SavedDataSnapshot(name, file, nbtBytes, savedData,
                        betterautosave$lastWrittenSize, inFlightKey, pipeline.savedDataInFlight());
                metrics.recordSavedDataSubmitted();
                // 乐观清 dirty 必须发生在 enqueue 之前。若放在 offer 之后, 是 lost-update: worker 是独立线程,
                // offer 把 task 交给 worker 后主线程还要再跑两行才清 dirty; 持续性 IO 故障下 worker 的
                // writeCompressed 同步快速抛 IOException 走 SavedDataSaveTask setDirty(true), 这次 true 可能先于
                // 主线程的 setDirty(false) 发生, last-writer-wins 主线程 false 胜出 -> 下周期 isDirty() gate 跳过
                // -> "worker re-mark -> 下周期重试" 在 fast-fail 窗口内被自己抵消。清 dirty 上移后主线程的 false
                // 必然 happens-before worker 任何 setDirty(true), worker 失败置 true 成为最后写, 下周期正确重试。
                // (volatile 镜像解决可见性, 与本顺序正交, 二者都需要。)
                savedData.setDirty(false);
                // inc serializing + offer 的 gauge 配平不变式收口到 SavedDataDispatch。
                // 入队成功后在途占位的释放责任移交 worker task 的 finally (SavedDataSaveTask); inc 抵消
                // 责任移交 worker execute 首行 dec。offer 阶段抛异常时 enqueue 内部已先补 dec 再上抛,
                // 故下面 dispatch catch 不得再碰 serializing (已配平), 仅走同步兜底写。
                SavedDataSaveTask savedDataTask = new SavedDataSaveTask(snapshot, metrics);
                SavedDataDispatch.enqueue(pipeline.savedDataWorkerQueue(), savedDataTask, metrics);
                // degraded 残窗兜底: 本次 dispatch 已过顶部闸门且乐观清了 dirty, 若 capture/序列化期间 triggerDegraded
                // 抢先 drain 完, 此 task 会滞留无存活 worker 的队列使 dirty=false 永不还原 -> vanilla flush 跳过静默丢失;
                // 抢下并 abandon 重新 setDirty 走 vanilla 兜底。
                pipeline.reclaimIfDegradedAfterOffer(savedDataTask, pipeline.savedDataWorkerQueue());
                // SavedData 队列深度入指标. 与 chunk/entity 不同, SavedData
                // 不走 SaveScheduler 的 tick 节流队列 (无逐 tick drain 回写时机), 只能在 offer
                // 后即时回写 queue.size() — worker 消费后的回落由下一周期 offer 重新采样.
                metrics.setSavedDataQueueDepth(pipeline.savedDataWorkerQueue().size());
            } catch (Throwable t) {
                // serializing gauge 已由 SavedDataDispatch.enqueue 在 offer 失败时配平, 此处不再碰。
                metrics.recordSavedDataFallback();
                // dispatch 抛, 未成功入 worker, 释放在途占位.
                pipeline.savedDataInFlight().remove(inFlightKey);
                LOGGER.error("[BetterAutoSave] SavedData {} dispatch failed, falling back to direct sync write",
                        name, t);
                // 用已序列化好的脱钩字节直接原子写盘, 不调 savedData.save(file) 避免 mod
                // save(CompoundTag) 被双重调用. 写失败 vanilla 等价 (vanilla 也只 log).
                try {
                    AtomicNbtWriter.writeCompressed(nbtBytes, file);
                    // 兜底写成功: dirty 已在 enqueue 前清 false, 此处幂等保持 false (与上移前同终值)。
                    savedData.setDirty(false);
                } catch (java.io.IOException ioe) {
                    // 因乐观清 dirty 已上移到 enqueue 前, 进入本 catch 时 dirty 已是 false。"offer 失败 + 兜底
                    // 写也失败" 这条路径需要下周期重试, 故必须在此显式 re-mark, 否则丢这次重试。
                    savedData.setDirty();
                    LOGGER.error("[BetterAutoSave] SavedData {} sync fallback write failed, re-marked dirty for next cycle",
                            name, ioe);
                }
            }
        }
        ci.cancel();
    }
}
