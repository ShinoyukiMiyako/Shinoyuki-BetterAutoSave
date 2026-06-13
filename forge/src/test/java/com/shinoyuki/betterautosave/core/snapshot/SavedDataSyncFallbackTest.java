package com.shinoyuki.betterautosave.core.snapshot;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SavedData 大文件同步 fallback 在途占位释放单测.
 *
 * <p>现场: DimensionDataStorageMixin 大文件 fallback 先 savedDataInFlight.add(name) 再
 * savedData.save(file). 之前 save(file) 无 finally, 抛 Throwable (vanilla SavedData.save(File)
 * 仅 catch IOException, mod 的 save(CompoundTag) 抛 RuntimeException 会透出) 则 remove(name)
 * 被跳过 → 该名称永久占位, 后续每周期 add 失败被跳过 → 该 SavedData 失去 BAS 增量保护.
 *
 * <p>判定标准: 删掉 SavedDataSyncFallback.syncWrite 的 finally (改成裸 save) →
 * "抛后占位已释放" 断言挂.
 */
class SavedDataSyncFallbackTest {

    /** save(File) 透出 RuntimeException 的 mod 桩 (模拟非幂等 / 损坏实现). */
    private static final class ThrowingSavedData extends SavedData {
        @Override
        public CompoundTag save(CompoundTag tag) {
            return tag;
        }

        @Override
        public void save(File file) {
            throw new IllegalStateException("mod save threw a non-IOException");
        }
    }

    /** save(File) 正常落盘的 mod 桩. */
    private static final class WritingSavedData extends SavedData {
        WritingSavedData() {
            // 复刻生产前提: syncWrite 仅对 dirty SavedData 调 (mixin 非 dirty 直接 continue).
            // vanilla SavedData.save(File) 内部 if (isDirty()) 才写盘, 不 setDirty 则空转不落盘.
            setDirty(true);
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putInt("v", 7);
            return tag;
        }
    }

    @Test
    void sync_fallback_releases_in_flight_slot_even_when_save_throws(@TempDir Path dir) {
        Set<String> inFlight = ConcurrentHashMap.newKeySet();
        String name = "huge";
        File file = dir.resolve(name + ".dat").toFile();
        assertTrue(inFlight.add(name), "前置: mixin 已占位");

        // save(file) 透出, 异常应按 vanilla 等价语义冒泡; 但占位必须经 finally 释放.
        assertThrows(IllegalStateException.class,
                () -> SavedDataSyncFallback.syncWrite(new ThrowingSavedData(), file, inFlight, name),
                "save(file) 的 Throwable 必须透出 (vanilla 等价)");

        assertFalse(inFlight.contains(name),
                "save 抛异常后仍必须经 finally 释放在途占位");
        assertTrue(inFlight.add(name),
                "占位释放后下个 autosave 周期同名文件必须能重新进入 dispatch 判定");
    }

    @Test
    void sync_fallback_releases_in_flight_slot_on_normal_write(@TempDir Path dir) {
        Set<String> inFlight = ConcurrentHashMap.newKeySet();
        String name = "normal";
        File file = dir.resolve(name + ".dat").toFile();
        assertTrue(inFlight.add(name));

        SavedDataSyncFallback.syncWrite(new WritingSavedData(), file, inFlight, name);

        assertFalse(inFlight.contains(name), "正常写盘后必须释放占位");
        assertTrue(file.exists(), "正常路径必须把文件写到盘");
        assertEquals(0, inFlight.size());
    }
}
