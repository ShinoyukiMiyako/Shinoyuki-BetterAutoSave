package com.shinoyuki.betterautosave.mixin.accessor;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor {

    @Accessor("worker")
    IOWorker betterautosave$getWorker();
}
