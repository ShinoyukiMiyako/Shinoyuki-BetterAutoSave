package com.shinoyuki.betterautosave.core.snapshot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public record EntitySnapshot(
        ChunkPos pos,
        ResourceKey<Level> dimension,
        CompoundTag tag,
        long capturedGeneration
) {
}
