package com.shinoyuki.betterautosave.api;

/**
 * BAS 管线降级状态订阅接口 (v0.10). 注册到 {@link SaveListenerRegistry} 后,
 * BAS 在 {@code SnapshotPipeline} 首次进入 degraded mode (worker 线程死亡 ->
 * 全部 save 回落 vanilla 同步路径) 时回调一次.
 *
 * <p><b>为什么只有 onDegraded 没有 onRecovered</b>: BAS 的 degraded 是单向闩锁
 * ({@code degraded.compareAndSet(false, true)}), 全库无复位路径. 一旦降级,
 * 唯一恢复语义是重启进程. 因此本接口只暴露 onDegraded, 不存在 onRecovered.
 *
 * <p><b>触发次数</b>: 闩锁保证 onDegraded 在单个 BAS 进程生命周期内对每个 listener
 * 最多触发一次 (compareAndSet 仅首次翻转返回 true 时 fire).
 *
 * <p><b>线程模型</b>: 回调在触发降级的线程上下文 (通常是 BAS worker 线程的
 * uncaught exception handler). Listener 实现必须线程安全且非阻塞, 重活入自己的
 * worker queue.
 *
 * <p><b>异常处理</b>: listener 抛出的异常被 BAS catch + log, 不传播回 BAS 路径.
 *
 * <p>典型使用场景: BetterBackup 订阅降级信号, 暂停快照创建并把 degraded 会话标志
 * 持久化, 下次启动补采降级窗口内变更的 chunk.
 */
@FunctionalInterface
public interface PipelineStateListener {

    /**
     * BAS 管线首次进入 degraded mode 时回调. 单进程内每 listener 最多一次.
     */
    void onDegraded();
}
