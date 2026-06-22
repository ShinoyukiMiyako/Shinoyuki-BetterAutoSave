package com.shinoyuki.betterautosave.core.load;

import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * 异步加载 v2 中, worker read-stage 期间被 {@code ChunkSerializerLoadMixin} 的 {@code @Redirect} 拦截的主线程
 * 独占副作用 (PoiManager.checkConsistencyWithBlocks / LevelLightEngine.retainData|queueSectionData /
 * ChunkDataEvent.Load 派发) 的延迟动作收集器。
 *
 * <p>线程模型 (docs/ASYNC_LOAD_DESIGN.md 第四/六节, v2 future 链):
 * <ul>
 *   <li>{@link #CURRENT} ThreadLocal 仅用于 worker read 期间让 redirect handler 定位本次 read 的 sink ——
 *       它<b>不</b>跨线程传递列表 (从来不需要跨), 只在单个 worker 线程内活: {@code ChunkLoadTask} 跑 vanilla
 *       {@code ChunkSerializer.read} 前 {@link #beginCapture} 挂上, read 内每次命中被 redirect 的 INVOKE,
 *       handler 见 {@link #current()} 非空即把该次调用 (实参快照) 封进 Runnable {@link #add 排入} 本列表,
 *       <b>不</b>在 worker 执行副作用; read 返回前 {@link #drainCaptured} 取走列表快照, {@link #endCapture}
 *       清 ThreadLocal。</li>
 *   <li>跨 worker -> main 的交付靠 future 值: worker 把 {@link #drainCaptured} 的快照塞进 {@link LoadResult}
 *       完成 {@code ChunkLoadTask.result()}, 主线程 replay-stage 经 {@code CompletableFuture} 续段拿到该
 *       {@code LoadResult}, 调静态 {@link #replayOnMainThread} 在 mainThreadExecutor 上顺序回放。CompletableFuture
 *       的 complete -> 续段建立 happens-before, 主线程看到 worker 对快照列表的全部写, 无需额外同步。</li>
 * </ul>
 *
 * <p>v2 不再有 v1 "主线程在 wrap 同步流里 join + 直接回放" 的形态: v2 主线程发完续段就早返回, replay 经
 * mainThreadExecutor 调度回主线程跑, 已不在任何 wrap 同步流里, 故经 executor 回主线程不自死锁 (v1 那条 "回
 * 队列会自死锁" 的约束在 v2 反转)。replay 严格 happens-before {@code protoChunkToFullChunk} 消费 ProtoChunk
 * (replay 与 {@code Either.left(chunk)} 同在一个 mainThreadExecutor 任务内顺序执行, 该任务完成才完成
 * scheduleChunkLoad 返回的 EMPTY future, 下游 mapLeft 才可能开始), 与 vanilla "read 内同步完成 POI/光照后才返回
 * ProtoChunk" 等价。
 *
 * <p>FULL / 未启用 / degraded 路径下 redirect handler 的 {@link #current()} 为 null (从未 beginCapture),
 * 走 inline 分支原样在主线程执行 —— 与 vanilla 零偏差。
 *
 * <p>本对象非线程安全且不可共享: 每次 read 一个新实例 (worker 线程私有), 仅被其 worker 线程在 read 内顺序
 * 写 + 同线程 drainCaptured 读走; 列表所有权随 drainCaptured 移交 {@link LoadResult}, 此后不再被本对象触碰。
 */
public final class LoadDeferredActions {

    private static final ThreadLocal<LoadDeferredActions> CURRENT = new ThreadLocal<>();

    private final List<Runnable> actions = new ArrayList<>();

    /** worker 线程在跑 read 前挂载, read 内的 redirect handler 据此判定 "正在 off-thread 捕获"。 */
    public static void beginCapture(LoadDeferredActions sink) {
        CURRENT.set(sink);
    }

    public static void endCapture() {
        CURRENT.remove();
    }

    /** 当前线程的捕获目标; 主线程 (FULL/vanilla) 路径恒为 null -> redirect handler 走 inline。 */
    public static LoadDeferredActions current() {
        return CURRENT.get();
    }

    public void add(Runnable action) {
        actions.add(action);
    }

    /**
     * 丢弃已捕获的延迟动作。仅 {@code ChunkLoadTask} 在 worker 内重试 read 前调用: 上一次 read 抛之前可能已
     * 经 redirect 排进若干 POI/光照副作用; 若不清空, 重试成功后会把上次失败尝试的残留副作用与本次的一并带出
     * (POI checkConsistency / 光照 queueSectionData 重复回放 = 脏写)。清空保证每次重试从干净的捕获列表开始,
     * 带出的副作用与最终成功那次 read 的调用序严格一一对应。
     */
    public void clearForRetry() {
        actions.clear();
    }

    /**
     * 取走当前已捕获副作用的防御性快照并清空本列表。仅在 worker read <b>成功返回那一刻</b>由 {@code ChunkLoadTask}
     * 调一次 (随即封进 {@link LoadResult} 交给 future)。返回 {@code new ArrayList<>} 拷贝而非 {@code actions} 本体:
     * 把列表所有权从 sink 解耦给 {@link LoadResult}, sink 随 worker read 结束 GC 不影响已交付的快照。失败路径
     * (重试耗尽抛) 不构造 {@code LoadResult} 也不 drainCaptured, 半截 deferred 随 sink 一起被丢弃, 绝不外流到任何
     * future 值。
     */
    public List<Runnable> drainCaptured() {
        List<Runnable> snapshot = new ArrayList<>(actions);
        actions.clear();
        return snapshot;
    }

    /**
     * 在主线程顺序回放 worker read-stage 截走的副作用。由 {@code ChunkMapLoadMixin} 的 replay-stage 在
     * mainThreadExecutor 上、{@code Either.left(chunk)} 之前调用, 保证这些 POI/光照/事件副作用在该 ProtoChunk
     * 被 protoChunkToFullChunk 消费前已落到 ServerLevel 全局状态 (与 vanilla read 内同步完成等价)。回放保持 read
     * 内原始调用顺序 (List 追加序 = 迭代序), 满足 POI/光照逐 section 的语义。assertOnServerThread 把 "回放误跑在
     * 非主线程" 显式炸出 (设计第六节断言护栏); replay-stage 用 mainThreadExecutor 跑故必在主线程。
     */
    public static void replayOnMainThread(MinecraftServer server, List<Runnable> deferred) {
        ServerThreadAssert.assertOnServerThread(server);
        for (Runnable action : deferred) {
            action.run();
        }
    }
}
