package com.shinoyuki.betterautosave.mixin.accessor;

import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.File;

/**
 * 暴露 vanilla {@link DimensionDataStorage#getDataFile(String)} 私有方法,
 * v0.7 SavedData 异步化路径需要在主线程构建目标 .dat 文件路径后入 worker queue.
 */
@Mixin(DimensionDataStorage.class)
public interface DimensionDataStorageInvoker {

    @Invoker("getDataFile")
    File betterautosave$getDataFile(String name);
}
