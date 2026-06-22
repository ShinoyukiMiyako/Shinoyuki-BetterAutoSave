package com.shinoyuki.betterautosave.core.load;

import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * 异步加载 PARTIAL 模式下, worker 解析期间被 {@code ChunkSerializerLoadMixin} 的 {@code @Redirect} 拦截的
 * 主线程独占副作用 (PoiManager.checkConsistencyWithBlocks / LevelLightEngine.retainData|queueSectionData /
 * ChunkDataEvent.Load 派发) 的延迟动作收集器。
 *
 * <p>线程模型 (docs/ASYNC_LOAD_DESIGN.md 第四/六节):
 * <ul>
 *   <li>主线程 (ChunkMap.scheduleChunkLoad 的 thenApplyAsync(mainThreadExecutor) 续段) 进入
 *       {@code ChunkMapLoadMixin} 的 wrap, 创建本对象, 投递解析任务到 load worker, 然后阻塞 join。</li>
 *   <li>worker 线程在跑 vanilla {@code ChunkSerializer.read} 前经 {@link #beginCapture} 把本对象挂到
 *       {@link #CURRENT} ThreadLocal; read 内每次命中被 redirect 的 INVOKE, handler 见 {@link #current()}
 *       非空即把该次调用 (实参快照) 封进 Runnable {@link #add 排入} 本列表, <b>不</b>在 worker 执行副作用;
 *       read 返回后经 {@link #endCapture} 清 ThreadLocal。</li>
 *   <li>主线程 join 拿到 ProtoChunk 后调 {@link #replayOnMainThread} 在主线程顺序回放这些副作用 —— 不经
 *       mainThreadExecutor 再入队 (主线程此刻仍在 wrap 同步流里, 回队列会自死锁), 直接在当前主线程跑。
 *       回放保持 read 内原始调用顺序 (List 追加序 = 迭代序), 满足 POI/光照逐 section 的语义。</li>
 * </ul>
 *
 * <p>FULL / 未启用 / degraded 路径下 wrap 直接在主线程 {@code original.call} 跑 read, 不调 beginCapture,
 * {@link #current()} 为 null, redirect handler 走 inline 分支原样执行 —— 与 vanilla 零偏差。
 *
 * <p>本对象非线程安全且不可共享: 每次 read 一个实例, 仅被其 worker 线程写 (单线程 read 内顺序 add) +
 * 其后主线程读 (replay); worker 写与主线程读之间由 join 建立 happens-before, 故无需同步。
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
     * 经 redirect 排进若干 POI/光照副作用; 若不清空, 重试成功后会把上次失败尝试的残留副作用与本次的一并回放
     * (POI checkConsistency / 光照 queueSectionData 重复执行 = 脏写)。清空保证每次重试从干净的捕获列表开始,
     * 回放的副作用与最终成功那次 read 的调用序严格一一对应。
     *
     * <p>线程模型不变: 本对象仍只被其 worker 线程写 (重试与捕获同线程, 主线程 replay 由 join 建立 happens-before),
     * 故 clear 与 add 无竞态。
     */
    public void clearForRetry() {
        actions.clear();
    }

    /**
     * 在主线程顺序回放捕获的副作用。必须由 wrap 在 join 出 ProtoChunk 后、返回前调用, 保证这些 POI/光照/事件
     * 副作用在该 ProtoChunk 被 protoChunkToFullChunk 消费前已落到 ServerLevel 全局状态 (与 vanilla read 内
     * 同步完成等价)。assertOnServerThread 把 "回放误跑在非主线程" 显式炸出 (设计第六节断言护栏)。
     */
    public void replayOnMainThread(MinecraftServer server) {
        ServerThreadAssert.assertOnServerThread(server);
        for (Runnable action : actions) {
            action.run();
        }
    }
}
