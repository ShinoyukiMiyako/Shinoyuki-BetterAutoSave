package com.shinoyuki.betterautosave.mixin.accessor;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * v0.6: 暴露 vanilla {@code EntityStorage.worker} 字段, 与 v0.2
 * {@link ChunkStorageAccessor} 同构. v0.4 之前 entity worker pool 已就位
 * 但 entity 路径未接管, 此 accessor 配合 Phase 4 mixin 让 BAS Async IO
 * 桥可调到 entity IOWorker.
 *
 * <p>注意 EntityStorage 不继承 ChunkStorage (实现 EntityPersistentStorage),
 * 因此独立 accessor 而非复用 ChunkStorageAccessor.
 *
 * <p>v0.7.1: 同时暴露 {@code emptyChunks} {@link LongSet}. v0.6 mixin
 * 拦截 storeEntities 后跳过了 vanilla 在非空分支末尾的 emptyChunks.remove
 * 副作用清理, 导致 chunk 从空→有 entity 后该位置仍在 emptyChunks 中,
 * 后续 unload→reload 走 loadEntities 快速路径返空 chunk → entity 静默丢失.
 * mixin 在异步 dispatch 成功后必须显式调 remove 复制 vanilla 副作用.
 */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {

    @Accessor("worker")
    IOWorker betterautosave$getWorker();

    @Accessor("emptyChunks")
    LongSet betterautosave$getEmptyChunks();
}
