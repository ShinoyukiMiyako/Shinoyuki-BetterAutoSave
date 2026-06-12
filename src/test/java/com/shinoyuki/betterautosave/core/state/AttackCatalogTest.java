package com.shinoyuki.betterautosave.core.state;

import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.snapshot.ChunkSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 攻击目录 A5/A6/A7 + 协议核心不变式 (cycleSeq 单调性 / drainOwner 不变式) 的受控复刻。
 * 每条对应攻击删除其防御 (mutation) 必挂; 每条 mutation 写在对应测试的 javadoc 末尾。
 *
 * <p>A1-A4 由既有测试覆盖 (标注复用):
 * <ul>
 *   <li>A1 丢唤醒 / A2 未就绪暴露: ChunkPendingStateMachineTest (begin 先于 dispatch + READY-only take 矩阵)。</li>
 *   <li>A3 drain 旁路: TerminalExitPreparingHandoffTest (终态消费者 READY-only, PREPARING 交还)。</li>
 *   <li>A4 跨周期 stale (清理前已存在的 stale): StaleMissedCrossCycleTest (三代 ABA)。</li>
 * </ul>
 * 本文件补 A5 (清理之后才写入的 stale, A4 的进阶) / A6 (begin 前终态 + 孤儿跨周期复活) / A7 (周期序号复用)。
 */
class AttackCatalogTest {

