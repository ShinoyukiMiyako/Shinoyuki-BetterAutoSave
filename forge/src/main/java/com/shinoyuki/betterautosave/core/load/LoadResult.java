package com.shinoyuki.betterautosave.core.load;

import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.List;

/**
 * 异步加载 v2 future 链中 worker read-stage 的产物, 跨线程交付给主线程 replay-stage 的不可变载体。
 *
 * <p>v2 与 v1 的根本差异在 deferred 列表的跨线程传递方式: v1 靠 "主线程预先 new 出列表 + 自己持引用 + join 等
 * 可见" 把 worker 写的副作用列表带回主线程; v2 主线程不阻塞、发完续段即返回, 不再预持任何引用等结果, 故 worker
 * 截走的 POI/光照/ChunkDataEvent.Load 延迟动作必须<b>作为 future 的返回值显式流出</b> —— worker read 跑完把
 * {@code chunk} 与 {@code deferred} (sink 的防御性快照) 一并封进本 record 完成 {@code ChunkLoadTask.result()},
 * 主线程 replay-stage 经 {@code CompletableFuture} 的 complete -> 续段 happens-before 看到 worker 的全部写。
 *
 * <p>{@code chunk} 类型取 {@link ChunkAccess} 与 vanilla {@code ChunkMap.scheduleChunkLoad} 续段里
 * {@code ChunkAccess chunkaccess = ChunkSerializer.read(...)} 的局部变量类型一致 (read 静态声明返回 ProtoChunk,
 * 运行期可能是 ImposterProtoChunk, 二者都是 ChunkAccess; 下游 protoChunkToFullChunk 再 {@code (ProtoChunk)} 回转
 * 安全)。
 *
 * <p>{@code deferred} 是 worker 截走副作用的快照拷贝 (见 {@link LoadDeferredActions#drainCaptured()}), 与 sink
 * 本身解耦: sink 随 worker read 结束 GC, 列表的所有权移交本 record -> replay-stage, 单消费者无共享。
 */
public record LoadResult(ChunkAccess chunk, List<Runnable> deferred) {
}
