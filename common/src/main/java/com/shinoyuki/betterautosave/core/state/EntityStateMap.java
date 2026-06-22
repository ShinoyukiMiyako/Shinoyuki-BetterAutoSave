package com.shinoyuki.betterautosave.core.state;

import java.util.concurrent.ConcurrentHashMap;

/**
 * per-level 的 packedPos -> {@link EntitySaveState} 状态表.
 *
 * <p>从 EntityStorageMixin 的裸 {@code @Unique ConcurrentHashMap} 字段抽出, 把"创建 / 查询 /
 * 安全剔除"三个语义收口到一个内聚、可单测的单元 (mixin 自身无法实例化单测)。
 *
 * <p><b>无界增长问题</b>: EntityStorage 是 per-level 单例, 该表生命周期 = level 生命周期 = 整个
 * 服务器进程。computeIfAbsent 只增不删会无界累积, 探索型服务器跑图数日后累积所有曾保存过的实体 chunk
 * 坐标条目 (10^5~10^6 量级), 每条含 4 个 Atomic* + 若干 field, 数十~数百 MB 常驻堆随运行时间线性
 * 增长 -> GC 压力上升甚至 OOM, 对以"降低 TPS 抖动"为卖点的存档优化 mod 是反向负担。
 *
 * <p><b>剔除安全性</b>: 见 {@link #evictIfClean} 文档 — 仅在确认安全的 CLEAN 终态、以 identity +
 * phase 原子校验剔除, best-effort (漏剔留待下次 CLEAN_LANDED 重试, 表仍有界)。
 */
public final class EntityStateMap {

    private final ConcurrentHashMap<Long, EntitySaveState> states = new ConcurrentHashMap<>();

    public EntitySaveState getOrCreate(long packedPos, String dimensionId, long enqueueSequence) {
        return states.computeIfAbsent(packedPos,
                k -> new EntitySaveState(k, dimensionId, enqueueSequence));
    }

    public EntitySaveState get(long packedPos) {
        return states.get(packedPos);
    }

    public int size() {
        return states.size();
    }

    /**
     * 在 CLEAN_LANDED 终态后尝试剔除该条目。
     *
     * <p>用 computeIfPresent 在 ConcurrentHashMap bin 锁内原子完成 identity + phase 检查与剔除,
     * 与并发的 getOrCreate (computeIfAbsent) 在同 key 上互斥。仅当 map 中该 key 仍映射到
     * {@code expected} 这个对象本身且其 phase 仍为 {@link EntitySaveState.Phase#CLEAN} 时才剔除:
     * <ul>
     *   <li>identity 不匹配 (已被替换) -> 保留, 不误删新对象;</li>
     *   <li>phase 非 CLEAN (主线程已开新一轮 save 把同一对象推到 DIRTY/SNAPSHOTTING/SERIALIZING/
     *       IO_PENDING, 或 sticky FAILED) -> 保留, 不误删在途条目, 留待下次 CLEAN_LANDED 再剔除。</li>
     * </ul>
     * best-effort: 漏剔无害 (每次 clean 落盘都会再尝试), 表仍有界。剔除后该 chunk 若仍加载并再被编辑,
     * 下次 storeEntities 的 getOrCreate 重建一个 CLEAN 状态, 重建成本仅一次对象分配。
     */
    public void evictIfClean(long packedPos, EntitySaveState expected) {
        states.computeIfPresent(packedPos, (k, current) -> {
            if (current == expected && current.phase() == EntitySaveState.Phase.CLEAN) {
                return null;
            }
            return current;
        });
    }
}
