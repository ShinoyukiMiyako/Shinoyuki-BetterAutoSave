package com.shinoyuki.betterautosave.core.state;

import com.shinoyuki.betterautosave.core.snapshot.ChunkSnapshot;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkSaveState {

    public enum Phase {
        CLEAN,
        DIRTY,
        SNAPSHOTTING,
        SERIALIZING,
        IO_PENDING,
        FAILED
    }

    public enum IoOutcome {
        CLEAN_LANDED,
        REQUEUE_DIRTY,
        FAILED_TERMINAL
    }

    private final long packedPos;
    private final String dimensionId;
    private final long enqueueSequence;

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.CLEAN);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicInteger retryCount = new AtomicInteger();
    private volatile long inFlightGeneration;
    private final AtomicBoolean mustDrain = new AtomicBoolean();
    // v0.10.2 修复 (M6): 终态转换 (CLEAN_LANDED / FAILED_TERMINAL) 内部 CAS 清 mustDrain 的结果,
    // 供 IO 完成回调线程在调完转换后读取以驱动 gauge dec. 仅被同一回调线程写后即读 (whenComplete
    // 是 per-task 单线程序列), 无跨线程可见性需求, 故普通字段即可.
    private boolean lastTransitionClearedMustDrain;
    // v0.11.0 修复 (C-dispatch-register-toctou 终局): 接力快照槽从裸 AtomicReference<ChunkSnapshot>
    // 升级为四态状态机 AtomicReference<PendingState>。
    //
    // 为何裸单槽不足 (第八轮 Critical): 第七轮把 registerPendingSnapshot 前置于 dispatchSaveEvent 以消灭
    // lost-wakeup, 但 dispatchSaveEvent 同步跑第三方 Forge listener 期间, 在飞那代 IO 可在并发 IOWorker
    // 线程落地、判 REQUEUE_DIRTY、把这份"刚登记但 listener 仍在原地改写"的可变 tag 取走 reoffer 给序列化
    // worker assemble —— 三线程无栅栏共享同一 CompoundTag 的 HashMap 底座, 静默数据损坏 (丢增量/半更新态/
    // ConcurrentModificationException/HashMap resize UB)。根因: 单槽只能编码"已登记 (回调可发现)"或"未登记",
    // 无法同时编码第三个正交维度"是否就绪 (可被消费)"。
    //
    // 状态机让回调能"发现"PREPARING 槽 (关 lost-wakeup) 却不能"消费"它 (关未就绪 tag 暴露): 消费权 (READY)
    // 的发布严格晚于 dispatchSaveEvent 返回; 若 dispatch 期间回调路过 (标 CONSUMER_MISSED), 补踢责任交还
    // 主线程 (合法 offer 方, 不引入 worker 阻塞)。AtomicReference 让主线程 (begin/publish) 与 IOWorker 回调
    // 线程 (markConsumerPassed/takeReady) 全程 CAS 安全交接。
    private final AtomicReference<PendingState> pendingState = new AtomicReference<>(PendingState.EMPTY);

    /**
     * 接力快照槽的四态状态机。不可变值对象, 全程经 {@code pendingState} 的 CAS 发布, 无字段可变性。
     *
     * <p>四态语义 (报告候选 3):
     * <ul>
     *   <li>EMPTY: 无 pending, 回调走原 REQUEUE_DIRTY 语义。</li>
     *   <li>PREPARING: 主线程已 capture 并登记 pending, 但 dispatchSaveEvent (第三方 listener 仍在原地改写
     *       这份 tag) 尚未返回 —— 回调能发现但**禁止消费** (消费即取走未就绪 tag)。</li>
     *   <li>READY: dispatch 已返回, tag 就绪, 回调可 CAS 取走 reoffer。</li>
     * </ul>
     *
     * <p>{@code consumerMissed} 标志 (精化一/精化二): "在槽处于不可消费态 (PREPARING, 或更早的 EMPTY) 期间,
     * 有 REQUEUE_DIRTY 回调路过但因不可消费而离开"。它把"补踢责任"从离开的回调转交给主线程: 主线程
     * publish (PREPARING->READY) 时若见此标志, 则自己把就绪的 pending reoffer 出去 —— 因为那个路过的回调
     * 是本代在飞 IO 的唯一消费者, 它走后不会再来, 不补踢则 READY 槽成无人消费的孤儿 (最新代不落盘 + mustDrain
     * 永挂)。EMPTY 态也携带此标志 (回调路过于 PREPARING 登记之前), 由随后的 beginPending 继承进 PREPARING,
     * 这正是报告所述"状态化 epoch"的载体: 标志随槽状态流转而不丢失, 替代裸 volatile 双检的 ABA 风险。
     */
    public static final class PendingState {

        private enum Kind {
            EMPTY,
            PREPARING,
            READY
        }

        // EMPTY 且无 consumerMissed 的规范单例 (槽的初始/清空态)。
        private static final PendingState EMPTY = new PendingState(Kind.EMPTY, null, false);
        // EMPTY 且 consumerMissed=true: 回调在 PREPARING 登记前路过留下的标记, 等下一次 beginPending 继承。
        private static final PendingState EMPTY_CONSUMER_PASSED = new PendingState(Kind.EMPTY, null, true);

        private final Kind kind;
        private final ChunkSnapshot snapshot;
        private final boolean consumerMissed;

        private PendingState(Kind kind, ChunkSnapshot snapshot, boolean consumerMissed) {
            this.kind = kind;
            this.snapshot = snapshot;
            this.consumerMissed = consumerMissed;
        }
    }

    public ChunkSaveState(long packedPos, String dimensionId, long enqueueSequence) {
        this.packedPos = packedPos;
        this.dimensionId = dimensionId;
        this.enqueueSequence = enqueueSequence;
    }

    public long packedPos() {
        return packedPos;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public long enqueueSequence() {
        return enqueueSequence;
    }

    public Phase phase() {
        return phase.get();
    }

    /**
     * 是否处于"某代 IO 已在管线在飞"的三态之一 (SNAPSHOTTING / SERIALIZING / IO_PENDING)。
     * v0.10.2 修复 (autosave 通道静默吞咽, gaps[1]): autosave 通道用此判定在途短路, 避免对在飞 chunk
     * 入队一个注定 trySnapshot 失败的 priority (静默 fallback + 虚高计数 + 关服 join 不知情)。
     */
    public boolean isInFlight() {
        Phase p = phase.get();
        return p == Phase.SNAPSHOTTING || p == Phase.SERIALIZING || p == Phase.IO_PENDING;
    }

    public long generation() {
        return generation.get();
    }

    public int retryCount() {
        return retryCount.get();
    }

    public long inFlightGeneration() {
        return inFlightGeneration;
    }

    public boolean mustDrain() {
        return mustDrain.get();
    }

    public void markDirty() {
        generation.incrementAndGet();
        phase.compareAndSet(Phase.CLEAN, Phase.DIRTY);
    }

    public boolean trySnapshot() {
        return phase.compareAndSet(Phase.DIRTY, Phase.SNAPSHOTTING);
    }

    public long enterSerializing() {
        long captured = generation.get();
        inFlightGeneration = captured;
        phase.set(Phase.SERIALIZING);
        // v0.11.0 修复 (C-stale-missed-no-epoch): 开新保存周期 (fresh capture, 非接力链) 时清掉上一周期
        // 残留的 EMPTY_CONSUMER_PASSED 单例, 否则该 stale consumerMissed 会被下一次 beginPendingSnapshot
        // 误继承进 PREPARING, 让 publishPendingSnapshot 提前自踢: 主线程在本代 IO 尚未落地时就并发 reoffer
        // 接力, 经共享 chunkWorkerQueue 被另一 worker assemble, 把"前代 store happens-before 后代 store"的
        // 写序保证降级成无序竞争 (多 worker 下旧代 last-write 覆盖最新代, 静默丢数据); 且提前覆盖
        // inFlightGeneration 让本代回调误判 CLEAN_LANDED 提前清 mustDrain。
        //
        // 必须用精确 CAS(EMPTY_CONSUMER_PASSED, EMPTY) 只清这一个单例, 不得无条件 set(EMPTY): 那会误清
        // 与本调用并发挂在槽上的 PREPARING/READY。enterSerializing 与碰撞登记 (beginPendingSnapshot) 虽都在
        // 主线程串行, 但接力链回调 (takeReadyPendingSnapshot) 在 off-main 线程, 仍以原子 CAS 为安全。
        // 接力链不走本方法 (它走 reenterSerializingForPending), 故 intra-cycle 的 PREPARING-missed 合并与
        // 嵌套碰撞语义不受影响 —— 本清理只切断"missed 跨保存周期存活"这一条路径。
        pendingState.compareAndSet(PendingState.EMPTY_CONSUMER_PASSED, PendingState.EMPTY);
        return captured;
    }

    public void enterIoPending() {
        phase.set(Phase.IO_PENDING);
    }

    public IoOutcome ioCompletedSuccessfully() {
        if (generation.get() == inFlightGeneration) {
            phase.set(Phase.CLEAN);
            retryCount.set(0);
            // v0.10.2 修复 (M6): 记录本次 CAS 是否真正清掉 mustDrain, 让调用方据此 dec gauge.
            // 终态 phase.set 在 CAS 之前完成, mixin 重入门只在 IO_PENDING 等在途 phase 才 inc;
            // 谁的 CAS 赢得 true->false 谁负责唯一一次 dec, 杜绝拆分读快照漏 dec.
            lastTransitionClearedMustDrain = mustDrain.compareAndSet(true, false);
            return IoOutcome.CLEAN_LANDED;
        }
        phase.set(Phase.DIRTY);
        lastTransitionClearedMustDrain = false;
        return IoOutcome.REQUEUE_DIRTY;
    }

    public IoOutcome ioFailed(int maxRetries) {
        int n = retryCount.incrementAndGet();
        if (n > maxRetries) {
            phase.set(Phase.FAILED);
            lastTransitionClearedMustDrain = mustDrain.compareAndSet(true, false);
            return IoOutcome.FAILED_TERMINAL;
        }
        phase.set(Phase.DIRTY);
        lastTransitionClearedMustDrain = false;
        return IoOutcome.REQUEUE_DIRTY;
    }

    /**
     * v0.10.2 修复 (M6): 返回上一次终态转换 (ioCompletedSuccessfully / ioFailed) 内部 CAS
     * 是否真正把 mustDrain 由 true 清成 false. IO 完成回调据此决定是否 dec mustDrainPending gauge,
     * 保证 boolean 清零与 gauge dec 是同一次 CAS 的原子结果. 仅由同一回调线程在调完转换后立即读取.
     */
    public boolean lastTransitionClearedMustDrain() {
        return lastTransitionClearedMustDrain;
    }

    public void resetAfterFallback() {
        retryCount.set(0);
        phase.set(Phase.CLEAN);
    }

    public void markMustDrain() {
        mustDrain.set(true);
    }

    public void clearMustDrain() {
        mustDrain.set(false);
    }

    /** CAS false -> true; 返回 true 表示首次置位, 调用方可 inc gauge 一次. */
    public boolean tryMarkMustDrain() {
        return mustDrain.compareAndSet(false, true);
    }

    /** CAS true -> false; 返回 true 表示首次清除, 调用方可 dec gauge 一次. */
    public boolean compareAndClearMustDrain() {
        return mustDrain.compareAndSet(true, false);
    }

    /**
     * v0.11.0 修复 (C-dispatch-register-toctou 终局): 把接力快照登记进 PREPARING 态 (主线程 dispatchSaveEvent
     * **之前**调)。回调此刻能发现槽非空 (关 lost-wakeup) 却因 PREPARING 不可消费而不会取走未就绪 tag。
     *
     * <p><b>覆盖语义 (隐角 B 最新者胜 + 精化二)</b>: 槽已被占用时 (PREPARING/READY/EMPTY-missed, 即 PREPARING
     * 期间嵌套的第二次主线程碰撞) 直接 CAS 覆盖为更新代的新 PREPARING, 旧 pending 作废 (它是更新 pending 的
     * 真子集)。但**必须合并 consumerMissed 标志不丢失**: 若旧态已被回调标过 missed (回调走后等补踢), 或更早
     * EMPTY 态携带 missed (回调在首次登记前路过), 该标志并入新 PREPARING —— 否则覆盖会抹掉"回调已路过"事实,
     * publish 时不补踢, 接力链断。
     *
     * <p>登记方 (mixin 碰撞分支) 必须已确保 mustDrain 置位 (gauge 不变式: 槽非空 -> mustDrain 恒真)。
     * CAS 循环防与回调线程的 markConsumerPassedIfPending 竞争丢更新。
     */
    public void beginPendingSnapshot(ChunkSnapshot snapshot) {
        PendingState next;
        PendingState current;
        do {
            current = pendingState.get();
            // 继承既有 missed 标志 (精化一: 来自 EMPTY-missed; 精化二: 来自被覆盖的 PREPARING-missed)。
            next = new PendingState(PendingState.Kind.PREPARING, snapshot, current.consumerMissed);
        } while (!pendingState.compareAndSet(current, next));
    }

    /**
     * v0.11.0 修复 (C-dispatch-register-toctou 终局): dispatchSaveEvent 成功返回后发布消费权 (PREPARING->READY)。
     * tag 此刻已就绪 (listener 改写已完成), 回调可安全消费。
     *
     * <p><b>返回值 = 主线程需自踢的 pending</b> (报告候选 3 的补踢路径): 若 publish 时槽携带 consumerMissed
     * (dispatch 期间有回调路过 PREPARING 但因不可消费而离开, 或更早 EMPTY 路过被继承), 则那个路过的回调是本代
     * 在飞 IO 的唯一消费者, 走后不再来。主线程必须接过补踢责任: 本方法把 pending 取走、槽归 EMPTY、返回该
     * pending 让主线程自己 reoffer。无 missed 则正常 PREPARING->READY (返 null, 等回调消费)。
     *
     * <p>仅主线程在 dispatch 成功后调; CAS 循环防与回调的 markConsumerPassedIfPending / 嵌套 beginPending 竞争。
     *
     * @return 非 null 表示主线程需自行 reoffer 这份 pending (回调已路过, 不会再来取); null 表示已发布 READY 等回调消费
     */
    public ChunkSnapshot publishPendingSnapshot() {
        PendingState current;
        PendingState next;
        ChunkSnapshot toReoffer;
        do {
            current = pendingState.get();
            if (current.kind != PendingState.Kind.PREPARING) {
                // publish 只在 PREPARING 上有效。非 PREPARING (理论上不可达: 只有本主线程能把 PREPARING 推走)
                // 直接返回不触碰, 防误改并发态。
                return null;
            }
            if (current.consumerMissed) {
                // 回调已路过, 补踢责任归主线程: 取走 pending, 槽归 EMPTY, 主线程自踢。
                next = PendingState.EMPTY;
                toReoffer = current.snapshot;
            } else {
                // 无回调路过: 正常发布消费权, 等在飞 IO 回调取走。
                next = new PendingState(PendingState.Kind.READY, current.snapshot, false);
                toReoffer = null;
            }
        } while (!pendingState.compareAndSet(current, next));
        return toReoffer;
    }

    /**
     * v0.11.0 修复 (C-dispatch-register-toctou 终局): dispatchSaveEvent 抛 (第三方 listener 故障) 时主线程的
     * 自我撤销。登记先于派发完成, 故抛时槽仍是 PREPARING (回调在 PREPARING 期间从不消费, 只标 missed)。取走
     * pending、槽归 EMPTY, 返回它供 mixin catch 据非空判定"已撤销, 需亲自配平 mustDrain"。
     *
     * <p>与旧 takePendingSnapshot 的 getAndSet 去重语义同构, 但用状态机表达: 撤销永远取得回未就绪 pending
     * (回调无法抢先消费 PREPARING), 故返回恒非 null —— 比裸槽的 getAndSet 竞态更确定。
     *
     * @return 撤销取回的 pending (恒非 null 当槽确为 PREPARING); 槽非 PREPARING 时返 null (理论不可达, 防御性)
     */
    public ChunkSnapshot abortPendingSnapshot() {
        PendingState current;
        do {
            current = pendingState.get();
            if (current.kind != PendingState.Kind.PREPARING) {
                return null;
            }
        } while (!pendingState.compareAndSet(current, PendingState.EMPTY));
        return current.snapshot;
    }

    /**
     * v0.11.0 修复 (C-dispatch-register-toctou 终局): 在飞那代 IO 落地判 REQUEUE_DIRTY 时由回调 (IOWorker 完成
     * 回调线程) 调, 取出**已就绪**的接力快照重投。
     *
     * <p>三态分流 (报告候选 3 回调序):
     * <ul>
     *   <li>READY: CAS READY->EMPTY 取走返回, 回调 reoffer 它。</li>
     *   <li>PREPARING: tag 未就绪 (dispatch 仍在跑), **禁止消费**。CAS 标 consumerMissed=true 后返 null 离开 ——
     *       把补踢责任交还主线程 (主线程 publish 时见 missed 自踢)。这正是关"未就绪 tag 暴露"门的核心动作。</li>
     *   <li>EMPTY: 无 pending。仍 CAS 留下 consumerMissed 标记 (EMPTY-missed, 精化一): 主线程随后 beginPending
     *       会继承它, 让 publish 时补踢 —— 覆盖"回调路过于登记之前"的交错 (报告穷举 case 1)。</li>
     * </ul>
     *
     * <p>CAS 循环防与主线程 beginPending/publish/abort 竞争。
     *
     * @return 非 null (仅 READY 时) 则回调重投它接力落盘; null 则回调走原 REQUEUE_DIRTY 语义不重投
     */
    public ChunkSnapshot takeReadyPendingSnapshot() {
        PendingState current;
        PendingState next;
        ChunkSnapshot taken;
        do {
            current = pendingState.get();
            switch (current.kind) {
                case READY -> {
                    next = PendingState.EMPTY;
                    taken = current.snapshot;
                }
                case PREPARING -> {
                    // 已 missed 则无需再 CAS (幂等), 直接离开。
                    if (current.consumerMissed) {
                        return null;
                    }
                    next = new PendingState(PendingState.Kind.PREPARING, current.snapshot, true);
                    taken = null;
                }
                case EMPTY -> {
                    if (current.consumerMissed) {
                        return null;
                    }
                    next = PendingState.EMPTY_CONSUMER_PASSED;
                    taken = null;
                }
                default -> throw new IllegalStateException("unreachable pending kind: " + current.kind);
            }
        } while (!pendingState.compareAndSet(current, next));
        return taken;
    }

    /**
     * v0.11.0: 测试/降级便捷入口 —— 原子地把 pending 直接发布为 READY (跳过 PREPARING)。语义等价于"无 dispatch
     * 窗口的接力登记": entity 路径 (无 Forge 事件) 与单测的消费侧用例正是这种退化形态。覆盖语义最新者胜,
     * 不携带 consumerMissed (READY 即可消费, 无需补踢)。
     *
     * <p><b>注意</b>: chunk 主线程碰撞分支**不**走此入口 —— 它有 dispatchSaveEvent 窗口, 必须 begin (PREPARING)
     * -> dispatch -> publish (READY), 否则回归第八轮"未就绪 tag 暴露"。本入口仅供无窗口场景。
     */
    public void registerReadyPendingSnapshot(ChunkSnapshot snapshot) {
        pendingState.set(new PendingState(PendingState.Kind.READY, snapshot, false));
    }

    /**
     * v0.11.0 修复 (C-unhandled-drains-preparing): 终态死亡路径 (FAILED_TERMINAL / onUnhandledError) 取出接力
     * 槽的一次原子处置, 返回三态判别 + 取走的 pending, 关死"绕过 READY 门"的旁路出口。
     *
     * <p><b>为何不再用 drainPendingSnapshot 全清</b>: 旧 drain 无条件 getAndSet(EMPTY) 把任意态 (含 PREPARING)
     * 夺走接力。但 PREPARING 的 tag 此刻仍被主线程 dispatchSaveEvent 内的第三方 listener 原地改写 (未就绪):
     * 在飞 task 在 off-main worker 线程把它夺走 reoffer 给另一序列化 worker assemble = 跨线程读写同一
     * CompoundTag 的 HashMap, 正是状态机立项要消灭的"未就绪 tag 暴露", 却在 drain 出口复活。本方法改为只消费
     * READY, PREPARING 标 missed 交还主线程 (与 takeReadyPendingSnapshot 同语义), 主线程 dispatch 返回后
     * publishPendingSnapshot 见 missed 自踢接力。
     *
     * <p><b>三态处置 (与 takeReadyPendingSnapshot 的差异)</b>:
     * <ul>
     *   <li>READY: CAS READY->EMPTY 取走, 判 {@code CONSUMED} —— 在飞 task 当其消费者直接重投 (合法就绪 tag, 无 race)。</li>
     *   <li>PREPARING (无论是否已 missed): CAS 标 consumerMissed=true (幂等), 判 {@code HANDED_TO_MAIN}, 不取走 ——
     *       补踢责任交还主线程 publish 自踢。调用方必须 early-return: **不**清 mustDrain、**不**走 ioFailed/enqueueRecovery
     *       安全网, 否则与主线程自踢接力链双重处置同一 state (mustDrain 由自踢接力链终态清)。</li>
     *   <li>EMPTY (含 EMPTY_CONSUMER_PASSED): 无就绪接力。判 {@code EMPTY_DEAD}, 不改槽 —— 与
     *       takeReadyPendingSnapshot 的 EMPTY 分支不同, 本路径**不**标 missed (在飞 task 正在死, 不存在"本代回调
     *       稍后路过"要交接的对象; 标了反而留 stale EMPTY_CONSUMER_PASSED)。调用方走原有安全网 (清 mustDrain +
     *       ioFailed + enqueueRecovery)。</li>
     * </ul>
     *
     * <p>判别与取走是同一次 CAS 的原子结果, 杜绝"take 返 null 后再 hasPendingSnapshot() 二次读"被主线程
     * publish (PREPARING->EMPTY) 抢跑的 TOCTOU。
     */
    public ReadyTake takeReadyForTerminalConsumer() {
        PendingState current;
        PendingState next;
        ReadyTake.Disposition disposition;
        ChunkSnapshot taken;
        do {
            current = pendingState.get();
            switch (current.kind) {
                case READY -> {
                    next = PendingState.EMPTY;
                    disposition = ReadyTake.Disposition.CONSUMED;
                    taken = current.snapshot;
                }
                case PREPARING -> {
                    disposition = ReadyTake.Disposition.HANDED_TO_MAIN;
                    taken = null;
                    if (current.consumerMissed) {
                        // 已 missed 则无需再 CAS (幂等), 直接返回判别。
                        return ReadyTake.handedToMain();
                    }
                    next = new PendingState(PendingState.Kind.PREPARING, current.snapshot, true);
                }
                case EMPTY -> {
                    // 不改槽 (含 EMPTY_CONSUMER_PASSED), 直接返回 —— 终态死亡不标 missed。
                    return ReadyTake.emptyDead();
                }
                default -> throw new IllegalStateException("unreachable pending kind: " + current.kind);
            }
        } while (!pendingState.compareAndSet(current, next));
        return new ReadyTake(disposition, taken);
    }

    /**
     * {@link #takeReadyForTerminalConsumer} 的原子三态结果: 判别 + 取走的 pending。
     */
    public static final class ReadyTake {

        public enum Disposition {
            /** 取到就绪 READY pending, 在飞 task 直接重投它。 */
            CONSUMED,
            /** 槽为 PREPARING 已标 missed, 补踢交还主线程 publish 自踢; 调用方 early-return 不碰 mustDrain。 */
            HANDED_TO_MAIN,
            /** 槽 EMPTY 无就绪接力; 调用方走安全网 (清 mustDrain + ioFailed + enqueueRecovery)。 */
            EMPTY_DEAD
        }

        private static final ReadyTake HANDED_TO_MAIN = new ReadyTake(Disposition.HANDED_TO_MAIN, null);
        private static final ReadyTake EMPTY_DEAD = new ReadyTake(Disposition.EMPTY_DEAD, null);

        private final Disposition disposition;
        private final ChunkSnapshot snapshot;

        private ReadyTake(Disposition disposition, ChunkSnapshot snapshot) {
            this.disposition = disposition;
            this.snapshot = snapshot;
        }

        private static ReadyTake handedToMain() {
            return HANDED_TO_MAIN;
        }

        private static ReadyTake emptyDead() {
            return EMPTY_DEAD;
        }

        public Disposition disposition() {
            return disposition;
        }

        /** 仅 CONSUMED 时非 null (取走的就绪 READY pending)。 */
        public ChunkSnapshot snapshot() {
            return snapshot;
        }
    }

    /**
     * v0.11.0 修复 (C-dispatch-register-toctou 终局): 无条件清空槽 (任意态 -> EMPTY), 返回曾停泊的 pending
     * (PREPARING 或 READY 的 snapshot, 否则 null)。供**关服终局** (joinWorkers 后 workersStopping 残窗,
     * 主线程已不可能再 publish) 全清防泄漏。
     *
     * <p><b>用途已收窄</b>: 原本的两个终态死亡调用点 (onUnhandledError / onIoFailure FAILED_TERMINAL) 已改用
     * {@link #takeReadyForTerminalConsumer} (只消费 READY, PREPARING 交还主线程), 因为那两处在飞 task 死亡时
     * 主线程可能仍在 dispatch 改写 PREPARING tag, 全清会夺走未就绪 tag 接力 = 数据竞争。本方法只保留给关服终局:
     * worker 已停、主线程已过 ChunkMap.save 拦截窗口不再 publish, 此时全清 (含理论上残留的 PREPARING) 才安全。
     *
     * @return 曾停泊的 pending (PREPARING/READY); 槽本就 EMPTY 时返 null
     */
    public ChunkSnapshot drainPendingSnapshot() {
        PendingState prev = pendingState.getAndSet(PendingState.EMPTY);
        return prev.snapshot;
    }

    /** 槽是否非空 (PREPARING 或 READY)。供 gauge 不变式断言 (槽非空 -> mustDrain 恒真) 与诊断使用。 */
    public boolean hasPendingSnapshot() {
        return pendingState.get().kind != PendingState.Kind.EMPTY;
    }

    /**
     * v0.10.2 修复 (C-chunk-unload-collision, 隐角 A): 重投接力快照前把 inFlightGeneration 锁到该
     * pending 快照 capture 时的代, phase 推回 SERIALIZING。
     *
     * <p><b>为何不复用 enterSerializing</b>: enterSerializing 读 {@code generation.get()} 锁当前代,
     * 但接力快照的代在 (更早的) 纯 capture 时刻就已固定 (存于 snapshot.capturedGeneration)。重投时
     * 若按当前 generation 锁, 期间又有新编辑会错配 —— 必须锁 pending 自己的代, 才能让该接力 IO 落地时
     * generation==inFlightGeneration 正确判 CLEAN_LANDED (无更新编辑) 或 REQUEUE_DIRTY (又有更新 pending)。
     * 这正是 "inFlightGeneration 随 pending 链推进, pending dispatch 时锁其代" 的落地点。
     */
    public void reenterSerializingForPending(long capturedGeneration) {
        inFlightGeneration = capturedGeneration;
        phase.set(Phase.SERIALIZING);
    }
}
