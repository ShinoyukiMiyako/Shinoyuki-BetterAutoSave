package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SavedData 入队 inFlightSerializing gauge 配平单测 (Major 修复 M5).
 *
 * <p>现场: DimensionDataStorageMixin 旧 dispatch 把 incInFlightSerializing 放在 offer 之前的 try 内,
 * 但 dispatch catch 只 fallback 写 + remove 占位, 从不补 decInFlightSerializing. offer 节点分配 (OOM)
 * 或 task 构造抛异常时, task 未入 worker, 其 execute 内的 dec 永不触发 -> serializing gauge 永久 +1,
 * SnapshotPipeline.drainPending 退出条件 inFlightSerializing==0 永假, 每次关服空耗满超时误报未落盘。
 *
 * <p>判定标准 (删修复必挂): 把 SavedDataDispatch.enqueue 的 offer-失败补 dec (finally 内 decInFlightSerializing)
 * 删掉, offer 抛异常后 inFlightSerializing 停在 1, 第一个测试的归零断言挂。
 */
class SavedDataDispatchTest {

    private static final class DummyTask implements SaveTask {
        @Override
        public String taskName() {
            return "dummy";
        }

        @Override
        public void execute() {
        }

        @Override
        public void onUnhandledError(Throwable cause) {
        }
    }

    @Test
    void offer_throwing_compensates_serializing_gauge() {
        SaveMetrics metrics = new SaveMetrics();
        // 队列桩: offer 抛 OOM 模拟节点分配失败 (生产 dispatch catch 的真实触发条件).
        LinkedBlockingQueue<SaveTask> throwingQueue = new LinkedBlockingQueue<>() {
            @Override
            public boolean offer(SaveTask saveTask) {
                throw new OutOfMemoryError("queue node allocation failed");
            }
        };

        assertThrows(OutOfMemoryError.class,
                () -> SavedDataDispatch.enqueue(throwingQueue, new DummyTask(), metrics));

        // inc 在 offer 前发生, offer 抛后必须补 dec 抵消 -> gauge 归零, 不毒化 drainPending.
        assertEquals(0L, metrics.snapshot().inFlightSerializing(),
                "offer 抛异常后 inFlightSerializing 必须配平归零");
    }

    @Test
    void successful_offer_keeps_serializing_inc_for_worker_to_dec() {
        SaveMetrics metrics = new SaveMetrics();
        LinkedBlockingQueue<SaveTask> queue = new LinkedBlockingQueue<>();

        SavedDataDispatch.enqueue(queue, new DummyTask(), metrics);

        // offer 成功: inc 保留 (gauge=1), 抵消责任移交 worker execute 首行 dec.
        assertEquals(1L, metrics.snapshot().inFlightSerializing(),
                "offer 成功后 inc 必须保留待 worker dec");
        assertEquals(1, queue.size(), "task 必须已入队交给 worker");
    }
}
