package com.shinoyuki.betterautosave.core.state;

import com.shinoyuki.betterautosave.core.snapshot.ChunkSnapshot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * chunk 异步保存协议的单字状态机 (v0.11.0 终局 REDESIGN, 第十轮)。
 *
 * <h2>为何是单字</h2>
 * 第六到第十轮共六次被攻破的根因是同一个: 生产者三步 (mark / begin / publish) 与消费者两步
 * (land / take) 跑在两条线程上, 而 {@code phase} / {@code mustDrain} / {@code pendingState} /
 * {@code inFlightGeneration} 是四个独立的原子单元 —— 没有任何一次 CAS 能把 "我在哪一周期 / 槽里是哪一代 /
 * 谁负责清 mustDrain" 绑成单个线性化点。每修一格交错就把竞速推到相邻一格 (A1->A2->A3->A4->A5->A6 是
 * 一条单调推进的链)。本类把这些散落字段收口进一个不可变值对象 {@link SlotWord}, 经单个
 * {@code AtomicReference<SlotWord>} 的 CAS 发布, 让所有转移在同一个字上线性化。
 *
 * <h2>状态字字段语义</h2>
 * <ul>
 *   <li>{@code phase}: 内容相 (CLEAN/DIRTY/SNAPSHOTTING/SERIALIZING/IO_PENDING/FAILED), 沿用旧 {@link Phase}。</li>
 *   <li>{@code inFlightGeneration}: 当前在飞 IO 锁定的 <b>内容代</b> (capture 时刻的 generation 快照)。
 *       决定 IO 落地判 CLEAN_LANDED (无更新编辑) 还是 REQUEUE_DIRTY (又有更新)。</li>
 *   <li>{@code inFlightCycleSeq}: 当前在飞 IO 的 <b>周期身份</b> —— 一个全局单调递增序号, 每次开/重开一个
 *       在飞周期 (enterSerializing / reenterSerializingForPending) 自增一次。与内容代解耦 (A7): 内容代会
 *       因接力 reenter 回退到 pending 自己的旧代, 但周期序号永远向前, 杜绝 "两个不同周期偶然复用同一代数值"
 *       让 {@code missedCycle} 跨周期 stale 被误判同周期。</li>
 *   <li>{@code pendingKind}: NONE / PREPARING / READY。把 "已登记 (回调可发现)" 与 "可消费 (回调可取走)"
 *       拆成两相 (A2): 回调只在 READY 相消费, PREPARING 相只标 missed 离开。</li>
 *   <li>{@code pendingSnapshot}: 接力快照引用 (仅 PREPARING/READY 非空)。</li>
 *   <li>{@code missedCycle}: -1 表示无 missed; 否则 = 回调路过时它所属的在飞周期 {@code inFlightCycleSeq}
 *       (A4/A5 核心)。回调 (REQUEUE_DIRTY 路过 PREPARING 或 EMPTY) 是本周期在飞 IO 的唯一消费者; 它路过即标
 *       本周期序号, 主线程 publish 见同周期 missed 自踢补救。携带周期序号而非内容代, 让 stale missed (旧周期)
 *       在新周期 begin 时被 {@code missedCycle != inFlightCycleSeq} 识别丢弃, 取代第九轮的时序性单 CAS 清理
 *       (那个清理挡不住 A5 那种 "清理之后才写入" 的 stale)。</li>
 *   <li>{@code drainOwner}: NONE / IN_FLIGHT / RELAY / TERMINAL_HANDED —— 显式编码 "谁负责把 mustDrain 带向
 *       终态", 取代旧的裸 {@code AtomicBoolean mustDrain} + 散落的 {@code lastTransitionClearedMustDrain}。
 *       不变式 "pendingKind != NONE => drainOwner != NONE" 由 CAS 强制 (A6 的破坏点 "槽非空但 mustDrain=false"
 *       从此不可达)。gauge inc/dec 绑定 drainOwner 的 NONE<->非NONE 翻转, 谁赢 CAS 谁唯一计数。</li>
 * </ul>
 * {@code generation} 与 {@code retryCount} 保持独立原子量: 前者由编辑线程 {@code markDirty} 高频递增不宜进
 * CAS 字; 后者是 per-task 重试计数而非协议状态。
 *
 * <h2>转移原语表</h2>
 * <pre>
 * 线程     原语                              语义
 * ------   -------------------------------   --------------------------------------------------------------
 * 主线程   markDirty                         generation++; phase CLEAN->DIRTY (内容代驱动, 不进 CAS 字外的 phase)
 * 主线程   trySnapshot                       phase DIRTY->SNAPSHOTTING (CAS, 单次接管)
 * 主线程   enterSerializing                  开新周期: 锁 inFlightGeneration=generation, 新 inFlightCycleSeq, phase=SERIALIZING
 * 主线程   enterIoPending                    phase=IO_PENDING
 * 主线程   tryMarkMustDrain / markMustDrain  drainOwner NONE->IN_FLIGHT (gauge 不变式: 槽非空/在飞 -> drainOwner!=NONE)
 * 主线程   beginPendingSnapshot              碰撞登记 PREPARING + pendingEpoch; 仅继承同周期 missedCycle (A4/A5 epoch 校验)
 * 主线程   publishPendingSnapshot            PREPARING->READY; 同周期 missed 或 TERMINAL_HANDED 则返 snapshot 主线程自踢 (A6)
 * 主线程   abortPendingSnapshot              dispatch 抛: PREPARING->EMPTY 撤销, 恒取回非 null
 * 主线程   reenterSerializingForPending      接力前锁 inFlightGeneration=pending 代 + 新 inFlightCycleSeq, phase=SERIALIZING
 * 回调     landAndTake                       land+take 单 CAS (A5 根治): land 判 CLEAN/REQUEUE + 同一 CAS 取 READY 接力 / 标本周期 missedCycle
 * 回调     ioCompletedSuccessfully           land only (不并 take, 供 entity-parity 单测/降级直驱): CLEAN_LANDED(清 drainOwner) / REQUEUE_DIRTY
 * 回调     ioFailed                          land: 超 maxRetries ? FAILED_TERMINAL(清 drainOwner) : REQUEUE_DIRTY
 * 回调     takeReadyPendingSnapshot          REQUEUE_DIRTY 接力 (拆分入口): READY 取走 / PREPARING|EMPTY 标本周期 missedCycle 离开
 * 回调     takeReadyForTerminalConsumer      终态死亡: READY 消费 / PREPARING 标 missedCycle+拨 TERMINAL_HANDED 交还 / EMPTY 仅标 missedCycle (A3/A6)
 * 关服     drainSlot                         关服终局任意态全清 -> NONE + drainOwner 配平 (主线程已不再 publish 才安全)
 * </pre>
 *
 * <h2>攻击目录防御 (A1-A7)</h2>
 * <ul>
 *   <li>A1 丢唤醒 (lost-wakeup): beginPendingSnapshot 仍先于 dispatch (主线程序), 回调 take 时槽必为 PREPARING
 *       可发现, 不取 null 离开 -> 标 missedCycle 交还。</li>
 *   <li>A2 未就绪 tag 暴露: take 只在 READY 相消费; PREPARING 相 (dispatch 未返回, listener 仍原地改写 tag)
 *       永不取走。单字 CAS 保证 pendingKind 与 pendingSnapshot 原子一致, 无半发布。</li>
 *   <li>A3 drain 旁路: 终态消费者走 takeReadyForTerminalConsumer, 同样 READY-only; PREPARING 交还主线程。
 *       drainSlot 仅关服终局保留 (主线程已不再 publish)。</li>
 *   <li>A4 跨周期 stale missed: missedCycle 携带周期序号, beginPendingSnapshot 校验 missedCycle==inFlightCycleSeq
 *       才继承, stale (旧周期) 自动丢弃。不依赖时序清理。</li>
 *   <li>A5 DIRTY 发布竞速: 双防线 —— (1) {@link #landAndTake} 把 land(置 DIRTY) 与 take(标 missedCycle) 合并单
 *       CAS, 消除 "land 已见但 take 未发生" 的窗口本体, 故 missed 必带 land 周期序号 (主线程开新周期是在 missed
 *       写定之后); (2) 即便残留任何 stale missed, 它带旧周期序号, 新周期 begin/publish 校验不等丢弃, 不提前自踢、
 *       inFlightGeneration 不被误覆盖、在飞回调不误判 CLEAN_LANDED。</li>
 *   <li>A6 begin 前终态: takeReadyForTerminalConsumer 的 EMPTY 分支对称标 missedCycle (本周期序号), 主线程 begin
 *       同周期继承之并发现 drainOwner 被 ioFailed 清空而重新获取 IN_FLIGHT (返 true 让调用方补 inc gauge),
 *       publish 见同周期 missed 自踢不发 READY 孤儿; PREPARING 分支额外拨 drainOwner=TERMINAL_HANDED (第二个
 *       publish 自踢触发点)。drainOwner 不变式保证 mustDrain 不会在槽即将非空时被清成 NONE。</li>
 *   <li>A7 周期序号复用 (本设计自找的新边缘): inFlightCycleSeq 用全局单调递增 {@link AtomicLong}, 每次 begin/reenter
 *       都 incrementAndGet, 与内容代彻底解耦 —— 内容代会因接力 reenter 复用 pending 旧代, 周期序号永不复用,
 *       故 "两个不同周期偶然同号" 不可能, missedCycle 的同周期判据单调可靠。</li>
 * </ul>
 */
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

    public enum PendingKind {
        NONE,
        PREPARING,
        READY
    }

    /**
     * 谁持有 "把 mustDrain 带向终态" 的责任。取代旧裸 {@code AtomicBoolean mustDrain}: 用一个枚举位区分
     * 责任归属, 让 A6 那种 "槽非空但 mustDrain 已被清成 false" 的不变式破坏由 CAS 结构性杜绝。
     */
    public enum DrainOwner {
        /** 无在飞 / 无 pending, 关服 join 无需为本 state 等待。 */
        NONE,
        /** 普通在飞 IO 周期 (或 autosave 在途短路标记) 持有 drain, 由其终态回调清。 */
        IN_FLIGHT,
        /** 接力链在途持有 drain, 由接力 task 的终态清 (落最新代后)。 */
        RELAY,
        /** 终态消费者路过 PREPARING 把 drain 交还主线程, 由主线程自踢的接力链终态清 (A6/A3)。EMPTY 窗口不拨此值
         *  (那里 ioFailed 已清 drainOwner, 由随后的 begin 重新获取 IN_FLIGHT, 见 takeReadyForTerminalConsumer)。 */
        TERMINAL_HANDED
    }

    /** missedCycle 的 "无 missed" 哨兵。-1 不会与任何真实 inFlightCycleSeq (>=1) 冲突。 */
    private static final long NO_MISSED = -1L;

    private final long packedPos;
    private final String dimensionId;
    private final long enqueueSequence;

    // 内容代: 编辑线程高频递增, 不进 CAS 字 (与 A7 一致 —— 它表 "内容代" 而非 "在飞周期身份")。
    private final AtomicLong generation = new AtomicLong();
    // per-task 重试计数, 非协议状态, 保持独立 (接力链复用同一 state 的它, 实现级联收敛, 见 ChunkSaveTask)。
    private final AtomicInteger retryCount = new AtomicInteger();
    // 全局单调递增的 "在飞周期身份" 分配器 (A7): 每次 enterSerializing / reenterSerializingForPending 取一个新号,
    // 写进状态字的 inFlightCycleSeq, 让 missedCycle 的同周期判据永不因数值复用而误判。
    private final AtomicLong cycleSeqAllocator = new AtomicLong();

    // 单一线性化点: 所有协议转移经此 CAS 发布。
    private final AtomicReference<SlotWord> word = new AtomicReference<>(SlotWord.initial());

    /**
     * 不可变状态字。所有转移原语先读当前字, 构造新字, CAS 发布; 字段在构造后绝不可变。
     */
    public static final class SlotWord {

        private final Phase phase;
        private final long inFlightGeneration;
        private final long inFlightCycleSeq;
        private final PendingKind pendingKind;
        private final ChunkSnapshot pendingSnapshot;
        private final long pendingEpoch;
        private final long missedCycle;
        private final DrainOwner drainOwner;

        private SlotWord(Phase phase, long inFlightGeneration, long inFlightCycleSeq, PendingKind pendingKind,
                         ChunkSnapshot pendingSnapshot, long pendingEpoch, long missedCycle, DrainOwner drainOwner) {
            this.phase = phase;
            this.inFlightGeneration = inFlightGeneration;
            this.inFlightCycleSeq = inFlightCycleSeq;
            this.pendingKind = pendingKind;
            this.pendingSnapshot = pendingSnapshot;
            this.pendingEpoch = pendingEpoch;
            this.missedCycle = missedCycle;
            this.drainOwner = drainOwner;
        }

        private static SlotWord initial() {
            return new SlotWord(Phase.CLEAN, 0L, 0L, PendingKind.NONE, null, 0L, NO_MISSED, DrainOwner.NONE);
        }

        private SlotWord withPhase(Phase next) {
            return new SlotWord(next, inFlightGeneration, inFlightCycleSeq, pendingKind, pendingSnapshot,
                    pendingEpoch, missedCycle, drainOwner);
        }

        private SlotWord withInFlight(long generation, long cycleSeq, Phase next) {
            return new SlotWord(next, generation, cycleSeq, pendingKind, pendingSnapshot,
                    pendingEpoch, missedCycle, drainOwner);
        }

        private SlotWord withDrainOwner(DrainOwner next) {
            return new SlotWord(phase, inFlightGeneration, inFlightCycleSeq, pendingKind, pendingSnapshot,
                    pendingEpoch, missedCycle, next);
        }

        private SlotWord withPending(PendingKind kind, ChunkSnapshot snapshot, long epoch, long missed,
                                     DrainOwner owner) {
            return new SlotWord(phase, inFlightGeneration, inFlightCycleSeq, kind, snapshot, epoch, missed, owner);
        }

        public Phase phase() {
            return phase;
        }

        public PendingKind pendingKind() {
            return pendingKind;
        }

        public DrainOwner drainOwner() {
            return drainOwner;
        }

        public long inFlightGeneration() {
            return inFlightGeneration;
        }

        public long inFlightCycleSeq() {
            return inFlightCycleSeq;
        }

        public long missedCycle() {
            return missedCycle;
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

    /** 当前状态字快照, 供测试/诊断读取协议内部 (pendingKind / drainOwner / cycleSeq)。 */
    public SlotWord slot() {
        return word.get();
    }

    public Phase phase() {
        return word.get().phase;
    }

    /**
     * 是否处于 "某代 IO 已在管线在飞" 的三态之一 (SNAPSHOTTING / SERIALIZING / IO_PENDING)。
     * autosave 通道 (ChunkMapMixin) 用此判定在途短路, 避免对在飞 chunk 入队一个注定 trySnapshot 失败的
     * priority (静默 fallback + 虚高计数 + 关服 join 不知情)。
     */
    public boolean isInFlight() {
        Phase p = word.get().phase;
        return p == Phase.SNAPSHOTTING || p == Phase.SERIALIZING || p == Phase.IO_PENDING;
    }

    public long generation() {
        return generation.get();
    }

    public int retryCount() {
        return retryCount.get();
    }

    public long inFlightGeneration() {
        return word.get().inFlightGeneration;
    }

    /** mustDrain 现由 drainOwner 单一字段表达: drainOwner != NONE 即 "关服 join 必须为本 state 等待"。 */
    public boolean mustDrain() {
        return word.get().drainOwner != DrainOwner.NONE;
    }

    public DrainOwner drainOwner() {
        return word.get().drainOwner;
    }

    public boolean hasPendingSnapshot() {
        return word.get().pendingKind != PendingKind.NONE;
    }

    public void markDirty() {
        generation.incrementAndGet();
        SlotWord cur;
        do {
            cur = word.get();
            if (cur.phase != Phase.CLEAN) {
                return;
            }
        } while (!word.compareAndSet(cur, cur.withPhase(Phase.DIRTY)));
    }

    public boolean trySnapshot() {
        SlotWord cur;
        do {
            cur = word.get();
            if (cur.phase != Phase.DIRTY) {
                return false;
            }
        } while (!word.compareAndSet(cur, cur.withPhase(Phase.SNAPSHOTTING)));
        return true;
    }

    /**
     * 开一个 fresh 保存周期: 锁 inFlightGeneration=当前 generation, 分配新 inFlightCycleSeq, phase=SERIALIZING。
     * 返回锁定的内容代作为本快照的 capturedGeneration。
     *
     * <p>第九轮的 "enterSerializing 末尾清 EMPTY_CONSUMER_PASSED 单例" 已彻底退役: A4/A5 的跨周期 stale missed
     * 防御移到 {@link #beginPendingSnapshot} 的周期序号校验 (missedCycle==inFlightCycleSeq 才继承), 那是更强的
     * 代际 fence —— 挡得住 A5 那种 "清理之后才写入" 的 stale, 而单 CAS 时序清理挡不住。
     */
    public long enterSerializing() {
        long captured = generation.get();
        long cycleSeq = cycleSeqAllocator.incrementAndGet();
        SlotWord cur;
        do {
            cur = word.get();
        } while (!word.compareAndSet(cur, cur.withInFlight(captured, cycleSeq, Phase.SERIALIZING)));
        return captured;
    }

    public void enterIoPending() {
        SlotWord cur;
        do {
            cur = word.get();
        } while (!word.compareAndSet(cur, cur.withPhase(Phase.IO_PENDING)));
    }

    public IoOutcome ioCompletedSuccessfully() {
        SlotWord cur;
        SlotWord next;
        IoOutcome outcome;
        do {
            cur = word.get();
            if (generation.get() == cur.inFlightGeneration) {
                retryCount.set(0);
                // CLEAN_LANDED 终态: 清 drainOwner (IN_FLIGHT/RELAY -> NONE), 调用方据下面返回的 cleared 配平 gauge。
                next = cur.withPhase(Phase.CLEAN).withDrainOwner(DrainOwner.NONE);
                outcome = IoOutcome.CLEAN_LANDED;
            } else {
                // REQUEUE_DIRTY: 不碰 drainOwner (接力仍在途, 关服 join 须继续等)。
                next = cur.withPhase(Phase.DIRTY);
                outcome = IoOutcome.REQUEUE_DIRTY;
            }
            lastTransitionClearedDrain = outcome == IoOutcome.CLEAN_LANDED && cur.drainOwner != DrainOwner.NONE;
        } while (!word.compareAndSet(cur, next));
        return outcome;
    }

    /**
     * IO 成功落地 + 接力槽处置, <b>合并成单个 CAS 线性化点</b> (A5 根治)。这是把旧的两步 {@code ioCompletedSuccessfully()}
     * (land: 置 phase=DIRTY/CLEAN) + {@code takeReadyPendingSnapshot()} (take: 取 READY 或标 missed) 收口进一次 CAS。
     *
     * <p><b>为何必须合并 (A5 的根本)</b>: 两步分离时, 回调在 land 置 phase=DIRTY 之后、take 之前可能被 OS 切走;
     * 主线程在此窗口看到 phase=DIRTY 开新周期 (enterSerializing 把 inFlightCycleSeq 推到新周期); 随后回调的 take
     * 读到的 inFlightCycleSeq 已是<b>新周期</b>的, 把 stale missed 误标成新周期序号 -> 新周期 begin 校验同周期通过
     * 继承 -> 提前自踢 / 误判 CLEAN_LANDED (这正是第十轮 A5)。合并后 land 与 take 在同一 CAS 原子发生: 主线程看到
     * phase=DIRTY 时 take 必已完成 (missed 已用<b>本周期</b> inFlightCycleSeq 标好), 主线程随后 enterSerializing 推
     * cycleSeq 是在 missed 写定<b>之后</b>, 那个 missed 带的是旧周期序号, 新周期 begin 校验不等丢弃。窗口本体消失。
     *
     * <p><b>三态处置 (REQUEUE_DIRTY 时)</b>: READY 取走返回 (回调 reoffer); PREPARING 标 missedCycle=本周期 不取走
     * (交还主线程 publish 自踢); NONE 标 missedCycle=本周期 (供主线程随后 begin 同周期继承)。CLEAN_LANDED 不碰槽
     * (蕴含无碰撞编辑, 不应有 pending), 清 drainOwner。
     *
     * @return land 的判定 + REQUEUE_DIRTY 时取走的 READY 接力 (CLEAN_LANDED 或非 READY 时 relayPending 为 null)
     */
    public LandResult landAndTake() {
        SlotWord cur;
        SlotWord next;
        IoOutcome outcome;
        ChunkSnapshot relayPending;
        do {
            cur = word.get();
            relayPending = null;
            if (generation.get() == cur.inFlightGeneration) {
                retryCount.set(0);
                next = cur.withPhase(Phase.CLEAN).withDrainOwner(DrainOwner.NONE);
                outcome = IoOutcome.CLEAN_LANDED;
            } else {
                outcome = IoOutcome.REQUEUE_DIRTY;
                switch (cur.pendingKind) {
                    case READY -> {
                        relayPending = cur.pendingSnapshot;
                        next = cur.withPhase(Phase.DIRTY)
                                .withPending(PendingKind.NONE, null, 0L, NO_MISSED, cur.drainOwner);
                    }
                    case PREPARING -> {
                        // 标本周期 (land 时刻的 inFlightCycleSeq) missed: A5 根治 —— land 与标 missed 同一 CAS,
                        // 故 missed 必带 land 周期, 主线程随后开新周期推 cycleSeq 是在此之后, 不会漂移到新周期。
                        next = cur.withPhase(Phase.DIRTY)
                                .withPending(PendingKind.PREPARING, cur.pendingSnapshot, cur.pendingEpoch,
                                        cur.inFlightCycleSeq, cur.drainOwner);
                    }
                    case NONE -> {
                        next = cur.withPhase(Phase.DIRTY)
                                .withPending(PendingKind.NONE, null, 0L, cur.inFlightCycleSeq, cur.drainOwner);
                    }
                    default -> throw new IllegalStateException("unreachable pending kind: " + cur.pendingKind);
                }
            }
            lastTransitionClearedDrain = outcome == IoOutcome.CLEAN_LANDED && cur.drainOwner != DrainOwner.NONE;
        } while (!word.compareAndSet(cur, next));
        return new LandResult(outcome, relayPending);
    }

    /** {@link #landAndTake} 的原子结果: land 判定 + REQUEUE_DIRTY 时取走的 READY 接力 (否则 null)。 */
    public static final class LandResult {
        private final IoOutcome outcome;
        private final ChunkSnapshot relayPending;

        private LandResult(IoOutcome outcome, ChunkSnapshot relayPending) {
            this.outcome = outcome;
            this.relayPending = relayPending;
        }

        public IoOutcome outcome() {
            return outcome;
        }

        /** 仅 REQUEUE_DIRTY 且槽为 READY 时非 null (取走的就绪接力)。 */
        public ChunkSnapshot relayPending() {
            return relayPending;
        }
    }

    public IoOutcome ioFailed(int maxRetries) {
        int n = retryCount.incrementAndGet();
        SlotWord cur;
        SlotWord next;
        IoOutcome outcome;
        do {
            cur = word.get();
            if (n > maxRetries) {
                next = cur.withPhase(Phase.FAILED).withDrainOwner(DrainOwner.NONE);
                outcome = IoOutcome.FAILED_TERMINAL;
            } else {
                next = cur.withPhase(Phase.DIRTY);
                outcome = IoOutcome.REQUEUE_DIRTY;
            }
            lastTransitionClearedDrain = outcome == IoOutcome.FAILED_TERMINAL && cur.drainOwner != DrainOwner.NONE;
        } while (!word.compareAndSet(cur, next));
        return outcome;
    }

    // 上一次终态转换 (CLEAN_LANDED / FAILED_TERMINAL) 是否真把 drainOwner 由非NONE清成NONE。供 IO 完成回调
    // 据此 dec gauge 一次, 保证 drainOwner 清零与 gauge dec 是同一次 CAS 的原子结果。仅由同一回调线程写后即读
    // (whenComplete 是 per-task 单线程序列), 无跨线程可见性需求。
    private boolean lastTransitionClearedDrain;

    /**
     * 上一次终态转换 (ioCompletedSuccessfully / ioFailed) 内部 CAS 是否真把 drainOwner 由非NONE清成NONE。
     * IO 完成回调据此决定是否 dec mustDrainPending gauge, 保证 drainOwner 清零与 gauge dec 同步。
     */
    public boolean lastTransitionClearedMustDrain() {
        return lastTransitionClearedDrain;
    }

    public void resetAfterFallback() {
        retryCount.set(0);
        SlotWord cur;
        do {
            cur = word.get();
        } while (!word.compareAndSet(cur, cur.withPhase(Phase.CLEAN)));
    }

    /** drainOwner -> IN_FLIGHT (无条件)。 */
    public void markMustDrain() {
        SlotWord cur;
        do {
            cur = word.get();
        } while (!word.compareAndSet(cur, cur.withDrainOwner(DrainOwner.IN_FLIGHT)));
    }

    /** drainOwner -> NONE (无条件)。 */
    public void clearMustDrain() {
        SlotWord cur;
        do {
            cur = word.get();
        } while (!word.compareAndSet(cur, cur.withDrainOwner(DrainOwner.NONE)));
    }

    /** CAS drainOwner NONE -> IN_FLIGHT; 返回 true 表示首次置位, 调用方可 inc gauge 一次。 */
    public boolean tryMarkMustDrain() {
        SlotWord cur;
        do {
            cur = word.get();
            if (cur.drainOwner != DrainOwner.NONE) {
                return false;
            }
        } while (!word.compareAndSet(cur, cur.withDrainOwner(DrainOwner.IN_FLIGHT)));
        return true;
    }

    /** CAS drainOwner 非NONE -> NONE; 返回 true 表示首次清除, 调用方可 dec gauge 一次。 */
    public boolean compareAndClearMustDrain() {
        SlotWord cur;
        do {
            cur = word.get();
            if (cur.drainOwner == DrainOwner.NONE) {
                return false;
            }
        } while (!word.compareAndSet(cur, cur.withDrainOwner(DrainOwner.NONE)));
        return true;
    }

    /**
     * 碰撞登记: 把接力快照挂进 PREPARING 相 (主线程 dispatchSaveEvent <b>之前</b>调)。回调此刻能发现槽非空
     * (关 A1 lost-wakeup) 却因 PREPARING 不可消费而不会取走未就绪 tag (关 A2)。
     *
     * <p><b>覆盖语义 (最新者胜)</b>: 槽已占用时 (PREPARING/READY, 即 PREPARING 期间嵌套的第二次碰撞) 直接 CAS
     * 覆盖为更新代的新 PREPARING, 旧 pending 作废 (它是更新 pending 的真子集)。
     *
     * <p><b>missedCycle 周期校验 (A4/A5 双杀)</b>: 仅当 {@code current.missedCycle == current.inFlightCycleSeq}
     * (同周期回调路过) 才继承 missedCycle; stale missed (持旧周期序号) 在新周期 inFlightCycleSeq 已变, 校验不等
     * 即丢弃 (置 NO_MISSED), 不再依赖第九轮 enterSerializing 的时序性单 CAS 清理。
     *
     * <p><b>drainOwner 不变式与 A6 gauge 配平</b>: 槽非空 -> drainOwner != NONE, 故 begin 必把 drainOwner 拉为
     * 非 NONE。若进入时 drainOwner 已非 NONE (常规碰撞: 原始周期已 IN_FLIGHT, 或嵌套 begin), 保持原值不变 ->
     * 返回 false (无需 inc gauge)。若进入时 drainOwner == NONE (A6 窗口: 终态消费者已在本 begin 之前路过 EMPTY 并
     * 经 ioFailed 清掉了 drainOwner), begin 把它重新拉为 IN_FLIGHT 并返回 true -> 调用方据此 inc gauge 一次,
     * 补回终态消费者 EMPTY_DEAD 分支 honor 清除时 dec 掉的那一格。这条返回值是 A6 gauge 配平的关键: 终态消费者
     * EMPTY 分支只标 missedCycle 不夺 drain (留给随后必到的 publish 自踢链), 但它 honor 了 ioFailed 的 dec; begin
     * 发现 drain 被清空便重新获取并通知调用方补 inc, 让 "槽非空 -> gauge>=1" 不变式在 A6 窗口闭合。
     *
     * @param snapshot 本次碰撞 capture 的接力快照
     * @return true 表示 begin 把 drainOwner 由 NONE 重新拉为 IN_FLIGHT (A6 补配平), 调用方须 inc gauge 一次;
     *         false 表示 drainOwner 进入时已非 NONE (常规), 调用方无需配平
     */
    public boolean beginPendingSnapshot(ChunkSnapshot snapshot) {
        long epoch = snapshot.capturedGeneration();
        SlotWord cur;
        SlotWord next;
        boolean reacquiredDrain;
        do {
            cur = word.get();
            long inheritedMissed = cur.missedCycle == cur.inFlightCycleSeq ? cur.missedCycle : NO_MISSED;
            reacquiredDrain = cur.drainOwner == DrainOwner.NONE;
            DrainOwner owner = reacquiredDrain ? DrainOwner.IN_FLIGHT : cur.drainOwner;
            next = cur.withPending(PendingKind.PREPARING, snapshot, epoch, inheritedMissed, owner);
        } while (!word.compareAndSet(cur, next));
        return reacquiredDrain;
    }

    /**
     * 发布消费权: dispatchSaveEvent 成功返回后 PREPARING -> READY。tag 此刻已就绪 (listener 改写完成), 回调可消费。
     *
     * <p><b>返回值 = 主线程需自踢的 pending</b>: 三种情形主线程必须接过补踢 (那个本代在飞 IO 的唯一消费者走后不再来):
     * <ul>
     *   <li>{@code missedCycle == inFlightCycleSeq} (本周期真有回调路过 PREPARING/EMPTY): 取走 pending, 槽归 NONE,
     *       drainOwner 维持 (接力在途), 返回 pending 主线程自踢。</li>
     *   <li>{@code drainOwner == TERMINAL_HANDED} (A6: 终态消费者已路过, 在飞 task 已死): 同样不发 READY 孤儿,
     *       取走 pending 主线程自踢接力 (主线程是合法 offer 方)。</li>
     * </ul>
     * 否则正常 PREPARING -> READY (返 null, 等在飞回调消费)。仅主线程在 dispatch 成功后调。
     *
     * @return 非 null 表示主线程需自行 reoffer 这份 pending; null 表示已发布 READY 等回调消费
     */
    public ChunkSnapshot publishPendingSnapshot() {
        SlotWord cur;
        SlotWord next;
        ChunkSnapshot toReoffer;
        do {
            cur = word.get();
            if (cur.pendingKind != PendingKind.PREPARING) {
                // publish 只在 PREPARING 上有效 (理论不可达: 只有主线程能把 PREPARING 推走)。不触碰并发态。
                return null;
            }
            boolean sameCycleMissed = cur.missedCycle == cur.inFlightCycleSeq;
            boolean terminalHanded = cur.drainOwner == DrainOwner.TERMINAL_HANDED;
            if (sameCycleMissed || terminalHanded) {
                next = cur.withPending(PendingKind.NONE, null, 0L, NO_MISSED, cur.drainOwner);
                toReoffer = cur.pendingSnapshot;
            } else {
                next = cur.withPending(PendingKind.READY, cur.pendingSnapshot, cur.pendingEpoch, NO_MISSED,
                        cur.drainOwner);
                toReoffer = null;
            }
        } while (!word.compareAndSet(cur, next));
        return toReoffer;
    }

    /**
     * dispatchSaveEvent 抛 (第三方 listener 故障) 时主线程的自我撤销。登记先于派发完成, 故抛时槽仍是 PREPARING
     * (回调在 PREPARING 期间从不消费, 只标 missed)。取走 pending、槽归 NONE, 返回它供 mixin catch 据非空判定
     * "已撤销, 需亲自配平 mustDrain"。
     *
     * @return 撤销取回的 pending (恒非 null 当槽确为 PREPARING); 槽非 PREPARING 时返 null (理论不可达, 防御性)
     */
    public ChunkSnapshot abortPendingSnapshot() {
        SlotWord cur;
        ChunkSnapshot taken;
        do {
            cur = word.get();
            if (cur.pendingKind != PendingKind.PREPARING) {
                return null;
            }
            taken = cur.pendingSnapshot;
        } while (!word.compareAndSet(cur,
                cur.withPending(PendingKind.NONE, null, 0L, NO_MISSED, cur.drainOwner)));
        return taken;
    }

    /**
     * 在飞那代 IO 落地判 REQUEUE_DIRTY 时由回调 (IOWorker 完成回调线程) 调, 取出 <b>已就绪</b> 的接力快照重投。
     * <ul>
     *   <li>READY: CAS READY->NONE 取走返回, 回调 reoffer 它。</li>
     *   <li>PREPARING: tag 未就绪 (dispatch 仍在跑), <b>禁止消费</b>。标 missedCycle=inFlightCycleSeq 后返 null 离开,
     *       把补踢交还主线程 (主线程 publish 见同周期 missed 自踢)。关 A2 未就绪暴露的核心动作。</li>
     *   <li>EMPTY (NONE): 无 pending。仍标 missedCycle=inFlightCycleSeq (A4/A5 载体): 主线程随后 beginPending 会
     *       同周期校验通过继承它 -> publish 补踢; 跨周期则被丢弃。</li>
     * </ul>
     *
     * @return 非 null (仅 READY 时) 则回调重投它接力落盘; null 则回调走原 REQUEUE_DIRTY 语义不重投
     */
    public ChunkSnapshot takeReadyPendingSnapshot() {
        SlotWord cur;
        SlotWord next;
        ChunkSnapshot taken;
        do {
            cur = word.get();
            switch (cur.pendingKind) {
                case READY -> {
                    next = cur.withPending(PendingKind.NONE, null, 0L, NO_MISSED, cur.drainOwner);
                    taken = cur.pendingSnapshot;
                }
                case PREPARING -> {
                    if (cur.missedCycle == cur.inFlightCycleSeq) {
                        // 已标本周期 missed, 幂等离开。
                        return null;
                    }
                    next = cur.withPending(PendingKind.PREPARING, cur.pendingSnapshot, cur.pendingEpoch,
                            cur.inFlightCycleSeq, cur.drainOwner);
                    taken = null;
                }
                case NONE -> {
                    if (cur.missedCycle == cur.inFlightCycleSeq) {
                        return null;
                    }
                    next = cur.withPending(PendingKind.NONE, null, 0L, cur.inFlightCycleSeq, cur.drainOwner);
                    taken = null;
                }
                default -> throw new IllegalStateException("unreachable pending kind: " + cur.pendingKind);
            }
        } while (!word.compareAndSet(cur, next));
        return taken;
    }

    /**
     * 测试/降级便捷入口: 原子地把 pending 直接发布为 READY (跳过 PREPARING)。语义等价于 "无 dispatch 窗口的接力
     * 登记": entity 路径 (无 Forge 事件) 与单测消费侧用例正是这种退化形态。覆盖最新者胜, 不带 missed (READY 即可
     * 消费)。drainOwner 拉为 IN_FLIGHT (槽非空不变式), 已是 TERMINAL_HANDED 则保留。
     *
     * <p><b>注意</b>: chunk 主线程碰撞分支<b>不</b>走此入口 —— 它有 dispatchSaveEvent 窗口, 必须 begin(PREPARING)
     * -> dispatch -> publish(READY), 否则回归 A2 "未就绪 tag 暴露"。本入口仅供无窗口场景。
     */
    public void registerReadyPendingSnapshot(ChunkSnapshot snapshot) {
        long epoch = snapshot.capturedGeneration();
        SlotWord cur;
        do {
            cur = word.get();
            DrainOwner owner = cur.drainOwner == DrainOwner.TERMINAL_HANDED
                    ? DrainOwner.TERMINAL_HANDED
                    : DrainOwner.IN_FLIGHT;
            // CAS 防与回调 take 竞争 (虽然测试场景多单线程, 生产降级路径仍以原子为安全)。
            if (word.compareAndSet(cur,
                    cur.withPending(PendingKind.READY, snapshot, epoch, NO_MISSED, owner))) {
                return;
            }
        } while (true);
    }

    /**
     * 终态死亡路径 (FAILED_TERMINAL / onUnhandledError) 取出接力槽的一次原子处置, 关死 "绕过 READY 门" 的旁路出口 (A3)。
     * <ul>
     *   <li>READY: CAS READY->NONE 取走, 判 {@code CONSUMED} —— 在飞 task 当其消费者直接重投 (合法就绪 tag, 无 race)。
     *       drainOwner 维持 (接力在途, 由调用方拨 RELAY)。</li>
     *   <li>PREPARING: 标 missedCycle=inFlightCycleSeq + drainOwner=TERMINAL_HANDED (A6 对称), 判 {@code HANDED_TO_MAIN},
     *       不取走 —— 补踢交还主线程 publish 自踢。调用方必须 early-return: 不清 drainOwner、不走 enqueueRecovery 安全网。</li>
     *   <li>EMPTY (NONE): 无就绪接力。判 {@code EMPTY_DEAD}, <b>但</b>对称标 missedCycle=inFlightCycleSeq (A6 修复核心):
     *       若主线程随后才 begin (终态消费者先于 begin 到达的窗口), begin 同周期继承 missedCycle, publish 见同周期 missed
     *       自踢不发 READY 孤儿; 且 begin 发现 drainOwner 被 ioFailed 清空会重新获取并让调用方补 inc gauge。drainOwner
     *       本分支不动 (留给 EMPTY_DEAD 调用方 honor ioFailed 的 dec, 真死亡时正确配平)。</li>
     * </ul>
     * 判别与标记是同一次 CAS 的原子结果, 杜绝 TOCTOU。
     */
    public ReadyTake takeReadyForTerminalConsumer() {
        SlotWord cur;
        SlotWord next;
        ReadyTake result;
        do {
            cur = word.get();
            switch (cur.pendingKind) {
                case READY -> {
                    next = cur.withPending(PendingKind.NONE, null, 0L, NO_MISSED, cur.drainOwner);
                    result = new ReadyTake(ReadyTake.Disposition.CONSUMED, cur.pendingSnapshot);
                }
                case PREPARING -> {
                    next = cur.withPending(PendingKind.PREPARING, cur.pendingSnapshot, cur.pendingEpoch,
                            cur.inFlightCycleSeq, DrainOwner.TERMINAL_HANDED);
                    result = ReadyTake.handedToMain();
                }
                case NONE -> {
                    // A6 修复核心: 对称于成功路径 (takeReadyPendingSnapshot 的 EMPTY 分支) 标 missedCycle, 供随后必到的
                    // 主线程 begin 同周期继承 -> publish 见同周期 missed 自踢, 不发 READY 孤儿。但**不**在此把 drainOwner
                    // 拨 TERMINAL_HANDED: 进入本方法前 ioFailed 已把 drainOwner 清成 NONE 且 EMPTY_DEAD 调用方会 honor 这次
                    // dec (真死亡时正确配平)。若这其实是 A6 窗口 (主线程随后 begin), begin 发现 drainOwner==NONE 重新拉
                    // IN_FLIGHT 并返回 true 让调用方补 inc, 重新闭合不变式。把 TERMINAL_HANDED 留给 PREPARING 分支 (那里
                    // 主线程已确证在 dispatch, 交还语义明确且不与 EMPTY_DEAD 的 honor-dec 冲突)。
                    next = cur.withPending(PendingKind.NONE, null, 0L, cur.inFlightCycleSeq, cur.drainOwner);
                    result = ReadyTake.emptyDead();
                }
                default -> throw new IllegalStateException("unreachable pending kind: " + cur.pendingKind);
            }
        } while (!word.compareAndSet(cur, next));
        return result;
    }

    /**
     * {@link #takeReadyForTerminalConsumer} 的原子三态结果: 判别 + 取走的 pending。
     */
    public static final class ReadyTake {

        public enum Disposition {
            /** 取到就绪 READY pending, 在飞 task 直接重投它。 */
            CONSUMED,
            /** 槽为 PREPARING 已标 missed + TERMINAL_HANDED, 补踢交还主线程 publish 自踢; 调用方 early-return。 */
            HANDED_TO_MAIN,
            /** 槽 EMPTY 无就绪接力 (已对称标 missedCycle, drainOwner 不变); 调用方走安全网 honor 清除 + 兜底。 */
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
     * 无条件清空槽 (任意态 -> NONE) + drainOwner -> NONE, 返回曾停泊的 pending (PREPARING/READY 的 snapshot, 否则 null)。
     * 供 <b>关服终局</b> (joinWorkers 后 workersStopping 残窗, 主线程已不可能再 publish) 全清防泄漏。
     *
     * <p><b>用途已收窄</b>: 两个终态死亡调用点 (onUnhandledError / FAILED_TERMINAL) 已改用
     * {@link #takeReadyForTerminalConsumer} (只消费 READY, PREPARING 交还主线程)。本方法只保留给关服终局:
     * worker 已停、主线程已过 ChunkMap.save 拦截窗口不再 publish, 此时全清 (含理论残留的 PREPARING) 才安全。
     *
     * @return 曾停泊的 pending (PREPARING/READY); 槽本就 EMPTY 时返 null
     */
    public ChunkSnapshot drainPendingSnapshot() {
        SlotWord cur;
        ChunkSnapshot prev;
        do {
            cur = word.get();
            prev = cur.pendingSnapshot;
        } while (!word.compareAndSet(cur,
                cur.withPending(PendingKind.NONE, null, 0L, NO_MISSED, DrainOwner.NONE)));
        return prev;
    }

    /**
     * 重投接力快照前把 inFlightGeneration 锁到该 pending 快照 capture 时的代, 分配新 inFlightCycleSeq,
     * phase 推回 SERIALIZING, drainOwner 拉为 RELAY (接力链在途持有 drain)。
     *
     * <p><b>为何不复用 enterSerializing</b>: enterSerializing 锁 {@code generation.get()} 当前代, 但接力快照的代
     * 在 (更早的) 纯 capture 时刻就已固定 (存于 snapshot.capturedGeneration)。重投按当前 generation 锁会错配 ——
     * 必须锁 pending 自己的代, 才能让该接力 IO 落地时 generation==inFlightGeneration 正确判 CLEAN_LANDED (无更新
     * 编辑) 或 REQUEUE_DIRTY (又有更新 pending)。
     *
     * <p>分配新 cycleSeq (A7): 接力是一个新的在飞周期, 必须有新周期身份, 否则上一周期的 missedCycle 会被新周期
     * 误判同周期。
     */
    public void reenterSerializingForPending(long capturedGeneration) {
        long cycleSeq = cycleSeqAllocator.incrementAndGet();
        SlotWord cur;
        do {
            cur = word.get();
        } while (!word.compareAndSet(cur,
                cur.withInFlight(capturedGeneration, cycleSeq, Phase.SERIALIZING).withDrainOwner(DrainOwner.RELAY)));
    }
}
