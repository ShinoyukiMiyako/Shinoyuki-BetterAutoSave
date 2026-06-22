package com.shinoyuki.betterautosave.core.worker;

import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

public final class SerializationWorker implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger("BetterAutoSave");

    private final BlockingQueue<SaveTask> queue;
    private final SaveMetrics metrics;
    private final String name;
    // 消费侧队列深度回写. 主线程 offer 后写的是峰值, worker 排空后不回写则 gauge 长期停在峰值
    // (尤其 SavedData 无 SaveScheduler 逐 tick drain 回写时机). 每次 execute 后调
    // depthSink.accept(queue.size()) 让深度指标反映真实积压. 默认 no-op (不需要回写的通道).
    private final LongConsumer depthSink;
    private volatile boolean running = true;
    private volatile boolean drainedAfterStop;

    public SerializationWorker(String name, BlockingQueue<SaveTask> queue, SaveMetrics metrics) {
        this(name, queue, metrics, depth -> {
        });
    }

    public SerializationWorker(String name, BlockingQueue<SaveTask> queue, SaveMetrics metrics, LongConsumer depthSink) {
        this.name = name;
        this.queue = queue;
        this.metrics = metrics;
        this.depthSink = depthSink;
    }

    public String name() {
        return name;
    }

    public boolean isDrainedAfterStop() {
        return drainedAfterStop;
    }

    public void requestStop() {
        running = false;
    }

    @Override
    public void run() {
        WorkerThreadAssert.markCurrentThreadAsWorker();
        LOGGER.info("[BetterAutoSave] worker started: {}", name);
        try {
            while (running || !queue.isEmpty()) {
                SaveTask task;
                try {
                    task = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (task == null) {
                    continue;
                }
                long t0 = System.nanoTime();
                try {
                    task.execute();
                    metrics.recordWorkerBuildNs(System.nanoTime() - t0);
                } catch (Throwable t) {
                    metrics.recordWorkerBuildNs(System.nanoTime() - t0);
                    LOGGER.error("[BetterAutoSave] worker {} task {} threw, escalating to task fallback",
                            name, task.taskName(), t);
                    try {
                        task.onUnhandledError(t);
                    } catch (Throwable inner) {
                        LOGGER.error("[BetterAutoSave] task fallback for {} itself threw", task.taskName(), inner);
                    }
                } finally {
                    // 无论 execute 成功还是抛, 消费一个 task 后回写当前队列深度,
                    // 让深度 gauge 反映 drain 后的真实积压而非主线程 offer 时的峰值.
                    depthSink.accept(queue.size());
                }
            }
            drainedAfterStop = true;
        } finally {
            WorkerThreadAssert.unmarkCurrentThreadAsWorker();
            LOGGER.info("[BetterAutoSave] worker stopped: {} (queue={})", name, queue.size());
        }
    }
}