    private static ResourceKey<Level> DIM;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DIM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation("minecraft", "overworld"));
    }

    /** enterSerializing 并返回它分配的在飞周期序号 (供断言 missedCycle 的周期归属)。 */
    private long enterAndGetCycle(ChunkSaveState state) {
        state.enterSerializing();
        return state.slot().inFlightCycleSeq();
    }

    private ChunkSnapshot snapshotForGeneration(ChunkSaveState state, long generation) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(7, -3), DIM, tag, generation, state,
                ConfigSpec.EventCompatMode.FULL);
    }

    /**
     * A5 (DIRTY 发布竞速, 第十轮) —— land+take 合并单 CAS 是根治。核心命题: 回调在 land(DIRTY) 那一刻就<b>原子</b>
     * 把 stale missed 用<b>本周期</b> (land 时刻的 inFlightCycleSeq) 标好, 主线程随后开新周期推 cycleSeq 是在 missed
     * 写定<b>之后</b>, 故那份 stale 带的永远是旧周期序号, 跨周期判据 (begin / publish 的 missedCycle==inFlightCycleSeq)
     * 永不误判同周期。
     *
     * <p>本测试用两个独立 state 对照, 直接锁定 "missed 必须带 land 周期而非 take-时-当前周期" 这个唯一防御点:
     * <ul>
     *   <li><b>合并路径 (landAndTake, 生产路径)</b>: land 与标 missed 在同一 CAS, missed 带 land 周期 (gen1)。
     *       即便随后主线程开 gen2 周期, missed 仍是 gen1 周期 -> 跨周期, begin/publish 不自踢。</li>
     *   <li><b>拆分路径 (ioCompletedSuccessfully 先, 主线程开新周期, 再 takeReadyPendingSnapshot) = A5 漏洞本体</b>:
     *       take 在主线程已推 cycleSeq 到 gen2 后才跑, 读当前 inFlightCycleSeq=gen2 把 missed 误标成 gen2 周期 ->
     *       同周期, begin 继承 -> publish 自踢 (BUG)。本测试断言这条拆分路径确实把 missed 标成新周期 (证明窗口真实
     *       存在), 从而证明合并路径消除了它。</li>
     * </ul>
     * mutation (删修复必挂): 把 onIoSuccess 从 landAndTake 改回 "ioCompletedSuccessfully 然后 takeReadyPendingSnapshot"
     * 拆分两步 (即下面 SPLIT 段的序), 生产 A5 漏洞重现 —— 合并段断言 missedCycle 带旧周期 会因实际带新周期而挂。
     */
    @Test
    void a5_landAndTake_tags_missed_with_land_cycle_not_advanced_cycle() {
        // ===== 合并路径 (生产 landAndTake): missed 带 land 周期, 跨周期被丢 =====
        ChunkSaveState merged = new ChunkSaveState(new ChunkPos(7, -3).toLong(), "minecraft:overworld", 1L);
        merged.markDirty();          // gen=1
        merged.trySnapshot();
        long gen1Cycle = enterAndGetCycle(merged);   // gen1 周期
        merged.enterIoPending();
        merged.markDirty();          // gen=2 (纯编辑, 槽 EMPTY)

        ChunkSaveState.LandResult gen1 = merged.landAndTake();   // land+take 原子: missed 标 gen1 周期
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, gen1.outcome());
        assertEquals(gen1Cycle, merged.slot().missedCycle(),
                "合并 landAndTake: missed 必带 land 时刻的 gen1 周期序号 (而非任何更晚的周期)");

        // 主线程随后开 gen2 周期 (推 cycleSeq)。missed 写定在前, 故仍是 gen1 周期。
        merged.trySnapshot();
        long gen2Cycle = enterAndGetCycle(merged);   // gen2 周期 > gen1 周期
        assertTrue(gen2Cycle > gen1Cycle);
        merged.enterIoPending();
        assertNotEquals(gen2Cycle, merged.slot().missedCycle(),
                "missed 仍带 gen1 周期, 不随主线程开新周期而漂移到 gen2");

        // gen3 碰撞 begin: missed(gen1 周期) != inFlightCycleSeq(gen2 周期) -> 丢弃 -> publish 不自踢。
        merged.markDirty();          // gen=3
        ChunkSnapshot gen3 = snapshotForGeneration(merged, 3L);
        merged.beginPendingSnapshot(gen3);
        assertNull(merged.publishPendingSnapshot(),
                "合并路径: stale missed 跨周期被丢, publish 发布 READY 不提前自踢");
        assertTrue(merged.hasPendingSnapshot(), "槽为 READY 等 gen2 回调");
        ChunkSaveState.LandResult gen2Land = merged.landAndTake();
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, gen2Land.outcome(),
                "gen2 落地正确 REQUEUE_DIRTY, inFlightGeneration 未被幽灵自踢覆盖");
        assertSame(gen3, gen2Land.relayPending());

        // ===== 拆分路径 (A5 漏洞本体, 证明窗口真实存在): take 在主线程已开新周期后跑, missed 误标新周期 =====
        ChunkSaveState split = new ChunkSaveState(new ChunkPos(7, -3).toLong(), "minecraft:overworld", 1L);
        split.markDirty();           // gen=1
        split.trySnapshot();
        long splitGen1Cycle = enterAndGetCycle(split);
        split.enterIoPending();
        split.markDirty();           // gen=2

        split.ioCompletedSuccessfully();    // land only (REQUEUE_DIRTY), take 尚未发生
        // [窗口] 主线程在 take 之前开 gen2 周期。
        split.trySnapshot();
        long splitGen2Cycle = enterAndGetCycle(split);
        split.enterIoPending();
        // 现在才 take: 读当前 inFlightCycleSeq=gen2 周期, 把 missed 误标成 gen2 周期 (A5 的错标本体)。
        assertNull(split.takeReadyPendingSnapshot());
        assertEquals(splitGen2Cycle, split.slot().missedCycle(),
                "拆分路径: take 晚于主线程开新周期, missed 被误标成 gen2 周期 (A5 窗口确实存在)");
        assertNotEquals(splitGen1Cycle, split.slot().missedCycle(),
                "对照: 错标的不是 land 的 gen1 周期, 证明合并 landAndTake 才是消除该错标的根治");
    }

    /**
     * A6 第一相 (begin 前终态, EMPTY 窗口): 终态消费者夹在主线程 tryMarkMustDrain 与 beginPendingSnapshot 之间到达,
     * 在 EMPTY 槽上判 EMPTY_DEAD。修复前不对称 (不标 missed) -> 主线程随后 begin/publish 发布无人消费的 READY 孤儿 +
     * mustDrain 已被终态清成 false (不变式破)。修复后: 终态 EMPTY 分支对称标 missedCycle=本周期; begin 同周期继承 ->
     * publish 自踢不发孤儿; begin 发现 drainOwner 被终态清空 (ioFailed 路径) 重新获取并返 true 让调用方补 inc gauge。
     *
     * <p>本测试直接在状态机层复刻 EMPTY 窗口序 (不经 ChunkSaveTask 以隔离协议本身), 用 maxRetries=0 让首次失败即终态。
     *
     * <p>mutation (删修复必挂):
     * (1) takeReadyForTerminalConsumer 的 NONE 分支改回不标 missedCycle -> 下面断言 "publish 返非 null (自踢)" 翻转
     *     成 publish 返 null (发 READY 孤儿), 断言 selfReoffer != null 挂, 且 hasPendingSnapshot 终态为 true (孤儿) 挂。
     * (2) beginPendingSnapshot 改回不返回 reacquire 标志 (void) -> 编译期断 (调用方拿不到补 inc 信号); 若强行让它
     *     恒返 false -> 下面 gauge 配平断言 (终值 0) 在 A6 路径变 -1 挂。
     */
    @Test
    void a6_terminal_before_begin_empty_window_hands_off_no_orphan() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(7, -3).toLong(), "minecraft:overworld", 1L);
        int[] gauge = {0};

        // 原始周期在飞: 主线程 mixin 已 markMustDrain (gauge=1), gen=1 IO 在飞。
        state.markDirty();          // gen=1
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=1
        state.enterIoPending();
        if (state.tryMarkMustDrain()) {
            gauge[0]++;             // gauge=1
        }
        assertEquals(1, gauge[0]);

        // 主线程碰撞编辑 gen=2 (将要 begin 但尚未到 begin)。
        state.markDirty();          // gen=2

        // [EMPTY 窗口] 终态消费者到达: ioFailed(0) FAILED_TERMINAL 清 drainOwner; takeReadyForTerminalConsumer
        // 在 EMPTY 槽判 EMPTY_DEAD 并对称标 missedCycle=本周期。EMPTY_DEAD 调用方 honor ioFailed 的 dec。
        ChunkSaveState.IoOutcome failed = state.ioFailed(0);
        assertEquals(ChunkSaveState.IoOutcome.FAILED_TERMINAL, failed);
        ChunkSaveState.ReadyTake take = state.takeReadyForTerminalConsumer();
        assertEquals(ChunkSaveState.ReadyTake.Disposition.EMPTY_DEAD, take.disposition(),
                "EMPTY 窗口终态消费者判 EMPTY_DEAD");
        if (state.lastTransitionClearedMustDrain()) {
            gauge[0]--;             // honor ioFailed 的清除 -> gauge=0
        }
        assertEquals(0, gauge[0], "EMPTY_DEAD honor 终态 dec, gauge=0");
        assertFalse(state.mustDrain(), "终态后 drainOwner=NONE");

        // 主线程续跑到 begin (无 phase 重检, 它已提交在碰撞分支)。begin 发现 drainOwner 被清空, 重新获取 IN_FLIGHT
        // 返 true -> 补 inc gauge。同周期继承 missedCycle。
        ChunkSnapshot gen2Pending = snapshotForGeneration(state, 2L);
        boolean reacquired = state.beginPendingSnapshot(gen2Pending);
        assertTrue(reacquired, "A6 窗口: begin 发现 drainOwner 被终态清空, 重新获取并通知调用方补 inc");
        gauge[0]++;                 // 补 inc -> gauge=1
        assertEquals(1, gauge[0]);
        assertTrue(state.mustDrain(), "begin 后槽非空 -> drainOwner != NONE (不变式)");

        // dispatch 成功 publish: 同周期 missed -> 主线程自踢 (返非 null), 不发 READY 孤儿。
        ChunkSnapshot selfReoffer = state.publishPendingSnapshot();
        assertSame(gen2Pending, selfReoffer,
                "A6 修复: 终态消费者标了同周期 missed, publish 自踢返回 pending 而非发布 READY 孤儿");
        assertFalse(state.hasPendingSnapshot(), "自踢后槽 NONE, 无孤儿 READY");

        // 主线程自踢接力: reenter (drainOwner=RELAY) + 接力 IO 落地 CLEAN -> 清 drainOwner -> dec gauge。
        state.reenterSerializingForPending(selfReoffer.capturedGeneration());
        state.enterIoPending();
        ChunkSaveState.LandResult relayLand = state.landAndTake();
        assertEquals(ChunkSaveState.IoOutcome.CLEAN_LANDED, relayLand.outcome(),
                "接力 IO 落地 generation(2)==inFlightGeneration(2) -> CLEAN_LANDED");
        if (state.lastTransitionClearedMustDrain()) {
            gauge[0]--;             // 接力终态唯一清 -> gauge=0
        }
        assertEquals(0, gauge[0], "A6 全程 gauge 配平归零, 无正偏移泄漏");
        assertFalse(state.mustDrain(), "终局 drainOwner=NONE");
        assertFalse(state.hasPendingSnapshot(), "终局槽 NONE");
    }

    /**
     * A6 第二相 (孤儿 READY 跨周期复活的二阶后果): 若一份 stale 处置后 missedCycle 残留, 它绝不得让 <b>下一个完整
     * 保存周期</b> 的 begin 误继承触发自踢。这测的是 A6 EMPTY 分支标的 missedCycle 在 "主线程其实不再 begin (真死亡),
     * 而是开了一个全新 fresh 周期" 时被正确丢弃。
     *
     * <p>mutation (删修复必挂): 把 begin 的 missedCycle 继承条件从 "==inFlightCycleSeq" 改成无条件继承 -> 下面新
     * fresh 周期的 begin 会继承上一周期 EMPTY_DEAD 残留的 missedCycle -> publish 自踢 (返非 null) -> 断言挂。
     */
    @Test
    void a6_second_order_terminal_empty_marker_does_not_resurrect_into_fresh_cycle() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(7, -3).toLong(), "minecraft:overworld", 1L);

        // 周期 X 在飞 -> 终态消费者在 EMPTY 槽标 missedCycle=X 周期 (真死亡, 主线程不再 begin 本周期)。
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();   // cycleSeq=X
        state.enterIoPending();
        state.ioFailed(0);          // FAILED_TERMINAL
        ChunkSaveState.ReadyTake take = state.takeReadyForTerminalConsumer();
        assertEquals(ChunkSaveState.ReadyTake.Disposition.EMPTY_DEAD, take.disposition());

        // 真死亡后 vanilla 兜底 -> chunk 又被编辑 -> 全新 fresh 周期 Y (enterSerializing 推 cycleSeq 到 Y > X)。
        state.resetAfterFallback();
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();   // cycleSeq=Y
        state.enterIoPending();

        // 周期 Y 内碰撞 begin: 上一周期 X 残留的 missedCycle 带 X 序号 != Y -> 丢弃, publish 不自踢。
        state.markDirty();
        ChunkSnapshot yPending = snapshotForGeneration(state, state.generation());
        state.beginPendingSnapshot(yPending);
        assertNull(state.publishPendingSnapshot(),
                "上一周期 EMPTY_DEAD 残留的 missedCycle (旧周期序号) 不得复活进新 fresh 周期 Y 触发自踢");
        assertTrue(state.hasPendingSnapshot(), "Y 周期 publish 发布 READY 等回调");
    }

    /**
     * A7 (周期序号复用, 架构师自找的新边缘): missedCycle==inFlightCycleSeq 当作 "同周期" 判据。若 inFlightCycleSeq
     * 用内容代 generation 而非独立单调序号, 则 "两个不同在飞周期偶然复用同一 generation 数值" 会让跨周期 stale 被
     * 误判同周期。本测试证明 inFlightCycleSeq 在 <b>内容代相同</b> 的两个相邻周期里 <b>严格不同</b> (单调递增),
     * 故同周期判据不因内容代复用而误判。
     *
     * <p>构造: 两个周期锁定相同的 inFlightGeneration (接力 reenter 复用 pending 旧代是真实场景), 但 cycleSeq 必不同。
     *
     * <p>mutation (删修复必挂): 把 inFlightCycleSeq 的来源从 cycleSeqAllocator.incrementAndGet() 换成
     * inFlightGeneration (即用内容代当周期身份) -> 下面 assertNotEquals(cycleSeq1, cycleSeq2) 在两周期同内容代时
     * 退化成相等 -> 断言挂; 且 stale missed 跨周期判据失效 (A4/A5 用例随之挂)。
     */
    @Test
    void a7_cycle_seq_is_monotonic_and_decoupled_from_generation() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(7, -3).toLong(), "minecraft:overworld", 1L);

        // 周期 1: 锁内容代 5。
        for (int i = 0; i < 5; i++) {
            state.markDirty();      // generation=5
        }
        state.trySnapshot();
        state.enterSerializing();   // inFlightGeneration=5, cycleSeq1
        long cycleSeq1 = state.slot().inFlightCycleSeq();
        assertEquals(5L, state.inFlightGeneration());

        // 周期 2: 接力 reenter 复用同一内容代 5 (capturedGeneration=5), 但分配新 cycleSeq。
        state.reenterSerializingForPending(5L);     // inFlightGeneration=5 (复用!), cycleSeq2
        long cycleSeq2 = state.slot().inFlightCycleSeq();
        assertEquals(5L, state.inFlightGeneration(), "两周期内容代相同 (接力复用 pending 旧代)");

        assertNotEquals(cycleSeq1, cycleSeq2,
                "内容代相同的两个相邻在飞周期, cycleSeq 必严格不同 (单调递增, 与 generation 解耦)");
        assertTrue(cycleSeq2 > cycleSeq1, "cycleSeq 单调递增, 永不复用");

        // 再开一个 fresh 周期, 仍单调。
        state.markDirty();          // generation=6
        state.trySnapshot();
        state.enterSerializing();
        long cycleSeq3 = state.slot().inFlightCycleSeq();
        assertTrue(cycleSeq3 > cycleSeq2, "每个新周期 cycleSeq 严格递增");
    }

    /**
     * 协议核心不变式: 槽非空 (pendingKind != NONE) 蕴含 drainOwner != NONE (即 mustDrain 真)。这是 A6 升级后的硬
     * 不变式 —— A6 的破坏点正是 "槽非空但 mustDrain 已被清成 false"。逐转移后断言。
     *
     * <p>mutation (删修复必挂): 把 beginPendingSnapshot 里 "drainOwner == NONE ? IN_FLIGHT : 原值" 改成不拉
     * drainOwner (允许 PREPARING 时 drainOwner=NONE) -> 下面 begin 后的不变式断言挂。
     */
    @Test
    void invariant_non_empty_slot_implies_drain_owner_non_none() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(7, -3).toLong(), "minecraft:overworld", 1L);

        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();
        // 不预先 markMustDrain: 模拟 A6 那种 drainOwner 被清空后 begin 必须自行重建不变式。
        assertFalse(state.mustDrain(), "起始 drainOwner=NONE");

        ChunkSnapshot pending = snapshotForGeneration(state, state.generation());
        boolean reacquired = state.beginPendingSnapshot(pending);
        assertTrue(reacquired, "drainOwner 进入时 NONE, begin 重新获取返 true");
        assertHasPendingImpliesDrain(state);

        state.publishPendingSnapshot();     // 无 missed -> READY
        assertHasPendingImpliesDrain(state);

        // 回调取走 READY -> 槽 NONE; drainOwner 仍 IN_FLIGHT (在飞接力代未终态), 不变式 (单向蕴含) 不违反。
        ChunkSnapshot taken = state.takeReadyPendingSnapshot();
        assertSame(pending, taken);
        assertHasPendingImpliesDrain(state);
    }

    /** 单向蕴含: 槽非空 -> drainOwner != NONE。(反向不要求: 在飞但无 pending 时 drainOwner 也可非 NONE。) */
    private void assertHasPendingImpliesDrain(ChunkSaveState state) {
        if (state.hasPendingSnapshot()) {
            assertTrue(state.mustDrain(),
                    "不变式: pendingKind != NONE 必蕴含 drainOwner != NONE (A6 不变式破坏点)");
        }
    }

    /**
     * A3 加固 (终态消费者 PREPARING 交还): 终态消费者在 PREPARING 槽上判 HANDED_TO_MAIN 时, 把 drainOwner 拨成
     * TERMINAL_HANDED 并标本周期 missedCycle —— 两个 publish 自踢触发点 (drainOwner==TERMINAL_HANDED 与
     * missedCycle==inFlightCycleSeq) 都活, 协议对未来改动稳健。本测试锁定 drainOwner=TERMINAL_HANDED 不被
     * 调用方 markMustDrain 覆盖成 IN_FLIGHT。
     *
     * <p>mutation (删加固必挂): 在 ChunkSaveTask.handleTerminalFailure 的 HANDED_TO_MAIN 分支恢复 markMustDrain()
     * -> drainOwner 被覆盖成 IN_FLIGHT -> 下面 assertEquals(TERMINAL_HANDED) 挂。(此处直接在状态机层断言 take 后
     * 的 drainOwner, 不经 task, 隔离协议本身。)
     */
    @Test
    void a3_terminal_consumer_on_preparing_sets_terminal_handed_drain_owner() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(7, -3).toLong(), "minecraft:overworld", 1L);

        // 在飞 + 主线程 begin 挂 PREPARING (drainOwner=IN_FLIGHT)。
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();
        state.markMustDrain();
        state.markDirty();
        ChunkSnapshot pending = snapshotForGeneration(state, state.generation());
        state.beginPendingSnapshot(pending);
        assertEquals(ChunkSaveState.DrainOwner.IN_FLIGHT, state.drainOwner(), "begin 后 drainOwner=IN_FLIGHT");

        // 终态消费者到达 PREPARING: ioFailed 清 drainOwner -> takeReadyForTerminalConsumer 判 HANDED_TO_MAIN
        // 并把 drainOwner 拨 TERMINAL_HANDED + 标本周期 missed。
        state.ioFailed(0);
        long cycleBeforeTake = state.slot().inFlightCycleSeq();
        ChunkSaveState.ReadyTake take = state.takeReadyForTerminalConsumer();
        assertEquals(ChunkSaveState.ReadyTake.Disposition.HANDED_TO_MAIN, take.disposition());
        assertEquals(ChunkSaveState.DrainOwner.TERMINAL_HANDED, state.drainOwner(),
                "终态消费者把 PREPARING 的 drainOwner 拨 TERMINAL_HANDED (满足不变式 + 保留 publish 触发点)");
        assertEquals(cycleBeforeTake, state.slot().missedCycle(), "同时标本周期 missedCycle (冗余触发点)");
        assertTrue(state.hasPendingSnapshot(), "PREPARING 槽未被夺走 (READY-only, A3)");
        assertTrue(state.mustDrain(), "TERMINAL_HANDED 非 NONE, 槽非空不变式成立");

        // publish 经 terminalHanded 触发点自踢 (返回 pending), 不发 READY 孤儿。
        ChunkSnapshot toReoffer = state.publishPendingSnapshot();
        assertSame(pending, toReoffer, "publish 经 TERMINAL_HANDED 触发自踢返回 pending");
        assertFalse(state.hasPendingSnapshot(), "自踢后槽 NONE");
    }
}
