package com.shinoyuki.betterautosave.core.worker;

import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializationWorkerTest {

    /**
     * 消费侧队列深度回写单测.
     *
     * <p>现场: SavedData 深度 gauge 仅在主线程 offer 后写峰值, worker 排空后无回写 (SavedData 不走
     * SaveScheduler 逐 tick drain 回写时机), gauge 长期停在峰值误导运维。因此 worker 每消费一个 task
     * 后经注入的 depthSink 回写 queue.size()。
     *
     * <p>判定标准: 删掉 run() 内 finally 的 depthSink.accept(queue.size()), drain 完成后 lastDepth
     * 停在初始峰值而非 0, 归零断言挂。
     */
    @Test
    void depth_sink_writes_back_queue_size_after_each_consume() throws InterruptedException {
        LinkedBlockingQueue<SaveTask> queue = new LinkedBlockingQueue<>();
        SaveMetrics metrics = new SaveMetrics();
        AtomicLong lastDepth = new AtomicLong(-1L);
        AtomicLong maxDepthSeen = new AtomicLong(-1L);

        // 先把 task 灌满队列再启动 worker, 保证 worker 启动时能观察到 backlog 峰值.
        AtomicInteger executed = new AtomicInteger();
        for (int i = 0; i < 6; i++) {
            queue.offer(new RecordingTask("d-" + i, executed::incrementAndGet));
        }
        SerializationWorker worker = new SerializationWorker("depth-1", queue, metrics, depth -> {
            lastDepth.set(depth);
            maxDepthSeen.accumulateAndGet(depth, Math::max);
        });
        Thread t = new Thread(worker, "depth-1");
        t.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (executed.get() < 6 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(6, executed.get(), "全部 task 必须被消费");

        worker.requestStop();
        t.join(2000);

        // drain 全部完成后最后一次回写必须是 0 (空队列), 而非停在主线程 offer 的峰值.
        assertEquals(0L, lastDepth.get(),
                "worker 排空后 depthSink 最后回写必须为 0, 反映真实积压");
        // 中途必然观察到 >0 的积压 (6 个 task 不可能一启动就全空), 证明回写跟踪了 drain 过程.
        assertTrue(maxDepthSeen.get() > 0L,
                "drain 过程中应观察到非 0 积压深度, got " + maxDepthSeen.get());
    }

    @Test
    void worker_executes_tasks_until_stop_request() throws InterruptedException {
        LinkedBlockingQueue<SaveTask> queue = new LinkedBlockingQueue<>();
        SaveMetrics metrics = new SaveMetrics();
        SerializationWorker worker = new SerializationWorker("test-1", queue, metrics);
        Thread t = new Thread(worker, "test-1");
        t.start();

        AtomicInteger executed = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            queue.offer(new RecordingTask("task-" + i, () -> executed.incrementAndGet()));
        }

        long deadline = System.currentTimeMillis() + 2000;
        while (executed.get() < 5 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(5, executed.get());

        worker.requestStop();
        t.join(2000);
        assertTrue(worker.isDrainedAfterStop(), "Worker should drain remaining queue before exit");
    }

    @Test
    void worker_routes_throwable_to_task_fallback_and_keeps_running() throws InterruptedException {
        LinkedBlockingQueue<SaveTask> queue = new LinkedBlockingQueue<>();
        SaveMetrics metrics = new SaveMetrics();
        SerializationWorker worker = new SerializationWorker("test-2", queue, metrics);
        Thread t = new Thread(worker, "test-2");
        t.start();

        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicInteger nextDone = new AtomicInteger();
        queue.offer(new RecordingTask("boom", () -> {
            throw new RuntimeException("intentional");
        }, caught));
        queue.offer(new RecordingTask("ok", nextDone::incrementAndGet));

        long deadline = System.currentTimeMillis() + 2000;
        while (nextDone.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertNotNull(caught.get(), "Failing task must receive its onUnhandledError callback");
        assertEquals("intentional", caught.get().getMessage());
        assertEquals(1, nextDone.get(), "Worker must keep running after a failed task");

        worker.requestStop();
        t.join(2000);
    }

    @Test
    void thread_factory_names_threads_and_marks_daemon_true() {
        WorkerThreadFactory factory = new WorkerThreadFactory("BetterAutoSave-Worker", null);
        Thread t1 = factory.newThread(() -> {
        });
        Thread t2 = factory.newThread(() -> {
        });
        assertEquals("BetterAutoSave-Worker-1", t1.getName());
        assertEquals("BetterAutoSave-Worker-2", t2.getName());
        // daemon: worker 绝不能钉死 JVM (否则别的 mod 关服抛异常跳过 BAS 收尾时, 服务器关不掉)。
        assertTrue(t1.isDaemon(), "worker 必须是 daemon, 否则会把服务器拖成关不掉");
        assertTrue(t1.getPriority() < Thread.NORM_PRIORITY);
    }

    private static final class RecordingTask implements SaveTask {
        private final String name;
        private final Runnable body;
        private final AtomicReference<Throwable> caught;

        RecordingTask(String name, Runnable body) {
            this(name, body, null);
        }

        RecordingTask(String name, Runnable body, AtomicReference<Throwable> caught) {
            this.name = name;
            this.body = body;
            this.caught = caught;
        }

        @Override
        public String taskName() {
            return name;
        }

        @Override
        public void execute() {
            body.run();
        }

        @Override
        public void onUnhandledError(Throwable cause) {
            if (caught != null) {
                caught.set(cause);
            }
        }
    }
}
