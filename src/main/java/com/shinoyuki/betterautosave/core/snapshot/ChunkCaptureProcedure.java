package com.shinoyuki.betterautosave.core.snapshot;

import com.mojang.serialization.Codec;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.mixin.accessor.ChunkSerializerInvoker;
import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkDataEvent;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主线程 capture: 把 LevelChunk 当前状态固化为 ChunkSnapshot, 不持有任何与活跃世界共享可变状态的引用。
 *
 * 三档行为差异:
 * - FULL: 直接调 vanilla ChunkSerializer.write 拿完整 tag, 触发 ChunkDataEvent.Save, worker 仅做 IO
 * - PARTIAL: 主线程构 core tag (不含 sections), 触发 ChunkDataEvent.Save, worker 拼 sections + IO
 * - DISABLED: 主线程构 core tag, 跳过事件, worker 拼 sections + IO
 */
public final class ChunkCaptureProcedure {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private ChunkCaptureProcedure() {
    }

    public static ChunkSnapshot capture(
            LevelChunk chunk,
            ServerLevel level,
            ChunkSaveState state,
            ConfigSpec.EventCompatMode mode) {
        ServerThreadAssert.assertOnServerThread(level.getServer());

        long captured = state.enterSerializing();

        ChunkPos pos = chunk.getPos();
        int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        int minSection = chunk.getMinSection();
        long lastUpdate = level.getGameTime();
        long inhabitedTime = chunk.getInhabitedTime();
        ResourceLocation statusKey = BuiltInRegistries.CHUNK_STATUS.getKey(chunk.getStatus());
        String statusId = statusKey != null ? statusKey.toString() : ChunkStatus.EMPTY.toString();

        LevelChunkSection[] sectionsCopy = copySections(chunk.getSections());

        LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
        int lightMin = lightEngine.getMinLightSection();
        int lightMax = lightEngine.getMaxLightSection();
        int lightLen = lightMax - lightMin;
        DataLayer[] skyLights = new DataLayer[lightLen];
        DataLayer[] blockLights = new DataLayer[lightLen];
        for (int sectionY = lightMin; sectionY < lightMax; sectionY++) {
            int idx = sectionY - lightMin;
            DataLayer skyData = lightEngine.getLayerListener(LightLayer.SKY)
                    .getDataLayerData(SectionPos.of(pos, sectionY));
            DataLayer blockData = lightEngine.getLayerListener(LightLayer.BLOCK)
                    .getDataLayerData(SectionPos.of(pos, sectionY));
            skyLights[idx] = (skyData != null && !skyData.isEmpty()) ? skyData.copy() : null;
            blockLights[idx] = (blockData != null && !blockData.isEmpty()) ? blockData.copy() : null;
        }

        Map<Heightmap.Types, long[]> heightmapsRaw = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (chunk.getStatus().heightmapsAfter().contains(entry.getKey())) {
                heightmapsRaw.put(entry.getKey(), entry.getValue().getRawData().clone());
            }
        }

        ListTag blockEntitiesNbt = new ListTag();
        for (var bePos : chunk.getBlockEntitiesPos()) {
            CompoundTag beTag = chunk.getBlockEntityNbtForSaving(bePos);
            if (beTag != null) {
                blockEntitiesNbt.add(beTag);
            }
        }

        ChunkAccess.TicksToSave ticks = chunk.getTicksForSerialization();
        ShortList[] postProcessing = chunk.getPostProcessing();
        UpgradeData upgradeData = chunk.getUpgradeData();
        BlendingData blendingData = chunk.getBlendingData();
        BelowZeroRetrogen belowZeroRetrogen = chunk.getBelowZeroRetrogen();
        Map<Structure, StructureStart> structureStarts = new LinkedHashMap<>(chunk.getAllStarts());
        Map<Structure, LongSet> structureRefs = new LinkedHashMap<>(chunk.getAllReferences());
        StructurePieceSerializationContext structureContext = StructurePieceSerializationContext.fromLevel(level);

        boolean isLightOn = chunk.isLightCorrect();

        CompoundTag preBuiltCoreTag = null;
        CompoundTag preBuiltFullTag = null;

