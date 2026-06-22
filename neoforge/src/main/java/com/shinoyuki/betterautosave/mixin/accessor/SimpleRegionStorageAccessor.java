package com.shinoyuki.betterautosave.mixin.accessor;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 NeoForge 1.21.1 {@code SimpleRegionStorage.worker} 内层 IOWorker。1.21.1 把
 * EntityStorage 的 region IO 收口进 SimpleRegionStorage (其私有 worker), BAS 经
 * {@link EntityStorageAccessor#betterautosave$getSimpleRegionStorage()} 拿到
 * SimpleRegionStorage 后, 再经本 accessor 取其内层 IOWorker, 与 1.20.1 直接持有
 * EntityStorage.worker 等价, 使异步 entity 管线 (EntitySaveTask / SnapshotPipeline) 无需改动。
 */
@Mixin(SimpleRegionStorage.class)
public interface SimpleRegionStorageAccessor {

    @Accessor("worker")
    IOWorker betterautosave$getWorker();
}
