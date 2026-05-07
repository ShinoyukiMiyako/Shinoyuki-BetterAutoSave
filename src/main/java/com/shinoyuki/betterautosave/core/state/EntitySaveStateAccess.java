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
}
