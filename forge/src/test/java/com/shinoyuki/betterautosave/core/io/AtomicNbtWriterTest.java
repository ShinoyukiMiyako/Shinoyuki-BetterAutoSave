package com.shinoyuki.betterautosave.core.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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
        CompoundTag readBack = NbtIo.readCompressed(target);
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

        CompoundTag readBack = NbtIo.readCompressed(target);
        assertEquals(2, readBack.getInt("gen"), "覆盖后必须是新版本");
        assertFalse(readBack.contains("stale"),
                "原子覆盖必须整体替换, 不能残留旧文件字段 (REPLACE_EXISTING)");
        assertFalse(new File(dir.toFile(), "forced.dat.tmp").exists(),
                "覆盖写后不能残留 .tmp");
    }

    /**
     * 字节路径 (issue #12 修复): serializeUncompressed 产出的脱钩字节经 writeCompressed(byte[])
     * 原子写后, 读回必须与原 tag 完全一致。判定标准: 把 serializeUncompressed 改成返回空数组,
     * 或 writeCompressed(byte[]) 漏 gzip, readBack.equals(tag) 挂。
     */
    @Test
    void serialized_bytes_path_roundtrips(@TempDir Path dir) throws IOException {
        File target = dir.resolve("mtr_train_data.dat").toFile();
        CompoundTag tag = sampleTag();

        byte[] bytes = AtomicNbtWriter.serializeUncompressed(tag);
        AtomicNbtWriter.writeCompressed(bytes, target);

        assertTrue(target.exists(), "字节路径原子写后目标 .dat 必须存在");
        assertEquals(tag, NbtIo.readCompressed(target),
                "脱钩字节经 gzip 原子写后, 读回必须与原 tag 完全一致");
        assertFalse(new File(dir.toFile(), "mtr_train_data.dat.tmp").exists(),
                "字节路径也不能残留 .tmp");
    }

    /**
     * 字节路径与 tag 路径落盘内容等价: 修复不改变落盘数据。判定标准: 若字节路径的 gzip 包装与 tag 路径
     * 不一致 (例如漏写 DataVersion / 字段次序漂移), 两边 readCompressed 不相等, 断言挂。
     */
    @Test
    void byte_path_ondisk_content_equals_tag_path(@TempDir Path dir) throws IOException {
        CompoundTag tag = sampleTag();
        File viaTag = dir.resolve("via_tag.dat").toFile();
        File viaBytes = dir.resolve("via_bytes.dat").toFile();

        AtomicNbtWriter.writeCompressed(tag, viaTag);
        AtomicNbtWriter.writeCompressed(AtomicNbtWriter.serializeUncompressed(tag), viaBytes);

        assertEquals(NbtIo.readCompressed(viaTag), NbtIo.readCompressed(viaBytes),
                "字节路径与 tag 路径落盘的 NBT 内容必须等价 (issue #12 修复不改变落盘数据)");
    }

    /**
     * 脱钩本体 (取代 tag.copy() 的数据安全保证): 序列化产出字节后, 对原 live tag 的任何 mutate
     * 都不能反映到已脱钩的字节里。这复刻 "worker 写盘期间 mod 继续改那棵 live tag" 的 race。
     * 判定标准: 若 serializeUncompressed 不是真快照 (例如改成持 live 引用), mutate 后 decoded 会含
     * "mutated" 字段或 counter 被改, 断言挂。
     */
    @Test
    void serializeUncompressed_decouples_from_live_tag_mutation() throws IOException {
        CompoundTag live = sampleTag();
        CompoundTag snapshotMoment = live.copy();

        byte[] bytes = AtomicNbtWriter.serializeUncompressed(live);

        // 模拟 mod 在 worker 写盘期间继续 mutate 那棵 live tag.
        live.putString("mutated", "after-serialize");
        live.getCompound("data").putLong("nextId", 999L);

        CompoundTag decoded = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        assertEquals(snapshotMoment, decoded,
                "序列化后对 live tag 的 mutate 不得反映到脱钩字节 (race 安全的本体)");
        assertFalse(decoded.contains("mutated"),
                "脱钩字节不得包含序列化之后才写入的字段");
        assertEquals(42L, decoded.getCompound("data").getLong("nextId"),
                "脱钩字节里的子树值必须是序列化时刻的值, 不随后续 mutate 变");
    }

    private static CompoundTag sampleTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", 7);
        tag.putString("name", "raids");
        CompoundTag inner = new CompoundTag();
        inner.putLong("nextId", 42L);
        inner.putByteArray("blob", new byte[]{1, 2, 3, 4, 5});
        tag.put("data", inner);
        return tag;
    }
}
