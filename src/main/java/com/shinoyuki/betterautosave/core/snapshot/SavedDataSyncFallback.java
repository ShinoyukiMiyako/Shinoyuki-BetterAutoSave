package com.shinoyuki.betterautosave.core.snapshot;

import net.minecraft.world.level.saveddata.SavedData;

import java.io.File;
import java.util.Set;

/**
 * v0.10.2 修复 (M3): SavedData 大文件同步 fallback 的在途占位释放抽到此处, 把
 * "save(file) 包 try-finally 释放占位" 的异常安全契约从 mixin 体里提出来单测.
 *
 * <p>背景: DimensionDataStorageMixin 大文件 fallback 先 savedDataInFlight.add(name)
 * 再 savedData.save(file). 之前 save(file) 无 finally, 抛 Throwable (vanilla
 * SavedData.save(File) 仅 catch IOException, mod 的 save(CompoundTag) 抛 RuntimeException
 * 会透出) 则 remove(name) 被跳过 → 该名称永久占位, 后续每周期 add 失败被跳过, 该
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
     * @param savedData 目标 SavedData (调其 save(File))
     * @param file      落盘目标文件
     * @param inFlight  在途文件名去重集合
     * @param name      该 SavedData 的文件名 (在途占位 key)
     */
    public static void syncWrite(SavedData savedData, File file, Set<String> inFlight, String name) {
        try {
            savedData.save(file);
        } finally {
            inFlight.remove(name);
        }
    }
}
