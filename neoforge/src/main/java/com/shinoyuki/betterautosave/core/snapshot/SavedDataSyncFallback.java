package com.shinoyuki.betterautosave.core.snapshot;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.File;
import java.util.Set;

/**
 * SavedData 大文件同步 fallback 的在途占位释放抽到此处, 把
 * "save(file) 包 try-finally 释放占位" 的异常安全契约从 mixin 体里提出来单测.
 *
 * <p>DimensionDataStorageMixin 大文件 fallback 先 savedDataInFlight.add(name)
 * 再 savedData.save(file). save(file) 必须包 finally: 它抛 Throwable (vanilla
 * SavedData.save(File) 仅 catch IOException, mod 的 save(CompoundTag) 抛 RuntimeException
 * 会透出) 时若漏掉 remove(name), 该名称永久占位, 后续每周期 add 失败被跳过, 该
 * SavedData 失去 BAS 增量保护仅剩关服兜底.
 *
 * <p>finally 保证无论 save 是否抛都释放占位; 异常仍按 vanilla 等价语义透出
 * (vanilla DimensionDataStorage.save 的 forEach 遇 RuntimeException 同样中断).
 */
public final class SavedDataSyncFallback {

    private SavedDataSyncFallback() {
    }

    /**
     * 同步写 SavedData 到文件, finally 释放在途占位.
     *
     * @param savedData  目标 SavedData (调其 save(File, registries))
     * @param file       落盘目标文件
     * @param registries 1.21.1 SavedData.save(File, HolderLookup.Provider) 必需的注册表
     * @param inFlight    在途去重集合 (全服单份, 跨所有维度)
     * @param inFlightKey 该 .dat 的在途占位 key (= 目标文件完整路径, 跨维度唯一)
     */
    public static void syncWrite(SavedData savedData, File file, HolderLookup.Provider registries, Set<String> inFlight, String inFlightKey) {
        try {
            savedData.save(file, registries);
        } finally {
            inFlight.remove(inFlightKey);
        }
    }
}
