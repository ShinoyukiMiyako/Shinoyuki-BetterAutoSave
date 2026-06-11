package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.diagnostic.DiagnosticLogger;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Shadow
    @Final
    private static int AUTOSAVE_INTERVAL;

    @Shadow
    private int tickCount;

    @Shadow
    public abstract float getAverageTickTime();

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void betterautosave$onTickServer(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }

        // Critical 修复 2 (M2): 恢复队列 drain 必须与 degraded 闸门解耦, 先于早返执行.
        // 降级后存活的 chunk worker 与 vanilla IOWorker 回调线程上的在途 task 仍会执行, 其 IO 失败
        // 仍 enqueueRecovery 投进 ChunkRecoveryQueue。若 drain 跟随 degraded 早返一起停摆, 这些失败
        // chunk 的 vanilla isUnsaved 永远停在 false, vanilla autosave/unload 全部跳过, 整个降级会话
        // (可能数小时) 不落盘; 而降级常由 OOM 等灾难触发, 崩溃概率显著升高 -- 一旦降级期进程被 kill,
        // onServerStopping 的一次性 drain 不执行, 增量永久静默丢失。失败恢复恰恰在降级时最需要, 故
        // drain 不受 degraded 影响。内部对空队列 / server==null 已零开销早返, 降级下调用安全。
        BetterAutoSaveCore.pipeline().drainChunkRecoveryQueue();

        // 调度 dispatch 与诊断日志仍受 degraded 闸门保护: 降级后所有新 save 走 vanilla 同步,
        // BAS 不再主动 dispatch chunk。
        if (BetterAutoSaveCore.pipeline().isDegraded()) {
            return;
        }
        int ticksIntoCycle = tickCount % AUTOSAVE_INTERVAL;
        int remainingTicks = AUTOSAVE_INTERVAL - ticksIntoCycle;
        int remainingSeconds = remainingTicks / 20;

        BetterAutoSaveCore.scheduler().onServerTick(getAverageTickTime(), remainingSeconds);
        DiagnosticLogger diag = BetterAutoSaveCore.diagnosticLogger();
        if (diag != null) {
            diag.onServerTick();
        }
    }
}
