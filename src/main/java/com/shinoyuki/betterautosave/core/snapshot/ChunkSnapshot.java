package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public record ChunkSnapshot(
        ChunkPos pos,
        ResourceKey<Level> dimension,
        CompoundTag tag,
        long capturedGeneration,
        ChunkSaveState state
) {
}
