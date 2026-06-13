package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.worker.SaveTask;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SavedData 乐观清 dirty 的顺序确定性回归.
 *
 * <p>现场: worker 是独立线程。若 mixin 把 {@code savedData.setDirty(false)} 放在
 * {@code SavedDataDispatch.enqueue} 之后, offer 把 task 交给 worker 后主线程还要再跑两行才清 dirty; 持续性 IO 故障下 worker 的
 * writeCompressed 同步快速抛 IOException 走 {@code SavedDataSaveTask} setDirty(true), 这次 true 可能先于
 * 主线程的 setDirty(false) 发生, last-writer-wins 主线程 false 胜出 -> 下周期 isDirty() gate 跳过 -> 丢一次
 * 重试。故 setDirty(false) 必须排在 enqueue 之前, 且 offer-fail 兜底写也失败时补 re-mark。
 *
 * <p>测试技法: 裸 JUnit 无 mixin agent (mixin 时序无法直测)。用自定义 queue 在 offer 内同步执行 failing
 * task (复刻 "worker 在主线程清 dirty 之前就失败置 dirty" 的最坏时序), 按 mixin 的真实两语句顺序驱动,
 * 断言返回后 dirty 仍为 true。
 */
class SavedDataDirtyOrderingTest {

    /** 记录 setDirty 调用序列的 SavedData 桩。dirty 初值 true (vanilla SavedData 默认 false, 这里显式置脏)。 */
    private static final class RecordingSavedData extends SavedData {
        private final List<Boolean> setDirtyCalls = new ArrayList<>();

        RecordingSavedData() {
            super.setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            return tag;
        }

        @Override
        public void setDirty(boolean dirty) {
            setDirtyCalls.add(dirty);
            super.setDirty(dirty);
        }
    }

    private SavedDataSnapshot snapshot(String name, File file, SavedData data, Set<String> inFlight) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", 1);
        return new SavedDataSnapshot(name, file, tag, data, new ConcurrentHashMap<>(), inFlight);
    }

    /**
     * 复刻 mixin 的真实两语句顺序 (setDirty(false) 先于 enqueue), 配 offer 内同步执行的 failing worker task,
     * 断言返回后 dirty 仍 true。判定标准: 把两语句换成 enqueue 先、setDirty(false) 后的次序,
     * worker 的 setDirty(true) 被随后的 setDirty(false) 覆盖, 最终 dirty=false, 末尾 isDirty 断言挂。
     */
    @Test
    void clear_dirty_before_enqueue_lets_worker_failure_win(@TempDir Path dir) throws Exception {
        Set<String> inFlight = ConcurrentHashMap.newKeySet();
        String name = "ordering";
        // 让目标父路径是普通文件, AtomicNbtWriter.createDirectories 抛 IOException -> SavedDataSaveTask 失败置 dirty.
        File blocker = dir.resolve("blocker").toFile();
        assertTrue(blocker.createNewFile());
        File file = new File(blocker, name + ".dat");

        RecordingSavedData data = new RecordingSavedData();
        assertTrue(data.isDirty(), "初始脏");
        SaveMetrics metrics = new SaveMetrics();
        assertTrue(inFlight.add(name));

        SavedDataSaveTask task = new SavedDataSaveTask(snapshot(name, file, data, inFlight), metrics);

        // offer 内同步跑 task.execute(): 复刻 worker 在主线程清 dirty 之前就失败置 dirty 的最坏时序。
        LinkedBlockingQueue<SaveTask> syncFailingQueue = new LinkedBlockingQueue<>() {
            @Override
            public boolean offer(SaveTask t) {
                // 同步执行: writeCompressed 抛 IOException 已被 SavedDataSaveTask 内部 catch + setDirty(true),
                // 不外泄; 仅意外的非受控异常会经此 rethrow 让测试可见 (不静默吞)。
                try {
                    t.execute();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return true;
            }
        };

        // mixin 真实顺序: 先乐观清 dirty, 再 enqueue (offer 内 worker 同步失败 re-mark dirty).
        data.setDirty(false);
        SavedDataDispatch.enqueue(syncFailingQueue, task, metrics);

        assertTrue(data.isDirty(),
                "顺序: 主线程 setDirty(false) 先于 worker setDirty(true), worker 失败置脏成为最后写 -> "
                        + "dirty 必须维持 true, 下周期 isDirty() gate 正确重试");
        assertEquals(1L, metrics.snapshot().savedDataFailed(), "worker IO 失败计数");
        assertFalse(inFlight.contains(name), "task finally 释放在途占位");
    }

    /**
     * 反向锚定: 显式跑相反序 (enqueue 先, setDirty(false) 后) 必得 dirty=false。本测试钉死
     * "顺序是因果" —— 颠倒两语句次序确实丢重试, 故这两行的相对顺序是 deletion-sensitive 的语义本体。
     */
    @Test
    void old_order_loses_retry_demonstrating_bug(@TempDir Path dir) throws Exception {
        Set<String> inFlight = ConcurrentHashMap.newKeySet();
        String name = "oldorder";
        File blocker = dir.resolve("blocker").toFile();
        assertTrue(blocker.createNewFile());
        File file = new File(blocker, name + ".dat");

        RecordingSavedData data = new RecordingSavedData();
        SaveMetrics metrics = new SaveMetrics();
        assertTrue(inFlight.add(name));
        SavedDataSaveTask task = new SavedDataSaveTask(snapshot(name, file, data, inFlight), metrics);

        LinkedBlockingQueue<SaveTask> syncFailingQueue = new LinkedBlockingQueue<>() {
            @Override
            public boolean offer(SaveTask t) {
                try {
                    t.execute();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return true;
            }
        };

        // 相反序: enqueue 先 (offer 内 worker 失败置 dirty=true), 主线程随后 setDirty(false) 覆盖。
        SavedDataDispatch.enqueue(syncFailingQueue, task, metrics);
        data.setDirty(false);

        assertFalse(data.isDirty(),
                "相反序下 worker setDirty(true) 被主线程随后的 setDirty(false) 覆盖 -> dirty=false 丢重试");
    }
}
