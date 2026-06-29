package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.core.load.LoadDeferredActions;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 异步加载内层钩子 (与 {@link ChunkSerializerLoadMixin} 对称补齐): 当 {@code ChunkSerializer.read} 的 LEVELCHUNK
 * 分支 (反编译金源 ChunkSerializer.java:161) 在 load worker 上 {@code new LevelChunk(...)} 时, 主构造器尾部
 * (反编译金源 LevelChunk.java:97 {@code this.capProvider.initInternal();}) 会经
 * {@code CapabilityProvider.AsField.initInternal() -> gatherCapabilities -> ForgeEventFactory.gatherCapabilities ->
 * EVENT_BUS.post(AttachCapabilitiesEvent)} 在 worker 线程派发全局 {@code AttachCapabilitiesEvent<Chunk>}
 * (并触发第三方 cap provider 的工厂回调)。该事件与已被 {@code ChunkSerializerLoadMixin} defer 的
 * {@code ChunkDataEvent.Load} 同性质 (全局事件总线派发, 第三方 listener 普遍假设主线程), 此前整段漏 defer ——
 * 本 mixin 把它对称纳入 {@link LoadDeferredActions} 留主线程 replay。
 *
 * <p>为何 {@code @Redirect} 而非 {@code @WrapOperation}: {@code initInternal} 是 forge 给 {@code LevelChunk} 私有字段
 * {@code capProvider} 调的内部方法 (非 vanilla 派发点), 不存在其它 mod 对这一指令的 {@code @ModifyArg}/{@code @Redirect}
 * 注入器需要保留 (对比事件 {@code post} 那处 architectury 会 @ModifyArg, 故那处用 @WrapOperation), 故此处用最直接的
 * {@code @Redirect} 整段替换调用决定 defer/inline 即可。
 *
 * <p>判据 {@link LoadDeferredActions#current()}: 非空 = 当前在 worker off-thread 捕获 -> 把这次 {@code initInternal}
 * 调用 (实参快照 = 该 {@code capProvider} 实例) 封进 Runnable 排进 sink, worker <b>不</b>派发事件; null = 主线程
 * (FULL / 未启用 / fallback 重读) -> 原样 {@code capProvider.initInternal()} inline, 与 vanilla 零偏差。
 *
 * <p>本主构造器被<b>所有</b> {@code LevelChunk} 创建路径走 (不只 read; vanilla:79/100 两个委托构造器都 {@code this(...)}
 * 委托到本主构造器, {@code initInternal} 仅本主构造器调一次), {@code current()} 判据保证只在 worker read 期 defer,
 * 其余创建路径 (主线程 protoChunkToFullChunk 等) 恒 null -> inline, 行为不变。
 *
 * <p>中间态安全 (worker 期 cap 未 gather): {@code AsField} 的 {@code isLazy=false}, vanilla {@code getCapabilities()}
 * 仅在 {@code isLazy && !initialized} 时才自动 gather (CapabilityProvider.java:85), 故 defer 窗口内 cap 字段为 null
 * 时即便误调 {@code getCapability} 也只返回 {@code LazyOptional.empty()}、不 post 事件; 且 {@code ImposterProtoChunk}
 * (read LEVELCHUNK 分支的实际返回包装) 不代理 cap, worker -> replay 之间无人触 cap, 故 defer 不暴露半初始化态。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkCapsLoadMixin {

    @Redirect(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/ChunkPos;"
            + "Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/ticks/LevelChunkTicks;"
            + "Lnet/minecraft/world/ticks/LevelChunkTicks;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;"
            + "Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;"
            + "Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/common/capabilities/CapabilityProvider$AsField;initInternal()V"))
    private void betterautosave$deferCapsGather(CapabilityProvider.AsField<?> capProvider) {
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(capProvider::initInternal);
        } else {
            capProvider.initInternal();
        }
    }
}
