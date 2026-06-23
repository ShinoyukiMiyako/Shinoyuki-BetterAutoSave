package com.shinoyuki.betterautosave.core.load;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/**
 * 异步在飞加载限流 (v2.1 L2): 把同时提交到 load worker 的区块解析任务数限制在 {@code max} 以内, 从而把同一 tick
 * 内回交主线程的 replay+install 完成数压到 ~max。解决 v2.1 L1 多 worker 并行解码后"一批区块同时解析完、replay 全砸
 * 主线程一个 tick"导致的 Can't-keep-up 突发尖峰 (实测 4 worker view=32 飞行下达 6249ms)。
 *
 * <p><b>为何用异步信号量限"在飞数", 而非"主线程限速 drain 队列"</b>: 后者会死锁 —— 主线程 {@code managedBlock}
 * 同步等某个区块就绪时, 若该区块的 replay 卡在限速队列里 (不在 tick、不被 drain) 就永久 hang。本限流器让 replay 仍走
 * 原 {@code mainThreadExecutor}: 主线程 managedBlock 时照常 drain replay -> replay 完成 -> {@link #release()} 放行
 * 排队的 {@link #acquire()} -> 被等区块得以推进, 无环等待, 死锁安全。
 *
 * <p><b>许可不泄漏纪律</b>: 每个 {@code acquire()} 必须严格对应一次 {@code release()}, 且在 success 与 fallback
 * 两条完成路径都 release (调用方用 {@code whenComplete} 覆盖两路)。漏一次 release 即永久占一个名额 -> 在飞数虚高 ->
 * 最终 max 个名额全泄漏后所有加载排队饿死。
 *
 * <p>线程安全: {@code acquire}/{@code release} 由 worker 线程 (offer 前) 与主线程 (replay 完成 release) 并发调用,
 * 全部经 {@code synchronized(this)} 串行化 inFlight 与 waiters。锁内不做 IO、不完成 future (complete 移出锁外, 避免
 * 续段 inline 回调重入)。
 */
public final class LoadInFlightLimiter {

    private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);

    private final IntSupplier maxSupplier;
    private int inFlight;
    private final Queue<CompletableFuture<Void>> waiters = new ArrayDeque<>();

    /**
     * @param maxSupplier 动态读取当前并发上限 (读 volatile 配置 {@code BetterAutoSaveConfig.loadMaxInFlight()})。改
     *                    配置经 Config Watcher 热重载即时生效, 无需重启 —— 便于飞行压测中实时调泄洪阀甜点。
     */
    public LoadInFlightLimiter(IntSupplier maxSupplier) {
        this.maxSupplier = maxSupplier;
    }

    /**
     * 取一个在飞许可。名额未满则立即返回已完成 future (快路径, 共享单例不分配); 已满则入队返回未完成 future,
     * 待某个在飞加载 {@link #release()} 时按 FIFO 放行。调用方须 {@code acquire().thenComposeAsync(submit, mainExec)}。
     */
    public CompletableFuture<Void> acquire() {
        synchronized (this) {
            if (inFlight < Math.max(1, maxSupplier.getAsInt())) {
                inFlight++;
                return COMPLETED;
            }
            CompletableFuture<Void> waiter = new CompletableFuture<>();
            waiters.add(waiter);
            return waiter;
        }
    }

    /**
     * 归还一个在飞许可。有排队者则把名额直接转交最早排队者 (FIFO, inFlight 不变); 无排队者才真正减少在飞数。
     * waiter 的 {@code complete} 移到 synchronized 块外, 防其 thenCompose 续段在持锁时 inline 回调重入本类。
     */
    public void release() {
        CompletableFuture<Void> next;
        synchronized (this) {
            next = waiters.poll();
            if (next == null) {
                inFlight--;
                return;
            }
        }
        next.complete(null);
    }

    public synchronized int inFlight() {
        return inFlight;
    }

    public synchronized int queued() {
        return waiters.size();
    }

    public int max() {
        return Math.max(1, maxSupplier.getAsInt());
    }
}
