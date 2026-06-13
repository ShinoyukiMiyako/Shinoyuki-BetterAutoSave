package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.state.CapturedSnapshot;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.util.Map;

/**
 * v0.2 chunk snapshot. 主线程 capture 时填入所有原料, worker 线程读字段拼装 NBT。
 * preBuiltCoreTag / preBuiltFullTag 二选一: PARTIAL/DISABLED 模式主线程构 core,
 * worker 端 assembler 补 sections; FULL 模式主线程构完整 tag, worker 仅做 IO。
 */
public record ChunkSnapshot(
        ChunkPos pos,
        ResourceKey<Level> dimension,
        int dataVersion,
        int minSection,
        long lastUpdate,
        long inhabitedTime,
        String statusId,
        LevelChunkSection[] sectionsCopy,
        DataLayer[] skyLights,
        DataLayer[] blockLights,
        int lightMinSection,
        int lightMaxSection,
        boolean isLightOn,
        Map<Heightmap.Types, long[]> heightmapsRaw,
        ListTag blockEntitiesNbt,
        ChunkAccess.TicksToSave ticks,
        ShortList[] postProcessing,
        UpgradeData upgradeData,
        BlendingData blendingData,
        BelowZeroRetrogen belowZeroRetrogen,
        Map<Structure, StructureStart> structureStarts,
        Map<Structure, LongSet> structureRefs,
        StructurePieceSerializationContext structureContext,
        long capturedGeneration,
        ChunkSaveState state,
        CompoundTag preBuiltCoreTag,
        CompoundTag preBuiltFullTag,
        ConfigSpec.EventCompatMode mode
) implements CapturedSnapshot {

    /**
     * v0.1 兼容入口: 主线程已经通过 ChunkSerializer.write 构好完整 tag,
     * 仅 worker 端做 IO。等 v0.2 capture procedure 全量接通后此方法被替换。
     */
    public static ChunkSnapshot ofPrebuiltFullTag(
            ChunkPos pos,
            ResourceKey<Level> dimension,
            CompoundTag preBuiltFullTag,
            long capturedGeneration,
            ChunkSaveState state,
            ConfigSpec.EventCompatMode mode
    ) {
        return new ChunkSnapshot(
                pos,
                dimension,
                0,
                0,
                0L,
                0L,
                "",
                null,
                null,
                null,
                0,
                0,
                false,
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                capturedGeneration,
                state,
                null,
                preBuiltFullTag,
                mode
        );
    }
}
