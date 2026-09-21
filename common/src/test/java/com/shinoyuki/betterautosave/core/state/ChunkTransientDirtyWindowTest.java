package com.shinoyuki.betterautosave.core.state;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 瞬态 DIRTY 窗口: 会继续在飞的回调 (原地重投 / 接力重投) 不得在两次 CAS 之间发布 phase=DIRTY。
 *
 * <p>主线程 ChunkMap.save 只按 phase 两值判定 "在飞 -> 碰撞分支登记接力" 或 "DIRTY -> 开新周期"。回调若先
 * CAS 出 DIRTY、再另一次 CAS 回到在飞态, 夹在中间的主线程会开新周期, 与回调的重投双在飞 (inFlightGeneration
 * 被覆盖、drainOwner 永不清零), 或在 trySnapshot 失败时按 "已被接管" 取消本次 save 而不登记接力。
 *
 * <p>本类用单线程按交错顺序直接驱动生产原语, 确定性复刻窗口; 主线程在窗口内的动作由 {@code trySnapshot} 与
 * {@code isInFlight} 代表 (ChunkMapSaveMixin 的两个分支正是据此选择)。
 */
class ChunkTransientDirtyWindowTest {

    private static final int MAX_RETRIES = 3;

    /** gen=1 的 IO 已提交在飞 (IO_PENDING), 且已置 mustDrain (drainOwner=IN_FLIGHT)。 */
    private static ChunkSaveState inFlightAtGenerationOne() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        state.markDirty();
        assertTrue(state.trySnapshot());
        state.enterSerializing();
        state.enterIoPending();
        assertTrue(state.tryMarkMustDrain());
        return state;
    }

    private static CapturedSnapshot capturedAt(long generation) {
        return () -> generation;
    }

    /**
     * 窗口一 (原地重投): 回调 ioFailed 判 REQUEUE_DIRTY 后、submitIo 的 enterIoPending 之前挂起, 主线程在窗口内
     * 处理一次 ChunkMap.save。
     *
     * <p>判定标准: 把 ioFailed 的 REQUEUE 改回写 phase=DIRTY -> 窗口内 isInFlight 为 false 且 trySnapshot 成功
     * (主线程开新周期), 第一组断言即挂。
     */
    @Test
    void requeue_in_place_retry_keeps_io_pending_so_main_thread_registers_relay_instead_of_new_cycle() {
        ChunkSaveState state = inFlightAtGenerationOne();
        long cycleOfGenerationOne = state.slot().inFlightCycleSeq();

        // 在飞期间编辑 (ChunkAccessMixin.setUnsaved -> markDirty): phase 非 CLEAN, 只推 generation。
        state.markDirty();
        assertEquals(2L, state.generation());

        // 回调: IO 失败, 未超预算 -> REQUEUE_DIRTY。窗口开点。
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, state.ioFailed(MAX_RETRIES));
        assertEquals(ChunkSaveState.Phase.IO_PENDING, state.phase(),
                "原地重投全程不发布瞬态 DIRTY, phase 保持 IO_PENDING");
        assertTrue(state.isInFlight(), "窗口内仍是在飞态: 回调正要原地重投, 它的下一个终态仍会取槽");
        assertEquals(ChunkSaveState.DrainOwner.IN_FLIGHT, state.drainOwner(), "REQUEUE 不清 drainOwner");
        assertFalse(state.lastTransitionClearedMustDrain(), "REQUEUE 不是终态, 调用方不得 dec gauge");

        // 主线程在窗口内: 新周期路径被挡住 (trySnapshot 只接受 DIRTY), 走碰撞分支登记接力。
        assertFalse(state.trySnapshot(), "窗口内主线程不得开新周期");
        assertEquals(1L, state.inFlightGeneration(), "inFlightGeneration 未被新周期覆盖");
        CapturedSnapshot latest = capturedAt(state.generation());
        assertFalse(state.beginPendingSnapshot(latest), "drainOwner 已是 IN_FLIGHT, 碰撞登记无需补 inc gauge");
        assertNull(state.publishPendingSnapshot(), "在飞消费者存在, 正常发布 READY 等回调消费");

        // 回调: 原地重投 (submitIo)。复用同一代 tag, 周期身份不变。
        state.enterIoPending();
        assertEquals(1L, state.inFlightGeneration());
        assertEquals(cycleOfGenerationOne, state.slot().inFlightCycleSeq(), "原地重投不是新周期");

        // 重投落地: generation(2) != inFlightGeneration(1) -> REQUEUE, 同一 CAS 取走接力并进接力周期。
        ChunkSaveState.LandResult retryLanded = state.landAndTake(true);
        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, retryLanded.outcome(),
                "重投落地必判 REQUEUE_DIRTY, 不因 inFlightGeneration 被覆盖误判 CLEAN_LANDED");
        assertSame(latest, retryLanded.relayPending(), "取走的是主线程在窗口内登记的唯一接力");
        assertEquals(ChunkSaveState.Phase.SERIALIZING, state.phase());
        assertEquals(2L, state.inFlightGeneration());
        assertEquals(ChunkSaveState.DrainOwner.RELAY, state.drainOwner());

        // 接力落地: 最新代落盘, 终态唯一清 drainOwner。
        ChunkSaveState.LandResult relayLanded = state.landAndTake(true);
        assertEquals(ChunkSaveState.IoOutcome.CLEAN_LANDED, relayLanded.outcome());
        assertTrue(state.lastTransitionClearedMustDrain(), "接力终态唯一一次清 drainOwner, 调用方 dec gauge 一次");
        assertEquals(ChunkSaveState.Phase.CLEAN, state.phase());
        assertEquals(0, state.retryCount(), "CLEAN_LANDED 归零重试预算");
    }

    /**
     * 窗口一的边界与随机化: 随机 maxRetries 与失败次数。预算内的每一次失败 (含恰好等于 maxRetries 的最后一次)
     * 都必须保持在飞, 超出预算的那一次才发布 FAILED 并清 drainOwner。
     */
    @Test
    void every_retry_within_budget_stays_in_flight_and_only_the_exhausting_failure_publishes_failed() {
        long seed = new Random().nextLong();
        Random random = new Random(seed);
        for (int round = 0; round < 200; round++) {
            int maxRetries = random.nextInt(11);
            ChunkSaveState state = inFlightAtGenerationOne();
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, state.ioFailed(maxRetries),
                        "seed=" + seed + " maxRetries=" + maxRetries + " attempt=" + attempt);
                assertTrue(state.isInFlight(),
                        "seed=" + seed + " maxRetries=" + maxRetries + " attempt=" + attempt + ": 预算内重投必须保持在飞");
                assertFalse(state.trySnapshot(),
                        "seed=" + seed + " maxRetries=" + maxRetries + " attempt=" + attempt + ": 主线程不得开新周期");
                state.enterIoPending();
            }
            assertEquals(ChunkSaveState.IoOutcome.FAILED_TERMINAL, state.ioFailed(maxRetries),
                    "seed=" + seed + " maxRetries=" + maxRetries);
            assertEquals(ChunkSaveState.Phase.FAILED, state.phase(), "seed=" + seed);
            assertEquals(ChunkSaveState.DrainOwner.NONE, state.drainOwner(), "seed=" + seed);
            assertTrue(state.lastTransitionClearedMustDrain(), "seed=" + seed + ": 终态真清了 drainOwner, 调用方 dec 一次");

            // 上一次终态把 lastTransitionClearedMustDrain 留成 true; 回退重新接管后再失败一次 (预算已归零, 必是 REQUEUE),
            // REQUEUE 必须把它复位为 false, 否则回调会据陈旧值多 dec 一次 gauge。
            state.resetAfterFallback();
            state.markDirty();
            assertTrue(state.trySnapshot(), "seed=" + seed);
            state.enterSerializing();
            state.enterIoPending();
            assertTrue(state.tryMarkMustDrain(), "seed=" + seed);
            if (maxRetries > 0) {
                assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, state.ioFailed(maxRetries), "seed=" + seed);
                assertFalse(state.lastTransitionClearedMustDrain(),
                        "seed=" + seed + " maxRetries=" + maxRetries + ": REQUEUE 必须复位上一次终态留下的 true");
            }
        }
    }

    /**
     * 窗口二 (接力重投): 碰撞登记的 READY 接力被在飞那代落地取走时, 取走与进接力周期必须在同一 CAS 内完成。
     *
     * <p>判定标准: 让 landAndTake 的 READY 分支无视 canReoffer 一律写 phase=DIRTY (旧行为: 由调用方随后另一次
     * CAS 调 reenterSerializingForPending) -> 本 CAS 之后 isInFlight 为 false 且 trySnapshot 成功, 断言即挂。
     */
    @Test
    void relay_take_enters_relay_cycle_in_the_same_cas_without_publishing_dirty() {
        ChunkSaveState state = inFlightAtGenerationOne();
        state.markDirty();
        CapturedSnapshot latest = capturedAt(state.generation());
        assertFalse(state.beginPendingSnapshot(latest));
        assertNull(state.publishPendingSnapshot());
        long cycleBeforeLand = state.slot().inFlightCycleSeq();

        ChunkSaveState.LandResult landed = state.landAndTake(true);

        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, landed.outcome());
        assertSame(latest, landed.relayPending());
        // 碰撞登记的纯 capture 不清 isUnsaved, 主线程此刻若处理 ChunkMap.save 只看 phase: 必须仍是在飞态。
        assertEquals(ChunkSaveState.Phase.SERIALIZING, state.phase(), "取走接力的同一 CAS 直接进接力周期");
        assertTrue(state.isInFlight());
        assertFalse(state.trySnapshot(), "主线程不得开新周期与接力双在飞");
        assertEquals(2L, state.inFlightGeneration(), "锁到接力快照自己的代");
        assertEquals(ChunkSaveState.DrainOwner.RELAY, state.drainOwner(), "接力链在途持有 drain");
        assertEquals(cycleBeforeLand + 1, state.slot().inFlightCycleSeq(), "接力是一个新周期, 恰好分配一个新序号");
        assertEquals(ChunkSaveState.PendingKind.NONE, state.slot().pendingKind(), "槽已取空");
        assertEquals(-1L, state.slot().missedCycle(), "取走 READY 不留 missed");
        assertFalse(state.lastTransitionClearedMustDrain(), "REQUEUE 进接力不是终态, 不 dec gauge");
    }

    /** 与经主线程自踢路径 reenterSerializingForPending 进入的接力周期, 终值字段逐一相同。 */
    @Test
    void atomic_relay_entry_matches_the_word_produced_by_reenter_serializing_for_pending() {
        ChunkSaveState atomic = inFlightAtGenerationOne();
        ChunkSaveState reference = inFlightAtGenerationOne();
        for (ChunkSaveState s : new ChunkSaveState[] {atomic, reference}) {
            s.markDirty();
            s.markDirty();
            s.beginPendingSnapshot(capturedAt(s.generation()));
            s.publishPendingSnapshot();
        }
        atomic.landAndTake(true);
        ChunkSaveState.LandResult legacy = reference.landAndTake(false);
        reference.reenterSerializingForPending(legacy.relayPending().capturedGeneration());

        ChunkSaveState.SlotWord a = atomic.slot();
        ChunkSaveState.SlotWord r = reference.slot();
        assertEquals(r.phase(), a.phase());
        assertEquals(r.inFlightGeneration(), a.inFlightGeneration());
        assertEquals(r.inFlightCycleSeq(), a.inFlightCycleSeq());
        assertEquals(r.pendingKind(), a.pendingKind());
        assertEquals(r.missedCycle(), a.missedCycle());
        assertEquals(r.drainOwner(), a.drainOwner());
        assertEquals(r.pendingNoInFlightConsumer(), a.pendingNoInFlightConsumer());
    }

    /** 无重投 sink (canReoffer=false) 时回调取走后即终态退出, 保留置 DIRTY、drainOwner 不动的原语义。 */
    @Test
    void relay_take_without_reoffer_sink_keeps_dirty_semantics() {
        ChunkSaveState state = inFlightAtGenerationOne();
        state.markDirty();
        CapturedSnapshot latest = capturedAt(state.generation());
        state.beginPendingSnapshot(latest);
        state.publishPendingSnapshot();

        ChunkSaveState.LandResult landed = state.landAndTake(false);

        assertSame(latest, landed.relayPending());
        assertEquals(ChunkSaveState.Phase.DIRTY, state.phase());
        assertEquals(1L, state.inFlightGeneration(), "不进接力周期");
        assertEquals(ChunkSaveState.DrainOwner.IN_FLIGHT, state.drainOwner());
    }

    /** 非 READY 槽 (PREPARING / NONE) 时 canReoffer 不起作用: 回调标本周期 missed 后终态退出, 发布 DIRTY。 */
    @Test
    void can_reoffer_has_no_effect_when_slot_is_not_ready() {
        ChunkSaveState preparing = inFlightAtGenerationOne();
        preparing.markDirty();
        preparing.beginPendingSnapshot(capturedAt(preparing.generation()));
        long cycle = preparing.slot().inFlightCycleSeq();
        ChunkSaveState.LandResult missedPreparing = preparing.landAndTake(true);
        assertNull(missedPreparing.relayPending(), "PREPARING 未就绪, 不取走");
        assertEquals(ChunkSaveState.Phase.DIRTY, preparing.phase());
        assertEquals(cycle, preparing.slot().missedCycle(), "标本周期 missed 交还主线程 publish 自踢");
        assertEquals(cycle, preparing.slot().inFlightCycleSeq(), "不分配接力周期");

        ChunkSaveState empty = inFlightAtGenerationOne();
        empty.markDirty();
        long emptyCycle = empty.slot().inFlightCycleSeq();
        ChunkSaveState.LandResult missedEmpty = empty.landAndTake(true);
        assertNull(missedEmpty.relayPending());
        assertEquals(ChunkSaveState.Phase.DIRTY, empty.phase());
        assertEquals(emptyCycle, empty.slot().missedCycle());
    }

    /**
     * 安全网发布真终态 DIRTY: task 已死 (onUnhandledError) 时 ioFailed 的 REQUEUE 不再推 phase, 由
     * markNoInFlightDirty 推; 它只动 phase, 不碰接力槽与 drainOwner。
     */
    @Test
    void mark_no_in_flight_dirty_publishes_dirty_without_touching_slot_or_drain_owner() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        assertTrue(state.tryMarkMustDrain());
        state.markDirty();
        CapturedSnapshot latest = capturedAt(state.generation());
        state.beginPendingSnapshot(latest);

        assertEquals(ChunkSaveState.IoOutcome.REQUEUE_DIRTY, state.ioFailed(MAX_RETRIES));
        assertEquals(ChunkSaveState.Phase.SERIALIZING, state.phase(), "ioFailed 的 REQUEUE 不碰 phase");

        state.markNoInFlightDirty();

        assertEquals(ChunkSaveState.Phase.DIRTY, state.phase());
        assertTrue(state.trySnapshot(), "下一次 ChunkMap.save 可走常规路径重新捕获");
        assertEquals(ChunkSaveState.PendingKind.PREPARING, state.slot().pendingKind(), "接力槽不动");
        assertEquals(ChunkSaveState.DrainOwner.IN_FLIGHT, state.drainOwner(), "drainOwner 不动");
        assertEquals(1, state.retryCount(), "不碰重试预算");
    }
}
