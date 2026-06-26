package com.shinoyuki.betterautosave.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("level")
    ServerLevel betterautosave$getLevel();

    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> betterautosave$getVisibleChunkMap();

    /**
     * 调 scheduleChunkLoad 续段里 read 之后那次 {@code this.markPosition(pos, chunkType)}
     * (ChunkMap.java:579/618 {@code private byte markPosition(ChunkPos, ChunkStatus.ChunkType)} 写
     * chunkTypeCache)。异步加载 v2 的 replay-stage 在主线程补做这步 (vanilla 续段里 read 同步出值后即调,
     * 异步把 read 搬下线程后须在主线程 replay 阶段对齐), 经 {@code @Invoker} 调起 private 目标方法, AP 可靠解析
     * SRG 名 (优于 @Shadow private)。chunkTypeCache 非并发, replay-stage 用 mainThreadExecutor 跑故在主线程。
     */
    @Invoker("markPosition")
    byte betterautosave$markPosition(ChunkPos pos, ChunkStatus.ChunkType type);

    /**
     * 取 ChunkMap 的私有 {@code poiManager} 字段。在线回退编排 (OnlineChunkRestorer) 复用
     * ChunkLoadTask 跑 ChunkSerializer.read 时需要它 (read 内的 POI 一致性校验被 BAS defer,
     * 但 lambda 捕获了 poiManager, 不能传 null)。ChunkMap.poiManager 为 private final, 经 accessor 取。
     */
    @Accessor("poiManager")
    PoiManager betterautosave$getPoiManager();
}
