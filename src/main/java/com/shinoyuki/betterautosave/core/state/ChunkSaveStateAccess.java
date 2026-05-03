package com.shinoyuki.betterautosave.core.state;

public interface ChunkSaveStateAccess {

    ChunkSaveState betterautosave$getOrCreateState(long packedPos, String dimensionId, long enqueueSequence);

    ChunkSaveState betterautosave$getState();
}
