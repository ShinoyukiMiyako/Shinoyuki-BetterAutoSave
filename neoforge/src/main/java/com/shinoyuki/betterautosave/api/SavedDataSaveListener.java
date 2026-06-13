package com.shinoyuki.betterautosave.api;

import net.minecraft.nbt.CompoundTag;

/**
 * BAS SavedData (DimensionDataStorage) 保存路径外部订阅接口 (v0.7).
 *
 * <p>每次 BAS 把一个 SavedData 文件 (例如 {@code raids.dat} / {@code Forced.dat} /
 * mod 自定义如 MTR {@code mtr_train_data.dat}) 成功落盘后回调.
 *
 * <p>线程模型 / 异常处理 / tag 生命周期约束与 {@link ChunkSaveListener} 相同.
 *
 * <p>tag 是 SavedData 完整外层 CompoundTag, 含 {@code data} 子 tag (mod 序列化结果)
 * 与 {@code DataVersion}, 跟 vanilla SavedData.save(File) 写盘格式一致.
 */
@FunctionalInterface
public interface SavedDataSaveListener {

    /**
     * SavedData 成功落盘后回调.
     *
     * @param fileName SavedData 名 (无 .dat 后缀, 例 "raids" "mtr_train_data")
     * @param tag      已写入 .dat 文件的完整外层 CompoundTag
     */
    void onSavedDataWritten(String fileName, CompoundTag tag);
}
