package com.shinoyuki.betterautosave.core.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPOutputStream;

/**
 * SavedData .dat 文件原子写 + 主线程脱钩序列化.
 *
 * <p>vanilla {@code SavedData.save(File)} 直接 {@code NbtIo.writeCompressed(tag, file)}:
 * 写到一半崩溃 (掉电 / OOM kill) 会留下截断的 .dat, 下次加载 gzip 解压失败 → SavedData
 * 丢失 (raids / forced chunks / mod 数据). BAS 多 worker (savedDataWorkerThreads 1-4)
 * 并发写同名文件更放大该风险.
 *
 * <p>原子写流程: 写临时文件 {@code <name>.dat.tmp} → fsync 落盘 (FileDescriptor.sync) →
 * {@code Files.move(tmp, target, ATOMIC_MOVE)}. ATOMIC_MOVE 保证读端要么看到旧完整文件
 * 要么看到新完整文件, 不会看到截断中间态. 文件系统不支持 ATOMIC_MOVE (部分跨卷 / 网络 fs)
 * 时降级为 REPLACE_EXISTING (非原子但仍优于直接覆盖 — 临时文件已 fsync 完整).
 *
 * <p><b>脱钩字节路径</b>: {@link #serializeUncompressed} 在主线程把 mod 的 SavedData 外层 tag
 * 一次序列化成未压缩 NBT 字节, 取代过去的 {@code CompoundTag.copy()} 深拷贝。字节是不可变快照,
 * 与 mod live tag 彻底脱钩 (worker gzip + 写盘期间 mod 再改原 tag 不影响落盘内容), 且不分配平行
 * NBT 对象树, 主线程开销显著低于深拷贝。worker 端用 {@link #writeCompressed(byte[], File)} 仅做
 * gzip + 原子写, 落盘内容与 {@link #writeCompressed(CompoundTag, File)} 等价。
 */
public final class AtomicNbtWriter {

    private AtomicNbtWriter() {
    }

    /**
     * 主线程把 SavedData 外层 tag 序列化成未压缩 NBT 字节, 与 mod live tag 脱钩.
     *
     * <p>取代 {@code tag.copy()}: 深拷贝在堆里建一棵平行 NBT 对象树 (每节点一个 CompoundTag +
     * Object2ObjectOpenHashMap), 对超大 SavedData 是主线程秒级尖峰; 序列化只顺树走一遍写原语进
     * buffer, 不建平行树, 远轻。产出的字节不可能 alias mod 内部 live 子树, 脱钩比深拷贝更彻底。
     *
     * @param tag 主线程构好的外层 tag (含 {@code data} 子 tag + DataVersion)
     * @return 未压缩 NBT 字节 (与 {@link NbtIo#write(CompoundTag, java.io.DataOutput)} 同格式)
     */
    public static byte[] serializeUncompressed(CompoundTag tag) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            NbtIo.write(tag, new DataOutputStream(baos));
        } catch (IOException e) {
            // ByteArrayOutputStream 不做真实 IO, write 不可能抛; 兜底转非受检, 不污染主线程调用方签名.
            throw new UncheckedIOException("in-memory NBT serialize failed (unreachable)", e);
        }
        return baos.toByteArray();
    }

    /**
     * 原子写 gzip 压缩 NBT 到 target (tag 路径, 主线程同步兜底用).
     *
     * @param tag    要写的 CompoundTag
     * @param target 目标 .dat 文件
     * @throws IOException 写临时文件 / fsync / rename 任一步失败 (调用方负责重试 / fallback)
     */
    public static void writeCompressed(CompoundTag tag, File target) throws IOException {
        atomicWrite(target, fos -> NbtIo.writeCompressed(tag, new NonClosingOutputStream(fos)));
    }

    /**
     * 原子写 gzip(rawNbt) 到 target (字节路径, worker 线程用).
     *
     * <p>rawNbt 为 {@link #serializeUncompressed} 产出的未压缩 NBT 字节; gzip 压缩留到此处 (worker
     * 线程) 完成, 主线程只付序列化。落盘的 gzip 流解压后与 {@link #writeCompressed(CompoundTag, File)}
     * 落盘内容逐字节一致 (同一份 NBT 字节, 仅压缩时机不同)。
     *
     * @param rawNbt 未压缩 NBT 字节
     * @param target 目标 .dat 文件
     * @throws IOException 写临时文件 / fsync / rename 任一步失败
     */
    public static void writeCompressed(byte[] rawNbt, File target) throws IOException {
        atomicWrite(target, fos -> {
            // NonClosingOutputStream 让 gzip 层正常 finish (写 trailer + flush) 但保留 fos 打开供 fsync;
            // 不这么做 gz.close() 会关掉 fos, 随后 getFD().sync() 抛 SyncFailedException.
            try (GZIPOutputStream gz = new GZIPOutputStream(new NonClosingOutputStream(fos))) {
                gz.write(rawNbt);
            }
        });
    }

    @FunctionalInterface
    private interface FosWriter {
        void writeTo(FileOutputStream fos) throws IOException;
    }

    /**
     * tmp + fsync + ATOMIC_MOVE 原子写骨架. {@code content} 负责把内容写进 tmp 的 fos
     * (写完保持 fos 打开, 由本方法 flush + fsync 后关闭)。
     */
    private static void atomicWrite(File target, FosWriter content) throws IOException {
        Path targetPath = target.toPath();
        Path dir = targetPath.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        // 临时文件与目标同目录, 保证 ATOMIC_MOVE 在同一文件系统卷上 (跨卷 move 不可原子).
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                content.writeTo(fos);
                fos.flush();
                // fsync: 把 OS page cache 强制刷到物理盘. 不 fsync 时 rename 后仍可能因掉电丢内容,
                // 因为 rename 元数据可能先于数据落盘.
                fos.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), targetPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 文件系统不支持原子 move (例如跨卷): 降级为非原子替换. 临时文件已 fsync 完整,
                // 仍优于直接覆盖目标的写到一半崩溃风险.
                Files.move(tmp.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 写 tmp / fsync / move 任一步失败: 删除可能残留的半截 tmp, 避免孤儿文件堆积。固定名 tmp 下次同名写
            // 会 truncate 复用, 但若该 .dat 此后不再 dirty 则永久残留, 故进程内失败这里主动清。删除本身失败不掩盖
            // 原 IOException (best-effort, 原异常优先上抛供调用方重试 / fallback)。掉电在 fsync 与 move 之间被
            // kill 的孤儿本方法管不到 (进程已死), 仍靠下次同名写 truncate。
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (IOException ignored) {
                // 清理失败无关紧要, 原 IOException 优先.
            }
            throw e;
        }
    }

    /**
     * 包装 OutputStream, close() 只 flush 不真正关闭底层. 让 NbtIo 的 gzip 层正常 finish
     * (写 trailer + flush 到底层 fos), 但保留 fos 打开供后续 fsync.
     */
    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            // FilterOutputStream 默认 write(byte[]) 逐字节转发, 极慢. 直接转发整段.
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
