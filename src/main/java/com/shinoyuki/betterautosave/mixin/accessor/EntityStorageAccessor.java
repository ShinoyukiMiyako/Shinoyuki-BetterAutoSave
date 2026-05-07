package com.shinoyuki.betterautosave.mixin.accessor;

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
 */
@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {

    @Accessor("worker")
    IOWorker betterautosave$getWorker();
}
