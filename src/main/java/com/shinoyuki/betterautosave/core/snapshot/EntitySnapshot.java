package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.state.EntitySaveState;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * v0.6 entity 路径 snapshot. 主线程 capture 时调每个 entity 的
 * {@code Entity.save(CompoundTag)} 把状态固化为 ListTag, worker 线程
 * 仅做 outer tag 包装 (Entities / Position / DataVersion) + 调
 * {@code IOWorker.store} 提交 IO.
 *
 * <p>与 {@link ChunkSnapshot} 设计原则一致: 主线程做不能并发的工作
 * (entity.save 内部读 AI / 库存 / 位置非线程安全), worker 做纯数据封装
 * 与 IO 提交.
 *
 * @param pos                   实体所在 chunk 坐标
 * @param dimension             所在维度 ResourceKey
 * @param dataVersion           当前 minecraft data version (vanilla
 *                              EntityStorage 写盘时调 NbtUtils.addCurrentDataVersion
 *                              拿到, BAS capture 时记录用于 worker 拼 tag)
 * @param entitiesNbt           主线程预序列化结果, 每个 Entity 一个 CompoundTag
 *                              元素. 已过滤 entity.save() 返回 false 的 entity
 *                              (vanilla 行为一致)
 * @param capturedGeneration    enterSerializing 返回的 generation, IO 完成
 *                              回调用以判定 CLEAN_LANDED 还是 REQUEUE_DIRTY
 * @param state                 反引用 EntitySaveState 用以推进状态机
 */
public record EntitySnapshot(
        ChunkPos pos,
        ResourceKey<Level> dimension,
        int dataVersion,
        ListTag entitiesNbt,
        long capturedGeneration,
        EntitySaveState state
) {
}
