package com.shinoyuki.betterautosave.core.snapshot;

import net.minecraft.world.level.saveddata.SavedData;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SavedData 路径 snapshot. 主线程 capture 时已把外层 tag
 * ({@code data} 子 tag + DataVersion) 序列化成未压缩 NBT 字节 (与 mod live tag 脱钩),
 * worker 线程仅做 gzip + 原子写盘 (+ 有 listener 时反序列化回 tag fire).
 *
 * <p>与 {@link ChunkSnapshot} / {@link EntitySnapshot} 同设计原则: 主线程做
 * 不能并发的工作 (SavedData.save(CompoundTag) 内读 mod 内部 mutable state,
 * 以及序列化脱钩), worker 做纯 IO. 但相比 chunk / entity 路径**显著简化**:
 * - 没有状态机 (DimensionDataStorage.save 是 per-cycle 调用, 不会高频并发)
 * - 没有 capturedGeneration (没有 "tag 落盘期间 mod 又改了" 的 race 检测,
 *   因为默认 vanilla 也是乐观清 dirty 后写, 本就有同样 race; 见 V0_7_PLAN §7.3)
 *
 * <p><b>为何持字节而非 tag</b>: 过去主线程对外层 tag 做 {@code CompoundTag.copy()} 深拷贝再
 * 入队, 对超大 SavedData 是秒级主线程尖峰 (issue #12)。改为主线程一次序列化成字节: 脱钩等价
 * (字节不可能 alias mod live 子树), 但不分配平行 NBT 对象树, 主线程开销远低, 且 worker 不必再
 * 序列化一遍 (直接 gzip 字节)。
 *
 * @param fileName        SavedData 名 (无 .dat 后缀, 例 "raids" "Forced" "mtr_train_data")
 * @param targetFile      完整 .dat 文件路径
 * @param nbtBytes        主线程序列化好的未压缩 NBT 字节 (外层 tag 含 {@code data} 子 tag + DataVersion)
 * @param savedData       反引用, 失败时 worker 通过 server.execute 调
 *                        {@link SavedData#setDirty()} 重新 mark dirty 让下个周期重试
 * @param historySizeMap  mixin 实例共享的 fileName -> 上次落盘 size map.
 *                        worker 写盘成功后回写 size, 让 mixin 下次守卫优先用历史 size 而非
 *                        file.length() (后者在 NFS / SMB 不可靠, 且首次写无值). null 表示
 *                        未启用 (向后兼容).
 * @param inFlight        pipeline 共享的在途文件名集合. mixin 入队前 add(fileName)
 *                        成功才 dispatch, worker task finally remove(fileName). 防多 worker
 *                        并发写同名 .dat. null 表示未启用 (向后兼容).
 */
public record SavedDataSnapshot(
        String fileName,
        File targetFile,
        byte[] nbtBytes,
        SavedData savedData,
        ConcurrentHashMap<String, Long> historySizeMap,
        Set<String> inFlight
) {
}
