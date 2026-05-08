package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SavedDataSaveTask;
import com.shinoyuki.betterautosave.core.snapshot.SavedDataSnapshot;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.mixin.accessor.DimensionDataStorageInvoker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.Map;

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
        MinecraftServer server = pipeline.server();
        if (server == null) {
            return;
        }

        DimensionDataStorageInvoker invoker = (DimensionDataStorageInvoker) (Object) this;
        long maxBytes = (long) BetterAutoSaveConfig.savedDataMaxFileSizeMB() * 1024L * 1024L;

        for (Map.Entry<String, SavedData> entry : cache.entrySet()) {
            String name = entry.getKey();
            SavedData savedData = entry.getValue();
            if (savedData == null || !savedData.isDirty()) {
                continue;
            }
            File file = invoker.betterautosave$getDataFile(name);

            // 大文件 fallback: 现存文件已超阈值, 走 vanilla 同步避免 worker queue
            // 被几十 MB 单文件堵死. 新文件首次写无法预判, 仅基于已有文件大小决策.
            if (file.exists() && file.length() > maxBytes) {
                metrics.recordSavedDataFallback();
                savedData.save(file);
                continue;
            }

            try {
                CompoundTag tag = new CompoundTag();
                tag.put("data", savedData.save(new CompoundTag()));
                NbtUtils.addCurrentDataVersion(tag);

                SavedDataSnapshot snapshot = new SavedDataSnapshot(name, file, tag, savedData);
                metrics.incInFlightSerializing();
                metrics.recordSavedDataSubmitted();
                pipeline.savedDataWorkerQueue().offer(new SavedDataSaveTask(snapshot, server, metrics));
                // 乐观清 dirty: 跟 vanilla 行为差异 — vanilla 在 IO 完成后清,
                // BAS 在 dispatch 时清. 失败时 worker 通过 server.execute 重新
                // setDirty(true), 让下个 autosave 周期重试. 详见 V0_7_PLAN §7.3.
                savedData.setDirty(false);
            } catch (Throwable t) {
                metrics.recordSavedDataFallback();
                LOGGER.error("[BetterAutoSave] SavedData {} dispatch failed, falling back to vanilla",
                        name, t);
                savedData.save(file);
            }
        }
        ci.cancel();
    }
}
