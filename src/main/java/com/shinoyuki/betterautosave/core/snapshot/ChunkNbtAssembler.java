package com.shinoyuki.betterautosave.core.snapshot;

import com.mojang.serialization.Codec;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.mixin.accessor.ChunkSerializerInvoker;
import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.slf4j.Logger;

/**
 * Worker 线程的 NBT 拼装器。负责把 ChunkSnapshot 翻译为最终落盘 CompoundTag:
 *
 * - FULL 模式: snapshot.preBuiltFullTag 已是完整 tag, 直接返回
 * - PARTIAL / DISABLED 模式: snapshot.preBuiltCoreTag 是除 sections 外的全部字段,
 *   worker 读 snapshot.sectionsCopy + skyLights + blockLights 编码 sections ListTag
 *   后 put 进核心 tag 返回
 *
 * 编码 block_states 走 ChunkSerializerInvoker.BLOCK_STATE_CODEC, 编码 biomes 走
 * makeBiomeCodec(registryAccess.lookup(BIOME)) 拿到的 PalettedContainerRO codec。
 * 两者均使用 NbtOps.INSTANCE 与 vanilla 一致。
 */
public final class ChunkNbtAssembler {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private ChunkNbtAssembler() {
    }

    public static CompoundTag assemble(ChunkSnapshot snapshot) {
        ServerThreadAssert.assertOnWorkerThread();

        CompoundTag fullTag = snapshot.preBuiltFullTag();
        if (fullTag != null) {
            return fullTag;
        }

        CompoundTag tag = snapshot.preBuiltCoreTag();
        if (tag == null) {
            throw new IllegalStateException(
                    "Snapshot for chunk " + snapshot.pos() + " has neither preBuiltFullTag nor preBuiltCoreTag");
        }

        ListTag sectionsTag = buildSectionsTag(snapshot);
        tag.put("sections", sectionsTag);
        return tag;
    }

    private static ListTag buildSectionsTag(ChunkSnapshot snapshot) {
        Codec<PalettedContainer<BlockState>> blockStateCodec = ChunkSerializerInvoker.betterautosave$blockStateCodec();
        Registry<Biome> biomeRegistry = snapshot.structureContext().registryAccess().registryOrThrow(Registries.BIOME);
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec =
                ChunkSerializerInvoker.betterautosave$makeBiomeCodec(biomeRegistry);

        ListTag sectionsTag = new ListTag();
        LevelChunkSection[] sectionsCopy = snapshot.sectionsCopy();
        int sectionsLen = sectionsCopy.length;
        int minSection = snapshot.minSection();
        int lightMin = snapshot.lightMinSection();
        int lightMax = snapshot.lightMaxSection();
        DataLayer[] sky = snapshot.skyLights();
        DataLayer[] block = snapshot.blockLights();

        for (int sectionY = lightMin; sectionY < lightMax; sectionY++) {
            int idx = sectionY - lightMin;
            int sectionIndex = sectionY - minSection;
            boolean inChunk = sectionIndex >= 0 && sectionIndex < sectionsLen;
            DataLayer skyData = sky[idx];
            DataLayer blockData = block[idx];
            if (!inChunk && skyData == null && blockData == null) {
                continue;
            }

            CompoundTag sectionTag = new CompoundTag();
            if (inChunk) {
                LevelChunkSection section = sectionsCopy[sectionIndex];
                sectionTag.put("block_states",
                        blockStateCodec.encodeStart(NbtOps.INSTANCE, section.getStates())
                                .getOrThrow(false, LOGGER::error));
                sectionTag.put("biomes",
                        biomeCodec.encodeStart(NbtOps.INSTANCE, section.getBiomes())
                                .getOrThrow(false, LOGGER::error));
            }
            if (skyData != null) {
                sectionTag.putByteArray("SkyLight", skyData.getData());
            }
            if (blockData != null) {
                sectionTag.putByteArray("BlockLight", blockData.getData());
            }
            sectionTag.putByte("Y", (byte) sectionY);
            sectionsTag.add(sectionTag);
        }
        return sectionsTag;
    }
}
