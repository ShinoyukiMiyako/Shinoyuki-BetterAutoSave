package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.core.load.LoadDeferredActions;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v0.x 异步加载内层钩子: 当 {@code ChunkSerializer.read} 经 {@link ChunkMapLoadMixin} 的 wrap 跑在 load worker 上
 * 时, 把 read 体内三类主线程独占副作用 (反编译金源 ChunkSerializer.java:123/130/135/139 + 216/240) 用
 * {@code @Redirect} 截走, 排进 {@link LoadDeferredActions} 留主线程回放; worker 只做纯解码与建对象。
 *
 * <p>判据 {@link LoadDeferredActions#current()}: 非空 = 当前在 worker off-thread 捕获 -> defer; null = 主线程
 * (FULL / 未启用 / wrap 兜底直接 original.call) -> inline 原样执行, 与 vanilla 零偏差。read 是 {@code public
 * static}, 故所有 redirect handler 必须 static。
 *
 * <p>实参快照纪律: {@code sectionpos}/{@code levelchunksection}/{@code datalayer} 在 read 的 section 循环里逐次
 * 重赋值新对象 (ChunkSerializer.java:98/120/135), redirect handler 把它们捕进 lambda 闭包是按调用那刻的引用快照,
 * 不跨迭代复用可变引用, 故 defer 的回放与原 inline 调用语义一致。POI/光照逐 section 互不依赖, 集中到 read 返回后
 * 回放不改结果。
 *
 * <p>{@code checkConsistencyWithBlocks} 单点循环内多次命中、{@code queueSectionData}/{@code post} 各两处命中,
 * 同一 {@code @Redirect} 默认绑定方法内全部同签名 INVOKE, 全部 defer —— 正符合需求。
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerLoadMixin {

    @Redirect(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
                            + "checkConsistencyWithBlocks(Lnet/minecraft/core/SectionPos;"
                            + "Lnet/minecraft/world/level/chunk/LevelChunkSection;)V"))
    private static void betterautosave$deferCheckConsistency(PoiManager poiManager, SectionPos sectionPos,
                                                             LevelChunkSection section) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(() -> poiManager.checkConsistencyWithBlocks(sectionPos, section));
        } else {
            poiManager.checkConsistencyWithBlocks(sectionPos, section);
        }
    }

    @Redirect(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;"
                            + "retainData(Lnet/minecraft/world/level/ChunkPos;Z)V"))
    private static void betterautosave$deferRetainData(LevelLightEngine lightEngine, ChunkPos pos, boolean retain) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(() -> lightEngine.retainData(pos, retain));
        } else {
            lightEngine.retainData(pos, retain);
        }
    }

    @Redirect(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;"
                            + "queueSectionData(Lnet/minecraft/world/level/LightLayer;"
                            + "Lnet/minecraft/core/SectionPos;"
                            + "Lnet/minecraft/world/level/chunk/DataLayer;)V"))
    private static void betterautosave$deferQueueSectionData(LevelLightEngine lightEngine, LightLayer layer,
                                                             SectionPos sectionPos, DataLayer data) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(() -> lightEngine.queueSectionData(layer, sectionPos, data));
        } else {
            lightEngine.queueSectionData(layer, sectionPos, data);
        }
    }

    /**
     * ChunkDataEvent.Load 派发 (金源 :216 LEVELCHUNK 分支 / :240 PROTOCHUNK 分支)。事件对象在 worker 已构造完毕
     * (只持有 chunkaccess/tag/type 引用, 构造无主线程依赖), 仅 {@code post} 派发须留主线程 (设计第六节第 3 条:
     * 第三方 Load listener 普遍假设主线程)。vanilla 两处都是丢弃返回值的裸语句 post, 故 defer 分支返回 false 安全
     * (回放里的真实 post 返回值同样被丢弃)。
     */
    @Redirect(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/eventbus/api/IEventBus;"
                            + "post(Lnet/minecraftforge/eventbus/api/Event;)Z"))
    private static boolean betterautosave$deferLoadEvent(IEventBus eventBus, Event event) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(() -> eventBus.post(event));
            return false;
        }
        return eventBus.post(event);
    }
}
