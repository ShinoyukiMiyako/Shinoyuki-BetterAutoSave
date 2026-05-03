package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.core.scheduler.ChunkSavePriority;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.core.state.ChunkSaveStateAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void betterautosave$interceptSaveAllChunks(boolean flush, CallbackInfo ci) {
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }
        if (!BetterAutoSaveConfig.enabled()) {
            return;
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline.isDegraded()) {
            return;
        }
        if (flush) {
            pipeline.drainPending(BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L);
            return;
        }

        SaveScheduler scheduler = BetterAutoSaveCore.scheduler();
        if (scheduler.isShutdownMode()) {
            return;
        }

        long deadlineMillis = System.currentTimeMillis()
                + (long) BetterAutoSaveConfig.deadlineGuardSeconds() * 1000L;
        String dimensionId = level.dimension().location().toString();
        int enqueued = 0;

        for (ChunkHolder holder : visibleChunkMap.values()) {
            ChunkAccess chunk = holder.getLastAvailable();
            if (!(chunk instanceof LevelChunk)) {
                continue;
            }
            if (!chunk.isUnsaved()) {
                continue;
            }
            ChunkPos pos = chunk.getPos();
            long packed = pos.toLong();
            long sequence = scheduler.nextEnqueueSequence();
            ChunkSaveState state = ((ChunkSaveStateAccess) chunk).betterautosave$getOrCreateState(
                    packed, dimensionId, sequence);
            if (state.phase() == ChunkSaveState.Phase.CLEAN) {
                state.markDirty();
            }
            ChunkSavePriority priority = new ChunkSavePriority(packed, dimensionId, sequence, deadlineMillis, 0.0);
            if (scheduler.enqueueChunk(priority)) {
                enqueued++;
            }
        }
        if (enqueued > 0) {
            LOGGER.info("BetterAutoSave intercepted autosave: enqueued {} dirty chunks from {}", enqueued, dimensionId);
        } else {
            LOGGER.debug("BetterAutoSave intercepted autosave for {} (no dirty chunks)", dimensionId);
        }
        ci.cancel();
    }
}
