package com.shinoyuki.betterautosave.core.worker;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import org.slf4j.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class SerializationWorker implements Runnable {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private final BlockingQueue<SaveTask> queue;
    private final SaveMetrics metrics;
    private final String name;
    private volatile boolean running = true;
    private volatile boolean drainedAfterStop;

    public SerializationWorker(String name, BlockingQueue<SaveTask> queue, SaveMetrics metrics) {
        this.name = name;
        this.queue = queue;
        this.metrics = metrics;
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
        ServerThreadAssert.markCurrentThreadAsWorker();
        LOGGER.info("Worker {} started", name);
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
                    LOGGER.error("Worker {} task {} threw, escalating to task fallback", name, task.taskName(), t);
                    try {
                        task.onUnhandledError(t);
                    } catch (Throwable inner) {
                        LOGGER.error("Task fallback for {} itself threw", task.taskName(), inner);
                    }
                }
            }
            drainedAfterStop = true;
        } finally {
            ServerThreadAssert.unmarkCurrentThreadAsWorker();
            LOGGER.info("Worker {} stopped, queue size {}", name, queue.size());
        }
    }
}
