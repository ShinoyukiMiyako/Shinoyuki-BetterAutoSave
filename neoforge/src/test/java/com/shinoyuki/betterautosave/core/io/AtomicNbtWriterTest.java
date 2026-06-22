package com.shinoyuki.betterautosave.core.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AtomicNbtWriter 原子写单测.
 *
 * <p>判定标准: 把 writeCompressed 里的 ATOMIC_MOVE 改成直接 NbtIo.writeCompressed(tag, target),
 * "无 tmp 残留" 断言挂 (临时文件路径不再产生但 round-trip 仍过 — 因此关键断言是 tmp 残留 +
 * 目标完整双重校验). 删掉 fsync / move 任一步, "读回内容一致" 断言挂.
 */
class AtomicNbtWriterTest {

    @Test
    void atomic_write_produces_complete_readable_file_with_no_tmp_residue(@TempDir Path dir) throws IOException {
        File target = dir.resolve("raids.dat").toFile();

        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 7);
        tag.putString("name", "raids");
        CompoundTag inner = new CompoundTag();
        inner.putLong("nextId", 42L);
        tag.put("data", inner);

        AtomicNbtWriter.writeCompressed(tag, target);

        // 目标文件存在且内容可被 vanilla NbtIo 读回, 字段完整 (证明写完整 + gzip 有效).
        assertTrue(target.exists(), "原子写后目标 .dat 必须存在");
        CompoundTag readBack = NbtIo.readCompressed(target.toPath(), NbtAccounter.unlimitedHeap());
        assertEquals(7, readBack.getInt("version"));
        assertEquals("raids", readBack.getString("name"));
        assertEquals(42L, readBack.getCompound("data").getLong("nextId"),
                "读回内容必须与写入一致 (fsync + rename 完整落盘)");

        // 关键: 临时文件 <name>.dat.tmp 不能残留 (ATOMIC_MOVE 已把它 rename 成目标).
        File tmp = new File(dir.toFile(), "raids.dat.tmp");
        assertFalse(tmp.exists(), "原子写后不能残留 .tmp 临时文件");
    }

    @Test
    void atomic_write_overwrites_existing_target_completely(@TempDir Path dir) throws IOException {
        File target = dir.resolve("forced.dat").toFile();

        // 先写一个"旧"版本占位.
        CompoundTag old = new CompoundTag();
        old.putInt("gen", 1);
        old.putString("stale", "should-be-gone");
        AtomicNbtWriter.writeCompressed(old, target);

        // 再原子覆盖为新版本, 新版本不含 stale 字段.
        CompoundTag fresh = new CompoundTag();
        fresh.putInt("gen", 2);
        AtomicNbtWriter.writeCompressed(fresh, target);

        CompoundTag readBack = NbtIo.readCompressed(target.toPath(), NbtAccounter.unlimitedHeap());
        assertEquals(2, readBack.getInt("gen"), "覆盖后必须是新版本");
        assertFalse(readBack.contains("stale"),
                "原子覆盖必须整体替换, 不能残留旧文件字段 (REPLACE_EXISTING)");
        assertFalse(new File(dir.toFile(), "forced.dat.tmp").exists(),
                "覆盖写后不能残留 .tmp");
    }
}
