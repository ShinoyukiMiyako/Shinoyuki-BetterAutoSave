package com.shinoyuki.betterautosave.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * BAS entity 保存路径外部订阅接口 (v0.6 entity chunk pipeline).
 *
 * <p>线程模型 / 异常处理 / tag 生命周期约束与 {@link ChunkSaveListener} 相同.
 *
 * <p>tag 是 entity chunk 的外层 CompoundTag, 含 {@code Entities} ListTag /
 * {@code Position} IntArray / {@code DataVersion}, 跟 vanilla EntityStorage
 * 写盘格式一致.
 */
@FunctionalInterface
public interface EntityChunkSaveListener {

    /**
     * BAS entity chunk save 成功落盘后回调.
     *
     * @param pos       entity chunk 坐标 (与 chunk 路径同一坐标系)
     * @param dimension 维度
     * @param tag       已写入 entity region file 的外层 CompoundTag
     */
    void onEntityChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag);
}
