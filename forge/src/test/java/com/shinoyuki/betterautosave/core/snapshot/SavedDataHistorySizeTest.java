package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.core.io.AtomicNbtWriter;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大文件守卫的历史 size 必须回写"未压缩字节长度"而非"gzip 后磁盘尺寸": 守卫要拦的是主线程
 * serializeUncompressed 分配的未压缩 byte[], 用压缩后尺寸会低估 5-10 倍放行超标文件在主线程炸内存。
 * 构造高压缩比数据 (未压缩 >> 压缩) 跑 worker execute, 断言回写的是未压缩长度。把 worker 回写改回
 * targetFile().length() (压缩尺寸) -> 回写值 << 未压缩长度 -> 断言挂。
 */
class SavedDataHistorySizeTest {

    @Test
    void worker_records_uncompressed_length_not_compressed_disk_size(@TempDir Path dir) {
        // 高压缩比 tag: 一段长重复字符串, gzip 后远小于未压缩。
        CompoundTag tag = new CompoundTag();
        tag.putString("payload", "a".repeat(50_000));
        byte[] nbtBytes = AtomicNbtWriter.serializeUncompressed(tag);

        String name = "compressible";
        File file = dir.resolve(name + ".dat").toFile();
        ConcurrentHashMap<String, Long> history = new ConcurrentHashMap<>();
        SavedData stub = new SavedData() {
            @Override
            public CompoundTag save(CompoundTag t) {
                return t;
            }
        };
        SavedDataSnapshot snapshot = new SavedDataSnapshot(name, file, nbtBytes, stub, history, file.getPath(),
                ConcurrentHashMap.newKeySet());

        new SavedDataSaveTask(snapshot, new SaveMetrics()).execute();

        assertTrue(file.isFile(), "worker 必须落盘");
        long compressedDiskSize = file.length();
        assertTrue(compressedDiskSize < nbtBytes.length,
                "前置: 高压缩比数据 gzip 后磁盘尺寸必须远小于未压缩长度 (压缩=" + compressedDiskSize
                        + " 未压缩=" + nbtBytes.length + ")");
        assertEquals((long) nbtBytes.length, history.get(name),
                "守卫历史必须回写未压缩字节长度 (nbtBytes.length), 而非 gzip 后磁盘尺寸 (否则大文件闸门按压缩尺寸低估放行)");
    }
}
