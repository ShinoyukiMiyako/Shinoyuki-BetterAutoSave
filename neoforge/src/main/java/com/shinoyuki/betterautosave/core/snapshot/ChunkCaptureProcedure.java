package com.shinoyuki.betterautosave.core.snapshot;

import com.mojang.serialization.Codec;
import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
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
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
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
        // 常规 capture: enterSerializing 推进 phase 到 SERIALIZING 并把当前代锁进 inFlightGeneration,
        // 锁到的代作为本快照的 capturedGeneration。
        return captureWithGeneration(chunk, level, state, mode, state.enterSerializing());
    }

    /**
     * 纯 capture 入口 —— 只固化最新内存为快照, **不**碰
     * phase 也**不**碰 inFlightGeneration (不调 enterSerializing), 不 offer。
     *
     * <p><b>为何不复用 {@link #capture}</b>: 在途碰撞发生时, 该 chunk 已有某代 IO 在飞
     * (phase=IO_PENDING, inFlightGeneration=在飞代)。若纯 capture 也调 enterSerializing, 会把
     * inFlightGeneration 改写成新代 —— 在飞那代 IO 落地时 generation==inFlightGeneration 误判
     * CLEAN_LANDED, 接力链断裂。本入口只读 {@code state.generation()} 作为接力快照的 capturedGeneration,
     * 在飞那代的 phase/inFlightGeneration 原样留给在飞 task, 落地时正确判 REQUEUE_DIRTY 触发重投接力。
     *
     * <p>capture 仍在主线程 (ServerThreadAssert), 与 capture 后的编辑天然串行, 故抓到的就是"截至本次
     * 卸载的最新代"。
     */
    public static ChunkSnapshot capturePending(
            LevelChunk chunk,
            ServerLevel level,
            ChunkSaveState state,
            ConfigSpec.EventCompatMode mode) {
        return captureWithGeneration(chunk, level, state, mode, state.generation());
    }

    /**
     * 实际把 {@code ChunkDataEvent.Save} 投到事件总线的 seam。生产绑到
     * {@link MinecraftForge#EVENT_BUS}; 单测注入记录性 dispatcher 验证 tag 选择与派发次数 —— bare JUnit
     * 下 Forge eventbus 注册 listener 需要事件类无参构造 (ModLauncher 字节码变换才补), 无法直接 post 真
     * 总线, 故把 post 抽到可注入 seam 而把 mode 守卫/tag 选择留在 {@link #dispatchSaveEvent} 内供测。
     */
    @FunctionalInterface
    public interface SaveEventDispatcher {
        void post(LevelChunk chunk, ServerLevel level, CompoundTag eventTag);
    }

    private static volatile SaveEventDispatcher saveEventDispatcher =
            (chunk, level, eventTag) -> NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, eventTag));

    /** 单测注入记录性 dispatcher; 返回原值供 teardown 还原。仅供测试, 生产代码不调。 */
    public static SaveEventDispatcher swapSaveEventDispatcher(SaveEventDispatcher dispatcher) {
        SaveEventDispatcher prev = saveEventDispatcher;
        saveEventDispatcher = dispatcher;
        return prev;
    }

    /**
     * 在主线程派发 Forge {@code ChunkDataEvent.Save}, 复刻 vanilla 在 {@code ChunkMap.save} 体内注入的
     * 事件 (Forge 把它注入到 ChunkSerializer.write 之后、IOWorker.store 之前)。常规 dispatch 路径与
     * pending 接力登记路径共用本入口, 杜绝两处派发逻辑漂移。
     *
     * <p><b>线程契约</b>: Forge listener 假定在主线程同步执行。两个调用点均在主线程: 常规路径在
     * {@link SnapshotPipeline#captureAndDispatchChunk} (mixin 拦截 save 在主线程), pending 路径在
     * {@link com.shinoyuki.betterautosave.mixin.ChunkMapSaveMixin} 碰撞分支 (同样在主线程)。绝不可在
     * worker / IOWorker 回调线程调本方法。
     *
     * <p><b>三档行为</b> (与 capture 的 tag 构建对齐):
     * PARTIAL 用 preBuiltCoreTag (无 sections), FULL 用 preBuiltFullTag (完整 tag), DISABLED 跳过派发。
     *
     * <p><b>异常语义</b>: 派发抛 (第三方 listener 故障) 原样冒泡, 由各调用点的 catch 按其语义降级
     * (常规路径 -> recoverAfterDispatchFailure 退 vanilla 同步; pending 路径 -> 不登记接力, 退信任在飞旧代)。
     */
    public static void dispatchSaveEvent(
            LevelChunk chunk,
            ServerLevel level,
            ChunkSnapshot snapshot,
            ConfigSpec.EventCompatMode mode,
            SaveMetrics metrics) {
        if (mode == ConfigSpec.EventCompatMode.DISABLED) {
            return;
        }
        CompoundTag eventTag = snapshot.preBuiltFullTag() != null
                ? snapshot.preBuiltFullTag()
                : snapshot.preBuiltCoreTag();
        long evT0 = System.nanoTime();
        saveEventDispatcher.post(chunk, level, eventTag);
        metrics.recordEventDispatchNs(System.nanoTime() - evT0);
    }

    private static ChunkSnapshot captureWithGeneration(
            LevelChunk chunk,
            ServerLevel level,
            ChunkSaveState state,
            ConfigSpec.EventCompatMode mode,
            long captured) {
        ServerThreadAssert.assertOnServerThread(level.getServer());

        ChunkPos pos = chunk.getPos();
        int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        int minSection = chunk.getMinSection();
        long lastUpdate = level.getGameTime();
        long inhabitedTime = chunk.getInhabitedTime();
        ResourceLocation statusKey = BuiltInRegistries.CHUNK_STATUS.getKey(chunk.getPersistedStatus());
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
            // 用 getPersistedStatus().heightmapsAfter() 过滤。NeoForge 21.1 对 ChunkSerializer.write 的补丁
            // (修 MC-308222) 对非 LevelChunk 状态额外落 WORLD_SURFACE_WG / OCEAN_FLOOR_WG (走的是 chunk 类型相关的
            // 更宽集合); 但 BAS 异步路径只接管 LevelChunk (ChunkMapSaveMixin 对非 LevelChunk 显式 bypass, capture
            // 形参即 LevelChunk), 而 LevelChunk 恒为 LEVELCHUNK 状态, 此时该更宽集合与 heightmapsAfter() 逐元素
            // 相等, 故此处 heightmapsAfter() 完全正确、零数据丢失。该等价依赖"只异步存 LevelChunk"不变式: 若将来
            // 管线接管 proto-chunk, 须改用补丁后的更宽集合以免漏落两个 _WG 高度图 (重新引入 MC-308222)。
            if (chunk.getPersistedStatus().heightmapsAfter().contains(entry.getKey())) {
                heightmapsRaw.put(entry.getKey(), entry.getValue().getRawData().clone());
            }
        }

        ListTag blockEntitiesNbt = new ListTag();
        for (var bePos : chunk.getBlockEntitiesPos()) {
            CompoundTag beTag = chunk.getBlockEntityNbtForSaving(bePos, level.registryAccess());
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
        } else {
            // NeoForge: chunk 数据附件 + LevelChunk 自定义光 (aux light) 必须在主线程取 (触 registries 与活跃状态),
            // 复刻 1.21.1 vanilla ChunkSerializer.write 的同名字段。手拼 core tag 漏这两字段会让依赖 NeoForge
            // 数据附件的 mod 在异步存档时静默丢数据 (1.20.1 Forge 无此字段, 是 NeoForge 端口新增的落盘契约)。
            Tag auxLightTag = chunk.getAuxLightManager(pos).serializeNBT(level.registryAccess());
            CompoundTag attachmentsTag;
            try {
                attachmentsTag = chunk.writeAttachmentsToNBT(level.registryAccess());
            } catch (Exception e) {
                LOGGER.error("[BetterAutoSave] chunk {} 数据附件写出抛异常, 本次存档不含附件 (附件序列化故障, 请联系对应 mod 作者)", pos, e);
                attachmentsTag = null;
            }
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
                    belowZeroRetrogen,
                    auxLightTag,
                    attachmentsTag);
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
            BelowZeroRetrogen belowZeroRetrogen,
            Tag auxLightTag,
            CompoundTag attachmentsTag) {
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

        // NeoForge 注入字段, 与 1.21.1 vanilla ChunkSerializer.write 对齐 (提取见 capture 处注释)。
        if (auxLightTag != null) {
            tag.put(LevelChunkAuxiliaryLightManager.LIGHT_NBT_KEY, auxLightTag);
        }
        if (attachmentsTag != null) {
            tag.put(AttachmentHolder.ATTACHMENTS_NBT_KEY, attachmentsTag);
        }

        return tag;
    }

    public static Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec(ServerLevel level) {
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        return ChunkSerializerInvoker.betterautosave$makeBiomeCodec(registry);
    }
}
