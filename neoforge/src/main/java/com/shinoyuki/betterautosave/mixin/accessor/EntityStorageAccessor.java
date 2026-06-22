package com.shinoyuki.betterautosave.mixin.accessor;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 NeoForge 1.21.1 {@code EntityStorage.simpleRegionStorage} 字段。1.21.1 的
 * EntityStorage 不再直接持 IOWorker, region IO 收口进 SimpleRegionStorage; BAS 经本
 * accessor 取 SimpleRegionStorage 再经 {@link SimpleRegionStorageAccessor} 取其内层
 * IOWorker, 使异步 entity 管线复用与 1.20.1 等价的 worker 引用。EntityStorage 不继承
 * ChunkStorage (实现 EntityPersistentStorage), 故独立 accessor 而非复用 {@link ChunkStorageAccessor}。
 *
 * <p>同时暴露 {@code emptyChunks} {@link LongSet}: mixin 拦截 storeEntities 后跳过了
 * vanilla 在非空分支末尾的 emptyChunks.remove 副作用清理, 导致 chunk 从空->有 entity 后
 * 该位置仍在 emptyChunks 中, 后续 unload->reload 走 loadEntities 快速路径返空 chunk ->
 * entity 静默丢失。mixin 在异步 dispatch 成功后必须显式调 remove 复刻 vanilla 副作用。
 */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {

    @Accessor("simpleRegionStorage")
    SimpleRegionStorage betterautosave$getSimpleRegionStorage();

    @Accessor("emptyChunks")
    LongSet betterautosave$getEmptyChunks();
}
