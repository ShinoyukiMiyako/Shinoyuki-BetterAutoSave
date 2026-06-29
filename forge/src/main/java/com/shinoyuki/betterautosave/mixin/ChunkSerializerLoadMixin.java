package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.core.load.LoadCodecGuard;
import com.shinoyuki.betterautosave.core.load.LoadDeferredActions;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.lighting.LevelLightEngine;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/**
 * v0.x 异步加载内层钩子: 当 {@code ChunkSerializer.read} 经 {@link ChunkMapLoadMixin} 的 wrap 跑在 load worker 上
 * 时, 把 read 体内三类主线程独占副作用 (反编译金源 ChunkSerializer.java:123/130/135/139 + 216/240) 用
 * {@code @WrapOperation} 截走, 排进 {@link LoadDeferredActions} 留主线程回放; worker 只做纯解码与建对象。
 *
 * <p>全 {@code @WrapOperation} 化 (含 POI/光照三处, 非 {@code @Redirect}): 保留各 INVOKE 原指令, 令未来其它 mod
 * 对这几处的 {@code @ModifyArg}/{@code @Redirect} 注入器照常生效 (如 architectury 对事件 post 的 @ModifyArg),
 * 我们只在外层包裹决定 defer/inline; 若用 {@code @Redirect} 整段替换调用, 会与同指令的他 mod 注入器互斥而崩。
 *
 * <p>判据 {@link LoadDeferredActions#current()}: 非空 = 当前在 worker off-thread 捕获 -> defer; null = 主线程
 * (FULL / 未启用 / wrap 兜底直接 original.call) -> inline 原样执行, 与 vanilla 零偏差。read 是 {@code public
 * static}, 故所有 handler 必须 static。
 *
 * <p>实参快照纪律: {@code sectionpos}/{@code levelchunksection}/{@code datalayer} 在 read 的 section 循环里逐次
 * 重赋值新对象 (ChunkSerializer.java:98/120/135), wrap handler 把它们 (连同 {@code Operation} 闭包) 捕进 lambda
 * 是按调用那刻的引用快照, 不跨迭代复用可变引用, 故 defer 的回放与原 inline 调用语义一致。POI/光照逐 section 互不
 * 依赖, 集中到 read 返回后回放不改结果。
 *
 * <p>{@code checkConsistencyWithBlocks} 单点循环内多次命中、{@code queueSectionData}/{@code post} 各两处命中,
 * 同一 {@code @WrapOperation} 默认绑定方法内全部同签名 INVOKE, 全部 defer —— 正符合需求。
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerLoadMixin {

    @WrapOperation(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
                            + "checkConsistencyWithBlocks(Lnet/minecraft/core/SectionPos;"
                            + "Lnet/minecraft/world/level/chunk/LevelChunkSection;)V"))
    private static void betterautosave$deferCheckConsistency(PoiManager poiManager, SectionPos sectionPos,
                                                             LevelChunkSection section, Operation<Void> original) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(() -> original.call(poiManager, sectionPos, section));
        } else {
            original.call(poiManager, sectionPos, section);
        }
    }

    @WrapOperation(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;"
                            + "retainData(Lnet/minecraft/world/level/ChunkPos;Z)V"))
    private static void betterautosave$deferRetainData(LevelLightEngine lightEngine, ChunkPos pos, boolean retain,
                                                       Operation<Void> original) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(() -> original.call(lightEngine, pos, retain));
        } else {
            original.call(lightEngine, pos, retain);
        }
    }

    @WrapOperation(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;"
                            + "queueSectionData(Lnet/minecraft/world/level/LightLayer;"
                            + "Lnet/minecraft/core/SectionPos;"
                            + "Lnet/minecraft/world/level/chunk/DataLayer;)V"))
    private static void betterautosave$deferQueueSectionData(LevelLightEngine lightEngine, LightLayer layer,
                                                             SectionPos sectionPos, DataLayer data,
                                                             Operation<Void> original) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(() -> original.call(lightEngine, layer, sectionPos, data));
        } else {
            original.call(lightEngine, layer, sectionPos, data);
        }
    }

    /**
     * ForgeCaps 反序列化 (金源 :162 LEVELCHUNK 分支 {@code ((LevelChunk)chunkaccess).readCapsFromNBT(
     * tag.getCompound("ForgeCaps"))}): 把盘上存的第三方 chunk capability 数据反序列化回各 cap 实例。
     * {@code readCapsFromNBT -> capProvider.deserializeInternal -> deserializeCaps} 触发第三方 cap 的
     * {@code INBTSerializable.deserializeNBT}, 与 cap gather ({@link LevelChunkCapsLoadMixin} defer 的
     * {@code initInternal}) 同性质——第三方 cap 代码普遍假设主线程, 且 deserialize 必须发生在 gather 建出 cap 实例
     * <b>之后</b>。两者都 defer 进同一 {@link LoadDeferredActions} sink, 按 read 执行序 (gather:161 先于
     * deserialize:162) 入列、replay 时同序回放, 保持 "先 gather 后 deserialize" 的依赖。
     *
     * <p>{@code @WrapOperation} 而非 {@code @Redirect}: 与事件 {@code post} 同理, 保留原 INVOKE 让其它 mod 对
     * {@code readCapsFromNBT} 的注入器照常生效, 我们只在外层包裹决定 defer/inline。worker 期 defer 分支
     * {@code original.call(levelChunk, tag)} 在主线程 replay 时才真正执行反序列化。
     */
    @WrapOperation(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunk;"
                            + "readCapsFromNBT(Lnet/minecraft/nbt/CompoundTag;)V"))
    private static void betterautosave$deferReadCapsFromNBT(LevelChunk levelChunk, CompoundTag tag,
                                                            Operation<Void> original) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(() -> original.call(levelChunk, tag));
        } else {
            original.call(levelChunk, tag);
        }
    }

    /**
     * ChunkDataEvent.Load 派发 (金源 :216 LEVELCHUNK 分支 / :240 PROTOCHUNK 分支)。事件对象在 worker 已构造完毕
     * (只持有 chunkaccess/tag/type 引用, 构造无主线程依赖), 仅 {@code post} 派发须留主线程 (设计第六节第 3 条:
     * 第三方 Load listener 普遍假设主线程)。vanilla 两处都是丢弃返回值的裸语句 post, 故 defer 分支返回 false 安全
     * (回放里的真实 post 返回值同样被丢弃)。
     *
     * <p>用 {@code @WrapOperation} 而非 {@code @Redirect}: 该 post 同时被其它 mod 以 {@code @ModifyArg} 命中
     * (如 architectury 给 ChunkDataEvent 附 level), {@code @Redirect} 会整段替换调用、令对方 @ModifyArg 失配崩溃;
     * {@code @WrapOperation} 保留原 INVOKE 让对方注入器照常生效, 我们只在外层包裹决定 defer/inline。
     * {@code original.call(eventBus, event)} 收到的 event 已是对方 @ModifyArg 处理后的对象。
     */
    @WrapOperation(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/eventbus/api/IEventBus;"
                            + "post(Lnet/minecraftforge/eventbus/api/Event;)Z"))
    private static boolean betterautosave$deferLoadEvent(IEventBus eventBus, Event event,
                                                         Operation<Boolean> original) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(() -> original.call(eventBus, event));
            return false;
        }
        return original.call(eventBus, event);
    }

    /**
     * v2.1 L1 细粒度 Codec 锁: 只串行化 read 内<b>唯一</b>跨线程竞态的结构拼图解码子树, 其余 section/调色板/biome/
     * heightmap/方块实体/ForgeCaps 解码全部无锁并行 —— 取代 v2 "{@code LoadCodecGuard} 包整段 read" 的粗粒度串行。
     *
     * <p>为何是这两个调用而非别处 (取证, docs/ASYNC_LOAD_DESIGN.md L1): read 体内绝大多数 {@code .parse} 是
     * thread-confined 的——{@code BLOCK_STATE_CODEC}/biome codec 经 {@code byNameCodec()} 只读已 frozen 的
     * {@code BuiltInRegistries}, 解码产物是本 section 私有的 {@code PalettedContainer}; {@code BlendingData.CODEC}/
     * {@code BelowZeroRetrogen.CODEC} 是纯 {@code RecordCodecBuilder} 解到新建 record; read 内不做 DFU (版本升级已在
     * 上层 {@code ChunkStorage.upgradeChunkTag} 完成)。唯一真竞态在结构解码: {@code unpackStructureStart} 深处经
     * {@code StructurePoolElement.CODEC}(dispatch codec, 内嵌 {@code StructureProcessorType.LIST_CODEC =
     * RegistryFileCodec}) 读取<b>跨线程共享</b>的 {@code RegistryOps} 解析状态, 且 {@code Codec.dispatch} 的内部
     * map 非线程安全。C2ME 对此正是只用一把 {@code SynchronizedCodec} 锁 {@code StructurePoolElement.CODEC} 这一处
     * (旁证: 全 C2ME 仅此一处加锁, read 其余整段在 worker 上无锁并行)。
     *
     * <p>为何锁整 {@code unpackStructureStart} 调用而非下钻到 {@code StructurePoolElement.CODEC.parse}: 竞态深埋在
     * {@code PiecesContainer.load} 的反射式 piece 类型分发里, 不同 piece 走不同子 Codec, mixin 无法精确钉到单个
     * {@code .parse} 指令。锁整调用是唯一覆盖完全的粒度。相邻的 {@code unpackStructureReferences} 纯 registry 只读
     * (理论可不锁), 但保守一并纳入同一把锁, 开销极小、杜绝漏判。
     *
     * <p>为何锁<b>无条件</b>施加 (worker 与主线程路径都锁, 不按 {@link LoadDeferredActions#current()} 区分): 该锁
     * 守护的是进程级共享的 static dispatch Codec / {@code RegistryOps} 缓存, 竞态是 "任意两个 decode 同时跑" 而非
     * "两个 worker"。fallback 路径 (worker 失败后主线程重读) 会令<b>主线程</b>的 read 与仍在解码别的区块的 worker 并发,
     * 二者同触该共享 Codec, 故主线程也必须持锁才安全 (与 C2ME 锁全局 Codec 字段使每个 decoder 都串行同理)。代价: 主线程
     * 仅在罕见 fallback / FULL 路径下、且仅在结构解码这微秒级切片上短暂阻塞, 绝非阻塞整段 read; PARTIAL 稳态主线程
     * 不走 read (read 全在 worker), 此锁对主线程零影响。{@code LoadCodecGuard} 是无嵌套获取的叶锁、worker 从不反向
     * 等主线程, 无锁序环、不可能死锁。
     *
     * <p>{@code @WrapOperation} 而非 {@code @Redirect}: 与事件 {@code post} 同理, 保留原 INVOKESTATIC 让未来其它 mod
     * 对这两个结构解码调用的 {@code @ModifyArg}/{@code @Redirect} 注入器照常生效, 我们只在外层包锁。
     */
    @WrapOperation(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;"
                            + "unpackStructureStart(Lnet/minecraft/world/level/levelgen/structure/pieces/"
                            + "StructurePieceSerializationContext;Lnet/minecraft/nbt/CompoundTag;J)Ljava/util/Map;"))
    private static Map<?, ?> betterautosave$lockStructureStartDecode(StructurePieceSerializationContext context,
                                                                     CompoundTag structuresTag, long seed,
                                                                     Operation<Map<?, ?>> original) {
        LoadCodecGuard.lock();
        try {
            return original.call(context, structuresTag, seed);
        } finally {
            LoadCodecGuard.unlock();
        }
    }

    @WrapOperation(method = "read",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;"
                            + "unpackStructureReferences(Lnet/minecraft/core/RegistryAccess;"
                            + "Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/Map;"))
    private static Map<?, ?> betterautosave$lockStructureReferencesDecode(RegistryAccess registryAccess, ChunkPos pos,
                                                                          CompoundTag structuresTag,
                                                                          Operation<Map<?, ?>> original) {
        LoadCodecGuard.lock();
        try {
            return original.call(registryAccess, pos, structuresTag);
        } finally {
            LoadCodecGuard.unlock();
        }
    }
}
