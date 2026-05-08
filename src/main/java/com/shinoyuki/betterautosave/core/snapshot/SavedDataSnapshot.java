package com.shinoyuki.betterautosave.core.snapshot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.File;

/**
 * v0.7 SavedData 路径 snapshot. 主线程 capture 时已构好完整外层 tag
 * ({@code data} 子 tag + DataVersion), worker 线程仅做 NbtIo.writeCompressed
 * 写盘 + listener fire.
 *
 * <p>与 {@link ChunkSnapshot} / {@link EntitySnapshot} 同设计原则: 主线程做
 * 不能并发的工作 (SavedData.save(CompoundTag) 内读 mod 内部 mutable state),
 * worker 做纯 IO. 但相比 chunk / entity 路径**显著简化**:
 * - 没有状态机 (DimensionDataStorage.save 是 per-cycle 调用, 不会高频并发)
 * - 没有 capturedGeneration (没有 "tag 落盘期间 mod 又改了" 的 race 检测,
 *   因为默认 vanilla 也是乐观清 dirty 后写, 本就有同样 race; 见 V0_7_PLAN §7.3)
 *
 * @param fileName     SavedData 名 (无 .dat 后缀, 例 "raids" "Forced" "mtr_train_data")
 * @param targetFile   完整 .dat 文件路径
 * @param preBuiltTag  主线程构好的外层 tag, 含 {@code data} 子 tag + DataVersion
 * @param savedData    反引用, 失败时 worker 通过 server.execute 调
 *                     {@link SavedData#setDirty()} 重新 mark dirty 让下个周期重试
 */
public record SavedDataSnapshot(
        String fileName,
        File targetFile,
        CompoundTag preBuiltTag,
        SavedData savedData
) {
}
