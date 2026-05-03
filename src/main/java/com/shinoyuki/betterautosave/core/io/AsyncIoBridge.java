package com.shinoyuki.betterautosave.core.io;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.mixin.accessor.ChunkStorageAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

public final class AsyncIoBridge {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    public CompletableFuture<Void> storeChunk(ServerLevel level, ChunkPos pos, CompoundTag tag) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        IOWorker worker = ((ChunkStorageAccessor) chunkMap).betterautosave$getWorker();
        if (worker == null) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "ChunkMap.worker missing for level " + level.dimension().location()));
            return failed;
        }
        return worker.store(pos, tag);
    }

    public CompletableFuture<Void> synchronize(ServerLevel level) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        IOWorker worker = ((ChunkStorageAccessor) chunkMap).betterautosave$getWorker();
        if (worker == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return worker.synchronize(true);
        } catch (Throwable t) {
            LOGGER.error("IOWorker synchronize failed for level {}", level.dimension().location(), t);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(t);
            return failed;
        }
    }
}
