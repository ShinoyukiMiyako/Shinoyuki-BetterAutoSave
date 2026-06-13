package com.shinoyuki.betterautosave.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * BAS chunk 保存路径外部订阅接口. 注册到 {@link SaveListenerRegistry} 后,
 * BAS 在 chunk NBT 成功落盘 (CLEAN_LANDED 或 REQUEUE_DIRTY) 时回调.
 *
 * <p><b>线程模型</b>: 回调在 BAS worker 线程上下文 (IOWorker future 的回调线程).
 * Listener 实现必须线程安全; 不允许阻塞 (堆积 IO 会让 BAS worker pool 饿死).
 * 重活请入 listener 自己的 worker queue.
 *
 * <p><b>异常处理</b>: listener 抛出的异常被 BAS catch + log, 不会传播到 BAS 路径.
 * Listener 自己应监控异常率, 必要时进入 degraded mode.
 *
 * <p><b>tag 生命周期</b>: 传入的 {@link CompoundTag} 是已落盘的版本. 实现可读,
 * 但**不应保留对该 tag 的引用** — BAS 不保证 tag 在回调返回后不被改动 / 回收.
 * 如需保存, 调 {@link CompoundTag#copy()} 拷贝.
 *
 * <p>典型使用场景: BetterBackup 等下游 mod 订阅 chunk save 事件做内容寻址备份.
 */
@FunctionalInterface
public interface ChunkSaveListener {

    /**
     * BAS chunk save 成功落盘后回调.
     *
     * @param pos       chunk 坐标
     * @param dimension chunk 所在维度
     * @param tag       已写入 region file 的完整 chunk NBT (不应保留引用, 必要时 copy)
     */
    void onChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag);
}
