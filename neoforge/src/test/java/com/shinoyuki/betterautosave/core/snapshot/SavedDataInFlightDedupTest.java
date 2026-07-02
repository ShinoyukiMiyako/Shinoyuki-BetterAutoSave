package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SavedData 在途文件名去重单测.
 *
 * <p>现场: savedDataWorkerThreads 可配 1-4, 同名 .dat 被多 worker 并发写交错损坏.
 * mixin 入队前 add(name), worker task finally remove(name): 同名在上轮还没写完时
 * 重复入队被去重集合挡下.
 *
 * <p>本测试用真实 SavedDataSaveTask.execute (真实文件 IO + 真实在途集合) 验证:
 * task 跑完后 finally 释放占位. 判定标准: 删掉 SavedDataSaveTask.execute 的 finally
 * releaseInFlight, "task 跑完释放占位" 断言挂 (第二次 add 永远失败).
 */
class SavedDataInFlightDedupTest {

    /** 最小 SavedData 桩, 仅供 IOException 路径的 setDirty 调用 (无 registry 依赖). */
    private static final class StubSavedData extends SavedData {
        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            return tag;
        }
    }

    private SavedDataSnapshot snapshot(String name, File file, Set<String> inFlight) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", 1);
        return new SavedDataSnapshot(name, file, tag, new StubSavedData(),
                new ConcurrentHashMap<>(), name, inFlight);
    }

    @Test
    void task_finally_releases_in_flight_slot_allowing_next_cycle(@TempDir Path dir) {
        Set<String> inFlight = ConcurrentHashMap.newKeySet();
        String name = "forced";
        File file = dir.resolve(name + ".dat").toFile();

        // 模拟 mixin gate: add 后 dispatch task.
        assertTrue(inFlight.add(name));
        SaveMetrics metrics = new SaveMetrics();
        // task 构造前 mixin 会 incInFlightSerializing, 这里对齐 execute 内 decInFlightSerializing 配平.
        metrics.incInFlightSerializing();

        SavedDataSaveTask task = new SavedDataSaveTask(snapshot(name, file, inFlight), metrics);
        task.execute();

        // task 跑完 (写盘成功) → finally 释放占位 → 下个周期同名可重新 dispatch.
        assertFalse(inFlight.contains(name),
                "task 执行完毕必须经 finally 释放在途占位");
        assertTrue(inFlight.add(name),
                "占位释放后, 下个 autosave 周期同名文件必须能重新入队");
        assertTrue(file.exists(), "task 应已把文件原子写到盘");

        SaveMetrics.Snapshot s = metrics.snapshot();
        assertEquals(1L, s.savedDataCompleted(), "成功写盘必须计 savedDataCompleted");
    }

    @Test
    void task_releases_in_flight_slot_even_on_io_failure(@TempDir Path dir) throws Exception {
        Set<String> inFlight = ConcurrentHashMap.newKeySet();
        String name = "broken";
        // 让目标的父路径是一个普通文件而非目录, AtomicNbtWriter.createDirectories 抛 IOException.
        File blocker = dir.resolve("blocker").toFile();
        assertTrue(blocker.createNewFile());
        File file = new File(blocker, name + ".dat");

        assertTrue(inFlight.add(name));
        SaveMetrics metrics = new SaveMetrics();
        metrics.incInFlightSerializing();

        SavedDataSaveTask task = new SavedDataSaveTask(snapshot(name, file, inFlight), metrics);
        task.execute();

        // IO 失败也必须经 finally 释放占位, 否则该文件名永久占位永不再 dispatch.
        assertFalse(inFlight.contains(name),
                "IO 失败路径也必须经 finally 释放在途占位");
        SaveMetrics.Snapshot s = metrics.snapshot();
        assertEquals(1L, s.savedDataFailed(), "写盘失败必须计 savedDataFailed");
        assertEquals(0L, s.savedDataCompleted());
    }

    /**
     * 多维度同名 SavedData 回归: savedDataInFlight 全服单份跨所有维度, 各维度各有一份同名 SavedData
     * (如每维度都有 "chunks") 落到不同文件. 去重 key 必须是目标文件完整路径, 否则下界/末地的同名会撞上
     * 主世界已在途的同名被整周期跳过 (落盘频率随维度数下降), 释放时又会连带误放.
     *
     * <p>判定标准: 把 mixin 去重 key 或 SavedDataSaveTask.releaseInFlight 从 file.getPath() 改回裸
     * fileName -> 两维度同名文件 key 相同: 第二个 add 失败 (下界被跳过) / 释放其一即连带释放另一, 断言挂.
     */
    @Test
    void same_name_saveddata_in_different_dimensions_tracked_independently(@TempDir Path dir) {
        Set<String> inFlight = ConcurrentHashMap.newKeySet();
        String name = "chunks"; // 各维度同名 .dat
        File overworld = dir.resolve("overworld").resolve(name + ".dat").toFile();
        File nether = dir.resolve("DIM-1").resolve(name + ".dat").toFile();
        String keyOverworld = overworld.getPath();
        String keyNether = nether.getPath();

        // 以文件路径为 key: 两维度同名文件必须都能独立进入在途 (裸名 key 下下界的 add 会失败被整周期跳过).
        assertTrue(inFlight.add(keyOverworld), "主世界 chunks 入在途");
        assertTrue(inFlight.add(keyNether),
                "下界同名 chunks 必须能独立入在途, 不被主世界的同名占位挡下");

        SaveMetrics metrics = new SaveMetrics();
        metrics.incInFlightSerializing();
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", 1);
        SavedDataSnapshot snap = new SavedDataSnapshot(name, overworld, tag, new StubSavedData(),
                new ConcurrentHashMap<>(), keyOverworld, inFlight);
        new SavedDataSaveTask(snap, metrics).execute();

        // 释放按 inFlightKey (路径): 只放主世界那份, 下界那份仍在途 (不被同名连带释放).
        assertFalse(inFlight.contains(keyOverworld), "主世界 task 跑完释放自己的在途占位");
        assertTrue(inFlight.contains(keyNether),
                "释放必须按文件路径, 不得因同名连带释放下界那份 (否则下界 .dat 会被并发写交错损坏)");
        assertTrue(overworld.exists(), "主世界 chunks 应已落盘");
    }
}
