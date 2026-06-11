package com.shinoyuki.betterautosave.mixin;

import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v0.10.2 修复 (M-saveddata-dirty-visibility): 给 vanilla {@link SavedData#isDirty()} /
 * {@code setDirty(boolean)} 的 dirty 标志补一个 volatile 镜像, 消除 BAS 异步路径下的跨线程
 * 可见性 race.
 *
 * <p><b>race 本体</b>: vanilla 的 {@code SavedData.dirty} 是普通非 volatile boolean。
 * BAS 的 {@link com.shinoyuki.betterautosave.core.snapshot.SavedDataSaveTask} 在 IO 失败时
 * 由 worker 线程调 {@code savedData.setDirty()} 写 dirty=true, 而主线程在
 * {@link DimensionDataStorageMixin} 周期遍历里裸读 {@code savedData.isDirty()}。两线程对同一
 * 非 volatile 字段无 happens-before, 主线程可能读到陈旧的 dirty=false, 把这个 IO 失败文件的
 * 重投推迟若干周期 (x86/HotSpot 上几乎即时可见, 仅弱内存序架构潜伏, 故 Minor)。
 *
 * <p><b>为何镜像而非直接把字段改 volatile</b>: dirty 在 vanilla 基类, mixin 不能改字段修饰符。
 * 用 {@code @Unique volatile} 镜像收口读写: {@code setDirty(boolean)} TAIL 同步写镜像 (no-arg
 * {@code setDirty()} 委托到此重载, 一并覆盖), {@code isDirty()} HEAD 直接返回镜像并 cancel。
 * 镜像与 vanilla 字段始终同写, 但读端只认 volatile 镜像, 跨线程可见性由 JMM volatile 语义保证。
 *
 * <p><b>初值一致性</b>: vanilla 字段初值 false, 镜像初值同为 false (Java boolean 默认), 二者
 * 一致, 无需构造钩子。任何 mod 子类的 dirty 都走基类 setDirty, 故镜像对所有 SavedData 子类生效。
 */
@Mixin(SavedData.class)
public abstract class SavedDataMixin {

    @Unique
    private volatile boolean betterautosave$dirtyMirror;

    @Inject(method = "setDirty(Z)V", at = @At("TAIL"))
    private void betterautosave$mirrorSetDirty(boolean dirty, CallbackInfo ci) {
        this.betterautosave$dirtyMirror = dirty;
    }

    @Inject(method = "isDirty()Z", at = @At("HEAD"), cancellable = true)
    private void betterautosave$readDirtyMirror(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.betterautosave$dirtyMirror);
    }
}
