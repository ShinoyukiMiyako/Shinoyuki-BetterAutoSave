package com.shinoyuki.betterautosave.core.state;

/**
 * v0.6: 让 EntityStorage 实例携带 BAS 状态机. mixin 注入到 EntityStorage
 * 实现该接口, 提供 per-instance 的 ConcurrentHashMap&lt;ChunkPos, EntitySaveState&gt;
 * 访问 (因为 entity storage 是 per-level, 没有像 ChunkAccess 那样的 per-chunk
 * 实例附挂点).
 */
public interface EntitySaveStateAccess {

    EntitySaveState betterautosave$getOrCreateEntityState(long packedPos, String dimensionId, long enqueueSequence);

    EntitySaveState betterautosave$getEntityState(long packedPos);

    /**
     * v0.10.2 修复 (M4): 在 CLEAN_LANDED 终态后尝试从 per-level 状态 map 剔除该条目, 防止
     * betterautosave$entityStates 随服务器运行无界增长 (EntityStorage 是 per-level 单例, map
     * 生命周期 = level 生命周期 = 整个进程)。
     *
     * <p><b>仅在确认安全的终态剔除</b>: 仅当 map 中该 key 仍映射到 {@code expected} 这个对象本身
     * (object identity) 且其 phase 仍为 CLEAN 时才剔除。实现用 computeIfPresent 在 map bin 锁内
     * 原子完成 identity + phase 检查, 避免与主线程 getOrCreate/markDirty/trySnapshot 竞态把正在途
     * (SNAPSHOTTING/SERIALIZING/IO_PENDING) 或 DIRTY/FAILED 的条目误删。若主线程已开新一轮 save
     * 把同一对象推过 CLEAN, identity 仍匹配但 phase 非 CLEAN -> 保留, 留待下次 CLEAN_LANDED 再剔除
     * (best-effort, 漏剔无害, 因每次 clean 落盘都会再尝试, map 仍有界)。
     *
     * <p>剔除后该 chunk 若仍加载并再被编辑, 下次 storeEntities 的 getOrCreate 重建一个 CLEAN 状态,
     * 重建成本仅一次对象分配, 可忽略。
     */
    void betterautosave$evictEntityStateIfClean(long packedPos, EntitySaveState expected);
}
