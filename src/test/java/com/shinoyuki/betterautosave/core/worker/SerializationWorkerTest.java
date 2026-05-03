package com.shinoyuki.betterautosave.core.worker;

import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializationWorkerTest {

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
    void thread_factory_names_threads_and_marks_daemon_false() {
        WorkerThreadFactory factory = new WorkerThreadFactory("BetterAutoSave-Worker", null);
        Thread t1 = factory.newThread(() -> {
        });
        Thread t2 = factory.newThread(() -> {
        });
        assertEquals("BetterAutoSave-Worker-1", t1.getName());
        assertEquals("BetterAutoSave-Worker-2", t2.getName());
        assertEquals(false, t1.isDaemon());
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
