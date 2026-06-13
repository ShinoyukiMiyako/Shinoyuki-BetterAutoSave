package com.shinoyuki.betterautosave.mixin.accessor;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@Mixin(ChunkSerializer.class)
public interface ChunkSerializerInvoker {

    @Accessor("BLOCK_STATE_CODEC")
    static Codec<PalettedContainer<BlockState>> betterautosave$blockStateCodec() {
        throw new AssertionError();
    }

    @Invoker("makeBiomeCodec")
    static Codec<PalettedContainerRO<Holder<Biome>>> betterautosave$makeBiomeCodec(Registry<Biome> registry) {
        throw new AssertionError();
    }

    @Invoker("packStructureData")
    static CompoundTag betterautosave$packStructureData(
            StructurePieceSerializationContext context,
            ChunkPos pos,
            Map<Structure, StructureStart> starts,
            Map<Structure, LongSet> references) {
        throw new AssertionError();
    }

    @Invoker("saveTicks")
    static void betterautosave$saveTicks(ServerLevel level, CompoundTag tag, ChunkAccess.TicksToSave ticks) {
        throw new AssertionError();
    }
}
