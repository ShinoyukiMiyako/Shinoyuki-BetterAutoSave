package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.state.ChunkSaveState;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * pending 接力链补派发 ChunkDataEvent.Save 回归.
 *
 * <p>现场: BAS 把 Forge 注入在 vanilla {@code ChunkMap.save} 体内的 {@code ChunkDataEvent.Save} 显式
 * 复刻到 {@link SnapshotPipeline#captureAndDispatchChunk}, 这是唯一派发点。在飞碰撞 + 卸载触发的 pending
 * 接力链 (mixin 碰撞分支登记 pending -> 在飞代落地 REQUEUE_DIRTY -> worker 重投) 完全绕开该派发点, 故接力
 * 落盘的"碰撞后最新代" tag 从未经过 Forge listener, 依赖该事件向 tag 写增量的第三方 mod 增量永久静默丢失。
 *
 * <p>派发逻辑收口到 {@link ChunkCaptureProcedure#dispatchSaveEvent}, mixin 碰撞分支在 capturePending
 * 成功后、registerPendingSnapshot 前用 pending 当代 tag 在主线程派发一次, 与常规路径同序。
 *
 * <p>测试技法: bare JUnit 下 Forge eventbus 注册 listener 需事件类无参构造 (ModLauncher 字节码变换才补),
 * 无法直接 post 真总线。故用 {@link ChunkCaptureProcedure#swapSaveEventDispatcher} 注入记录性 dispatcher,
 * 验证 mode 守卫 + tag 选择 + 派发次数 (这些正是 dispatchSaveEvent 内、真总线之外的全部业务逻辑)。
 *
 * <p>判定标准: 删 mixin 碰撞分支的 dispatchSaveEvent 调用 -> 接力代 tag 不再被派发 ->
 * relay_dispatches_latest_generation_tag 的"最新代被派发"断言挂。删 DISABLED 守卫 -> disabled 用例挂。
 */
class ChunkRelaySaveEventTest {

    private static ResourceKey<Level> DIM;
    private ChunkCaptureProcedure.SaveEventDispatcher savedDispatcher;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        DIM = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
    }

    private record Dispatched(CompoundTag tag) {
    }

    private final List<Dispatched> dispatched = new ArrayList<>();

    @BeforeEach
    void installRecorder() {
        dispatched.clear();
        savedDispatcher = ChunkCaptureProcedure.swapSaveEventDispatcher(
                (chunk, level, eventTag) -> dispatched.add(new Dispatched(eventTag)));
    }

    @AfterEach
    void restoreDispatcher() {
        ChunkCaptureProcedure.swapSaveEventDispatcher(savedDispatcher);
    }

    private ChunkSnapshot snapshotForGeneration(ChunkSaveState state, long generation, ConfigSpec.EventCompatMode mode) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("gen", generation);
        return ChunkSnapshot.ofPrebuiltFullTag(new ChunkPos(3, -5), DIM, tag, generation, state, mode);
    }

    /**
     * 接力链 (PARTIAL / FULL 共此断言, 因 dispatchSaveEvent 的 tag 选择对 FULL 用 fullTag) 必须把碰撞后
     * 最新代 (gen=2) 的 tag 派发一次, 而非信任在飞旧代 (gen=1)。复刻 mixin 碰撞分支的真实调用序列。
     */
    @Test
    void relay_dispatches_latest_generation_tag() {
        ChunkSaveState state = new ChunkSaveState(new ChunkPos(3, -5).toLong(), "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();

        // 常规路径派发在飞代 (gen=1).
        ChunkSnapshot gen1 = snapshotForGeneration(state, 1L, ConfigSpec.EventCompatMode.FULL);
        ChunkCaptureProcedure.dispatchSaveEvent(null, null, gen1, ConfigSpec.EventCompatMode.FULL, metrics);

        // mixin 碰撞分支: 对最新代 (gen=2) 派发.
        ChunkSnapshot gen2 = snapshotForGeneration(state, 2L, ConfigSpec.EventCompatMode.FULL);
        ChunkCaptureProcedure.dispatchSaveEvent(null, null, gen2, ConfigSpec.EventCompatMode.FULL, metrics);

        assertEquals(2, dispatched.size(), "常规代 + 接力代各派发一次");
        assertEquals(1L, dispatched.get(0).tag().getLong("gen"), "首次派发的是在飞代 (gen=1)");
        assertEquals(2L, dispatched.get(1).tag().getLong("gen"),
                "接力链必须派发碰撞后最新代 (gen=2) tag, 而非旧代");
        assertSame(gen2.preBuiltFullTag(), dispatched.get(1).tag(),
                "接力派发的必须是 pending 快照自己的 tag 实例 (最新代)");
        assertEquals(2L, metrics.snapshot().eventDispatch().count(),
                "派发计数随每次 dispatchSaveEvent 累加");
    }

    /** PARTIAL 模式: 用 preBuiltCoreTag (无 sections) 派发, 接力代同样选 coreTag。 */
    @Test
    void partial_mode_dispatches_core_tag() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        CompoundTag coreTag = new CompoundTag();
        coreTag.putLong("gen", 7L);
        // PARTIAL: coreTag 非空, fullTag 为 null -> dispatchSaveEvent 选 coreTag.
        ChunkSnapshot snap = new ChunkSnapshot(new ChunkPos(0, 0), DIM, 0, 0, 0L, 0L, "", null, null, null, 0, 0,
                false, java.util.Map.of(), null, null, null, null, null, null, java.util.Map.of(), java.util.Map.of(),
                null, 7L, state, coreTag, null, ConfigSpec.EventCompatMode.PARTIAL);

        ChunkCaptureProcedure.dispatchSaveEvent(null, null, snap, ConfigSpec.EventCompatMode.PARTIAL, metrics);

        assertEquals(1, dispatched.size());
        assertSame(coreTag, dispatched.get(0).tag(), "PARTIAL 必须派发 preBuiltCoreTag");
    }

    /** DISABLED 模式: 接力链零派发 (与常规路径对称)。 */
    @Test
    void disabled_mode_dispatches_nothing() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkSnapshot snap = snapshotForGeneration(state, 1L, ConfigSpec.EventCompatMode.DISABLED);

        ChunkCaptureProcedure.dispatchSaveEvent(null, null, snap, ConfigSpec.EventCompatMode.DISABLED, metrics);

        assertEquals(0, dispatched.size(), "DISABLED 模式接力链必须零派发");
    }

    /**
     * 派发抛 (第三方 listener 故障) 必须原样冒泡, 让 mixin 碰撞分支的 catch 走"不登记 pending + 退信任
     * 在飞旧代"降级。本测试断言异常透出 dispatchSaveEvent (不被吞)。
     */
    @Test
    void dispatch_failure_propagates_for_mixin_fallback() {
        ChunkSaveState state = new ChunkSaveState(0L, "minecraft:overworld", 1L);
        SaveMetrics metrics = new SaveMetrics();
        ChunkSnapshot snap = snapshotForGeneration(state, 1L, ConfigSpec.EventCompatMode.FULL);

        ChunkCaptureProcedure.swapSaveEventDispatcher((chunk, level, eventTag) -> {
            throw new RuntimeException("third-party listener boom");
        });

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ChunkCaptureProcedure.dispatchSaveEvent(null, null, snap, ConfigSpec.EventCompatMode.FULL, metrics));
        assertEquals("third-party listener boom", ex.getMessage(),
                "派发抛必须原样冒泡, 由 mixin catch 降级 (不登记 pending, 退信任在飞旧代)");
    }
}
