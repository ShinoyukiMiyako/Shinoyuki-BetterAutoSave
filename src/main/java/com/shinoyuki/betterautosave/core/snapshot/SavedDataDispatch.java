package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;

import java.util.concurrent.BlockingQueue;

/**
 * v0.10.2 修复 (M5): SavedData 入队的 inFlightSerializing gauge 配平不变式的单一归属点.
 *
 * <p><b>背景</b>: DimensionDataStorageMixin 的 SavedData dispatch 旧实现把 incInFlightSerializing
 * 放在 offer 之前的 try 内, 但 dispatch catch 只 recordSavedDataFallback + remove 占位 + 同步兜底写,
 * 从不补 decInFlightSerializing. 一旦 offer 节点分配 (OOM) 或 task 构造抛, task 未入 worker, 其
 * execute 内的 decInFlightSerializing 永不触发 -> serializing gauge 永久 +1. SnapshotPipeline.drainPending
 * 退出条件含 inFlightSerializing()==0, 泄漏后该条件永假, 每次关服空耗满 shutdownTimeoutSeconds 并误报
 * "pending IO 未在超时内全部落盘", 运维误判有数据未落盘; Prometheus in_flight_serializing 永久虚高。
 *
 * <p><b>不变式</b>: 一个 SavedData task 的 inFlightSerializing 由"成功入队"建立, 由 worker task 的
 * {@code execute()} 首行 decInFlightSerializing 抵消。本方法保证: 仅当 offer 成功 (task 确实交给 worker)
 * 才让 inc 生效保留; offer 之前的构造或 offer 本身抛异常时, 抵消掉本次 inc 并把异常原样上抛, 由调用方
 * 走同步兜底写 (此时 gauge 已配平, 调用方不得再碰 serializing)。
 *
 * <p>抽到独立可注入入口 (queue 作参数) 让该 gauge 配平能单测: 注入一个 offer 抛 OOM/RuntimeException 的
 * 队列桩, 断言抛出后 inFlightSerializing()==0。
 */
public final class SavedDataDispatch {

    private SavedDataDispatch() {
    }

    /**
     * inc serializing 后入队. offer 前任何环节抛异常 (含 offer 自身) 都先补 dec 抵消本次 inc, 再原样
     * 上抛供调用方兜底; offer 成功则 inc 保留, 抵消责任移交 worker task execute。
     *
     * <p>v0.10.2 修复 (m-offer-false-contract): {@link BlockingQueue#offer} 有两种非入队信号 ——
     * 抛异常 (容量分配 OOM 等) 与返 false (有界队列已满)。生产队列当前是无界 LinkedBlockingQueue,
     * offer 永不返 false, 故无 live defect; 但本方法刻意写在抽象 BlockingQueue 接口上 (可注入桩供
     * 单测), 一旦未来换成有界队列 (ArrayBlockingQueue / 带容量的 LinkedBlockingQueue) 而本方法把
     * "offer 没抛" 等同于 "入队成功", 就会:
     * (a) 本周期数据无写盘且不重试 (调用方乐观清了 dirty);
     * (b) 永久泄漏 savedDataInFlight 占位 (该 .dat 此后每周期 add 失败被跳过, 进程内永不再保存);
     * (c) serializing gauge 永久 +1 毒化 drainPending 收敛与 Prometheus 指标。
     * 把 offer 返 false 当成与异常同路 —— 抛 IllegalStateException (JDK Queue.add 满队列的惯用信号),
     * 由 finally 配平 gauge, 由调用方既有 catch 走 remove 占位 + 同步兜底写。两种非入队信号收口到同一
     * 兜底路径, 杜绝有界队列换装时的静默回归。
     *
     * @throws RuntimeException / Error offer 阶段的原始异常, 或 offer 返 false 时的 IllegalStateException
     *         (两种情形 gauge 都已在抛出前配平)
     */
    public static void enqueue(BlockingQueue<SaveTask> queue, SaveTask task, SaveMetrics metrics) {
        metrics.incInFlightSerializing();
        boolean enqueued = false;
        try {
            if (!queue.offer(task)) {
                throw new IllegalStateException(
                        "SavedData worker queue rejected task (bounded queue full): " + task.taskName());
            }
            enqueued = true;
        } finally {
            if (!enqueued) {
                metrics.decInFlightSerializing();
            }
        }
    }
}
