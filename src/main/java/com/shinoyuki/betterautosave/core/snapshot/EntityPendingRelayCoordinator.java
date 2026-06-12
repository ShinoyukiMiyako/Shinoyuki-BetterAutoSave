package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.state.EntitySaveState;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;

/**
 * entity 在飞碰撞分支 "登记接力槽 -> 重读 phase -> 自取 -> 自踢" 协调逻辑的收口点。从
 * {@link com.shinoyuki.betterautosave.mixin.EntityStorageMixin} 抽出, 让该段写后读对偶 (Dekker 式双向检查)
 * 可脱离 mixin 单测 (mixin 自身无法实例化)。mixin 退化为一行委托, vanilla 副作用 (emptyChunks.remove /
 * ci.cancel) 仍留在 mixin 原位。
 *
 * <p><b>写后读对偶</b>: 接力槽与终态回调跑在两条线程上, 需一道顺序纪律杜绝 "回调终态取槽" 与 "主线程登记"
 * 互相看不见 ——
 * <ul>
 *   <li>主线程侧 (本协调): 先写 pending 槽 (registerPendingSnapshot), 再重读 phase。</li>
 *   <li>回调侧: 先写 phase 终态 (ioCompletedSuccessfully/ioFailed 内 phase 写), 再 getAndSet 取槽。</li>
 * </ul>
 * 两侧各自先写后读, 故任一交错至少一方看见对方: 若回调取槽早于主线程写槽, 主线程重读 phase 必见回调写定的
 * 终态 (非在飞态) -> 主线程取回自己刚放的 pending 自踢; 若主线程写槽早于回调取槽, 回调 getAndSet 必见这份
 * pending -> 回调接力。双方都看见时以 getAndSet 析构语义裁定唯一消费者防双投。
 *
 * <p>{@link EntitySaveState#isInFlight()} 的三态判定 (SNAPSHOTTING/SERIALIZING/IO_PENDING) 即 "存在在飞消费者"
 * 的严格充要: in-place IO 重投全程保持 IO_PENDING 不发布瞬态 DIRTY, 故重读得非在飞态 (CLEAN/DIRTY/FAILED)
 * 时回调确已终态退出, 不会再来消费这份 pending。
 */
public final class EntityPendingRelayCoordinator {

    private EntityPendingRelayCoordinator() {
    }

    /**
     * 主线程登记接力槽后的自踢动作 seam。给定主线程自取回的 pending (回调未消费), 由调用方把它锁代重投。
     * 生产环境绑到 {@code pipeline.reofferEntityPendingFromMainThread(worker, stateOwner, state, taken)};
     * 单测注入记录性 fake 同步执行接力。
     */
    @FunctionalInterface
    public interface SelfKick {
        void kick(EntitySaveState state, EntitySnapshot taken);
    }

    /**
     * 登记 pending 接力槽并按写后读对偶决定是否主线程自踢。
     *
     * @return true 表示主线程自取回 pending 并触发了 selfKick (回调已终态退出, 无在飞消费者);
     *         false 表示回调仍是在飞消费者 (重读得在飞态) 或已抢先取走 pending (getAndSet 返 null), 主线程不动作
     */
    public static boolean registerAndSelfKick(EntitySaveState state, EntitySnapshot pending,
                                              SaveMetrics metrics, SelfKick selfKick) {
        // 写后读对偶 "写" 半: 先写槽。
        state.registerPendingSnapshot(pending);
        // "读" 半: 重读 phase。仍在在飞态说明回调尚未终态退出, 它是这份 pending 的消费者, 主线程不自取。
        if (state.isInFlight()) {
            return false;
        }
        // 非在飞态: 回调已写定终态并可能已取槽。getAndSet 自取回自己刚放的 pending (防与回调取槽双投):
        // 取回 null -> 回调已接管, 主线程不再动作; 取回非空 -> 回调已终态退出不会再消费, 主线程自踢。
        EntitySnapshot taken = state.takePendingSnapshot();
        if (taken == null) {
            return false;
        }
        // 回调终态若已清 mustDrain (CLEAN_LANDED/FAILED_TERMINAL), 接力在途须恢复并补 inc gauge (关服 join 继续等);
        // 若 mustDrain 仍真 (REQUEUE_DIRTY 不清), tryMark 返 false 不重复 inc。接力链终态唯一清+dec。
        if (state.tryMarkMustDrain()) {
            metrics.incMustDrainPending();
        }
        selfKick.kick(state, taken);
        return true;
    }
}
