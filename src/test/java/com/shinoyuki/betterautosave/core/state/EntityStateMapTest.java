package com.shinoyuki.betterautosave.core.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * EntityStateMap 安全剔除单测 (Major 修复 M4).
 *
 * <p>现场: EntityStorage 是 per-level 单例, 旧实现状态 map computeIfAbsent 只增不删, 跑图数日累积
 * 所有曾保存过的 entity chunk 坐标条目 -> 数十~数百 MB 常驻堆线性增长甚至 OOM。
 *
 * <p>判定标准 (删修复必挂): 把 evictIfClean 的 computeIfPresent 剔除逻辑删掉 (改为 no-op), 第一个
 * 测试的 "N 轮 save->clean 后 size 收敛到 1" 断言挂 (退回线性增长)。
 */
class EntityStateMapTest {

    /** 把一个状态推进到 CLEAN_LANDED 终态 (一次完整成功 save 周期). */
    private static void completeCleanCycle(EntitySaveState state) {
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();
        state.enterIoPending();
        EntitySaveState.IoOutcome outcome = state.ioCompletedSuccessfully();
        assertEquals(EntitySaveState.IoOutcome.CLEAN_LANDED, outcome);
    }

    @Test
    void clean_landed_eviction_keeps_map_bounded_across_many_cycles() {
        EntityStateMap map = new EntityStateMap();
        long packed = 12345L;

        // 同一 chunk 反复 save->clean->剔除->重建, 模拟长跑. 删剔除则每轮 getOrCreate 复用同一条目
        // size 恒为 1 看不出 -- 故下面用"剔除后 getOrCreate 得到全新对象"佐证剔除真的发生.
        EntitySaveState first = map.getOrCreate(packed, "minecraft:overworld", 1L);
        completeCleanCycle(first);
        map.evictIfClean(packed, first);
        assertEquals(0, map.size(), "CLEAN_LANDED 后该条目必须被剔除");

        EntitySaveState rebuilt = map.getOrCreate(packed, "minecraft:overworld", 2L);
        assertNotSame(first, rebuilt, "剔除后 getOrCreate 必须重建全新对象 (证明旧条目确实被删)");
        assertEquals(1, map.size());

        // 模拟海量不同 chunk 各 save 一次后卸载: 每个在其 CLEAN_LANDED 被剔除, map 不随历史线性增长.
        EntityStateMap massMap = new EntityStateMap();
        for (long pos = 0; pos < 5000; pos++) {
            EntitySaveState s = massMap.getOrCreate(pos, "minecraft:overworld", pos);
            completeCleanCycle(s);
            massMap.evictIfClean(pos, s);
        }
        assertEquals(0, massMap.size(),
                "5000 个一次性 chunk 各自 CLEAN_LANDED 剔除后 map 必须收敛到 0, 而非线性累积 5000");
    }

    @Test
    void eviction_skips_entry_that_started_new_inflight_save() {
        // 并发安全: CLEAN_LANDED 回调剔除前, 主线程已用同一对象开新一轮 save (phase 非 CLEAN).
        // identity 匹配但 phase 非 CLEAN -> 必须保留, 不能误删在途条目.
        EntityStateMap map = new EntityStateMap();
        long packed = 777L;
        EntitySaveState state = map.getOrCreate(packed, "minecraft:overworld", 1L);
        completeCleanCycle(state); // 上一轮 clean

        // 主线程开新一轮 save, 把同一对象推到 SERIALIZING (在途).
        state.markDirty();
        state.trySnapshot();
        state.enterSerializing();

        map.evictIfClean(packed, state);
        assertEquals(1, map.size(), "在途 (非 CLEAN) 条目必须保留不被误删");
        assertSame(state, map.get(packed), "保留的必须是同一在途对象");
    }

    @Test
    void eviction_skips_when_identity_mismatch() {
        // identity 不匹配 (条目已被替换为新对象): 不能按旧引用误删新对象.
        EntityStateMap map = new EntityStateMap();
        long packed = 999L;
        EntitySaveState stale = new EntitySaveState(packed, "minecraft:overworld", 1L);
        EntitySaveState current = map.getOrCreate(packed, "minecraft:overworld", 2L);

        map.evictIfClean(packed, stale);
        assertEquals(1, map.size(), "按陈旧引用剔除不得删掉当前条目");
        assertSame(current, map.get(packed));
    }
}
