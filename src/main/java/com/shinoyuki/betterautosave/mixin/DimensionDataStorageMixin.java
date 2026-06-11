package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SavedDataSaveTask;
import com.shinoyuki.betterautosave.core.snapshot.SavedDataSnapshot;
import com.shinoyuki.betterautosave.core.snapshot.SavedDataSyncFallback;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.mixin.accessor.DimensionDataStorageInvoker;
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

    /**
     * v0.7.1 修复 (M7): 历史落盘 size 跟踪, 替代 file.length() 守卫.
     * 之前守卫 file.exists() && file.length() > maxBytes:
     * - 文件不存在时短路 false → 第一次写大文件无保护
     * - NFS / SMB 远程 fs file.length() 缓存延迟可能误判
     *
     * 改为 worker 完成 IO 后回写 size 到此 map. 守卫优先用历史 size,
     * 没历史记录退化为 file.length() (跟之前等价, 只覆盖第一次写场景).
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
        // v0.7.1 修复 (M9): 不再需要 server 引用 — SavedDataSaveTask 失败重试改为
        // worker 直接 setDirty, 不走 server.execute 异步派回主线程.
        DimensionDataStorageInvoker invoker = (DimensionDataStorageInvoker) (Object) this;
        long maxBytes = (long) BetterAutoSaveConfig.savedDataMaxFileSizeMB() * 1024L * 1024L;

        for (Map.Entry<String, SavedData> entry : cache.entrySet()) {
            String name = entry.getKey();
            SavedData savedData = entry.getValue();
            if (savedData == null || !savedData.isDirty()) {
                continue;
            }
            File file = invoker.betterautosave$getDataFile(name);

            // Minor 修复 4 (a): 在途文件名去重. savedDataWorkerThreads 可配 1-4, 同名 .dat
            // 被多 worker 并发写会交错损坏. 上轮该文件的 worker 还没写完 (in-flight) 就跳过
            // 本轮 dispatch, 不清 dirty → 下个 autosave 周期自然重试. add 成功才往下走;
            // 失败 (已在途) continue 且保持 dirty.
            if (!pipeline.savedDataInFlight().add(name)) {
                continue;
            }

            // 大文件 fallback: 现存文件已超阈值, 走 vanilla 同步避免 worker queue
            // 被几十 MB 单文件堵死.
            // v0.7.1 修复 (M7): 优先用历史 size (worker 上次写完回写), 兜底再用
            // file.length(). 历史 size 在 NFS / SMB 远程 fs 上比 file.length() 可靠.
            // 首次写仍无保护 (没有历史也没有现存文件), 文档化为已知限制.
            Long historySize = betterautosave$lastWrittenSize.get(name);
            long sizeForGuard = historySize != null ? historySize : (file.exists() ? file.length() : 0L);
            if (sizeForGuard > maxBytes) {
                metrics.recordSavedDataFallback();
                // v0.10.2 修复 (M3): 同步 fallback 不入 worker, 占位释放责任在本地. 之前
                // save(file) 无 finally, 抛 Throwable (vanilla SavedData.save(File) 仅 catch
                // IOException, mod 的 save(CompoundTag) 抛 RuntimeException 会透出) 则 remove(name)
                // 被跳过 → 该名称永久占位, 后续每周期 add 失败被跳过, 该 SavedData 失去 BAS 增量
                // 保护仅剩关服兜底. 抽到 SavedDataSyncFallback.syncWrite 用 try-finally 释放占位 +
                // 集中单测异常安全契约. 异常仍按 vanilla 等价语义透出.
                SavedDataSyncFallback.syncWrite(savedData, file, pipeline.savedDataInFlight(), name);
                continue;
            }

            // v0.7.1 修复 (M8): 把 mod 序列化阶段 (savedData.save(CompoundTag)) 跟
            // BAS dispatch 阶段分两个 try-catch. 之前合并 try 一旦 mod save(CompoundTag)
            // 抛, fallback 走 savedData.save(file) 又调一次 save(CompoundTag) → mod
            // 非幂等实现副作用双发, 同时 vanilla SavedData.save(File) 仅 catch IOException
            // 不 catch RuntimeException, 异常透出导致 dataStorage.save forEach 中断.
            //
            // 拆分后:
            // - mod 序列化抛 → 跳过该条 + log + fallback 计数, 不影响其他 entry
            // - BAS dispatch 抛 → 用已构好的 tag 直接 NbtIo.writeCompressed 主线程同步
            //   兜底, 不再调 vanilla savedData.save(file) (避免双重 save(CompoundTag))
            CompoundTag tag;
            try {
                tag = new CompoundTag();
                tag.put("data", savedData.save(new CompoundTag()));
                NbtUtils.addCurrentDataVersion(tag);
            } catch (Throwable t) {
                metrics.recordSavedDataFallback();
                // mod 序列化抛, 未入 worker, 释放在途占位.
                pipeline.savedDataInFlight().remove(name);
                LOGGER.error("[BetterAutoSave] SavedData {} mod serialization threw, skipping this cycle (data still dirty for next cycle)",
                        name, t);
                continue;
            }

            try {
                SavedDataSnapshot snapshot = new SavedDataSnapshot(name, file, tag, savedData,
                        betterautosave$lastWrittenSize, pipeline.savedDataInFlight());
                metrics.incInFlightSerializing();
                metrics.recordSavedDataSubmitted();
                // 入队成功后在途占位的释放责任移交 worker task 的 finally (SavedDataSaveTask).
                pipeline.savedDataWorkerQueue().offer(new SavedDataSaveTask(snapshot, metrics));
                // v0.10.2 修复 (M4): SavedData 队列深度入指标. 与 chunk/entity 不同, SavedData
                // 不走 SaveScheduler 的 tick 节流队列 (无逐 tick drain 回写时机), 只能在 offer
                // 后即时回写 queue.size() — worker 消费后的回落由下一周期 offer 重新采样.
                metrics.setSavedDataQueueDepth(pipeline.savedDataWorkerQueue().size());
                // 乐观清 dirty: 跟 vanilla 行为差异 — vanilla 在 IO 完成后清,
                // BAS 在 dispatch 时清. 失败时 worker 直接 setDirty(true) (v0.7.1
                // M9 修复: 不再走 server.execute, 避免关服阶段主线程 task queue 已
                // 不消费导致 setDirty 永久丢失).
                savedData.setDirty(false);
            } catch (Throwable t) {
                metrics.recordSavedDataFallback();
                // dispatch 抛, 未成功入 worker, 释放在途占位.
                pipeline.savedDataInFlight().remove(name);
                LOGGER.error("[BetterAutoSave] SavedData {} dispatch failed, falling back to direct sync write",
                        name, t);
                // 用已构好的 tag 直接写盘, 不调 savedData.save(file) 避免 mod
                // save(CompoundTag) 被双重调用. 写失败 vanilla 等价 (vanilla 也只 log).
                try {
                    net.minecraft.nbt.NbtIo.writeCompressed(tag, file);
                    savedData.setDirty(false);
                } catch (java.io.IOException ioe) {
                    LOGGER.error("[BetterAutoSave] SavedData {} sync fallback write failed, data stays dirty for next cycle",
                            name, ioe);
                }
            }
        }
        ci.cancel();
    }
}