        if (mode == ConfigSpec.EventCompatMode.FULL) {
            preBuiltFullTag = ChunkSerializer.write(level, chunk);
            MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, preBuiltFullTag));
        } else {
            preBuiltCoreTag = buildCoreTag(
                    level,
                    pos,
                    dataVersion,
                    minSection,
                    lastUpdate,
                    inhabitedTime,
                    statusId,
                    isLightOn,
                    blockEntitiesNbt,
                    heightmapsRaw,
                    structureContext,
                    structureStarts,
                    structureRefs,
                    ticks,
                    postProcessing,
                    upgradeData,
                    blendingData,
                    belowZeroRetrogen);
            if (mode == ConfigSpec.EventCompatMode.PARTIAL) {
                MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, preBuiltCoreTag));
            }
        }

        return new ChunkSnapshot(
                pos,
                level.dimension(),
                dataVersion,
                minSection,
                lastUpdate,
                inhabitedTime,
                statusId,
                sectionsCopy,
                skyLights,
                blockLights,
                lightMin,
                lightMax,
                isLightOn,
                heightmapsRaw,
                blockEntitiesNbt,
                ticks,
                postProcessing,
                upgradeData,
                blendingData,
                belowZeroRetrogen,
                structureStarts,
                structureRefs,
                structureContext,
                captured,
                state,
                preBuiltCoreTag,
                preBuiltFullTag,
                mode);
    }

    private static LevelChunkSection[] copySections(LevelChunkSection[] live) {
        LevelChunkSection[] copy = new LevelChunkSection[live.length];
        for (int i = 0; i < live.length; i++) {
            LevelChunkSection original = live[i];
            PalettedContainer<net.minecraft.world.level.block.state.BlockState> statesCopy = original.getStates().copy();
            PalettedContainerRO<Holder<Biome>> biomesRaw = original.getBiomes();
            PalettedContainerRO<Holder<Biome>> biomesCopy;
            if (biomesRaw instanceof PalettedContainer<?> pcRaw) {
                @SuppressWarnings("unchecked")
                PalettedContainer<Holder<Biome>> pc = (PalettedContainer<Holder<Biome>>) pcRaw;
                biomesCopy = pc.copy();
            } else {
                biomesCopy = biomesRaw;
            }
            copy[i] = new LevelChunkSection(statesCopy, biomesCopy);
        }
        return copy;
    }

    private static CompoundTag buildCoreTag(
            ServerLevel level,
            ChunkPos pos,
            int dataVersion,
            int minSection,
            long lastUpdate,
            long inhabitedTime,
            String statusId,
            boolean isLightOn,
            ListTag blockEntitiesNbt,
            Map<Heightmap.Types, long[]> heightmapsRaw,
            StructurePieceSerializationContext structureContext,
            Map<Structure, StructureStart> structureStarts,
            Map<Structure, LongSet> structureRefs,
            ChunkAccess.TicksToSave ticks,
            ShortList[] postProcessing,
            UpgradeData upgradeData,
            BlendingData blendingData,
            BelowZeroRetrogen belowZeroRetrogen) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", dataVersion);
        tag.putInt("xPos", pos.x);
        tag.putInt("yPos", minSection);
        tag.putInt("zPos", pos.z);
        tag.putLong("LastUpdate", lastUpdate);
        tag.putLong("InhabitedTime", inhabitedTime);
        tag.putString("Status", statusId);
        if (isLightOn) {
            tag.putBoolean("isLightOn", true);
        }

        if (blendingData != null) {
            BlendingData.CODEC.encodeStart(NbtOps.INSTANCE, blendingData)
                    .resultOrPartial(err -> LOGGER.error("[BetterAutoSave] blending_data encode error: {}", err))
                    .ifPresent(t -> tag.put("blending_data", t));
        }
        if (belowZeroRetrogen != null) {
            BelowZeroRetrogen.CODEC.encodeStart(NbtOps.INSTANCE, belowZeroRetrogen)
                    .resultOrPartial(err -> LOGGER.error("[BetterAutoSave] below_zero_retrogen encode error: {}", err))
                    .ifPresent(t -> tag.put("below_zero_retrogen", t));
        }
        if (!upgradeData.isEmpty()) {
            tag.put("UpgradeData", upgradeData.write());
        }

        CompoundTag heightmapsTag = new CompoundTag();
        for (Map.Entry<Heightmap.Types, long[]> entry : heightmapsRaw.entrySet()) {
            heightmapsTag.put(entry.getKey().getSerializationKey(), new LongArrayTag(entry.getValue()));
        }
        tag.put("Heightmaps", heightmapsTag);

        tag.put("structures",
                ChunkSerializerInvoker.betterautosave$packStructureData(structureContext, pos, structureStarts, structureRefs));

        ChunkSerializerInvoker.betterautosave$saveTicks(level, tag, ticks);

        tag.put("PostProcessing", ChunkSerializer.packOffsets(postProcessing));

        tag.put("block_entities", blockEntitiesNbt);

        return tag;
    }

    public static Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec(ServerLevel level) {
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        return ChunkSerializerInvoker.betterautosave$makeBiomeCodec(registry);
    }
}
