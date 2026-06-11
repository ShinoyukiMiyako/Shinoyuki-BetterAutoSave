package com.shinoyuki.betterautosave.core.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * SavedData .dat 文件原子写 (Minor 修复 4 第二层).
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
 */
public final class AtomicNbtWriter {

    private AtomicNbtWriter() {
    }

    /**
     * 原子写 gzip 压缩 NBT 到 target.
     *
     * @param tag    要写的 CompoundTag
     * @param target 目标 .dat 文件
     * @throws IOException 写临时文件 / fsync / rename 任一步失败 (调用方负责重试 / fallback)
     */
    public static void writeCompressed(CompoundTag tag, File target) throws IOException {
        Path targetPath = target.toPath();
        Path dir = targetPath.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        // 临时文件与目标同目录, 保证 ATOMIC_MOVE 在同一文件系统卷上 (跨卷 move 不可原子).
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            // NbtIo.writeCompressed 内部用 try-with-resources 包 GZIPOutputStream, close 时会
            // 关闭传入的流. 这里包一层不传播 close 的 wrapper, 让 gzip 层正常 finish (写完
            // gzip trailer) 但保留 fos 打开, 以便随后 fsync. 不这么做的话 fos 在 writeCompressed
            // 返回时已被关闭, getFD().sync() 抛 SyncFailedException.
            NbtIo.writeCompressed(tag, new NonClosingOutputStream(fos));
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
