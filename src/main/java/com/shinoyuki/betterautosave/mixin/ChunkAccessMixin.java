package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.state.ChunkSaveStateAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin implements ChunkSaveStateAccess {

    @Unique
    private ChunkSaveState betterautosave$state;

    @Override
    public ChunkSaveState betterautosave$getOrCreateState(long packedPos, String dimensionId, long enqueueSequence) {
        ChunkSaveState s = this.betterautosave$state;
        if (s == null) {
            s = new ChunkSaveState(packedPos, dimensionId, enqueueSequence);
            this.betterautosave$state = s;
        }
        return s;
    }

    @Override
    public ChunkSaveState betterautosave$getState() {
        return this.betterautosave$state;
    }
}
