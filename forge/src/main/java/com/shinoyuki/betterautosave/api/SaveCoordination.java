package com.shinoyuki.betterautosave.api;

import com.shinoyuki.betterautosave.core.restore.OnlineChunkRestorer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.CompletableFuture;

/**
 * 活服存档协同的对外门面 (DESIGN §3.1)。与 {@link SaveListenerRegistry} (观察者总线) 并列,
 * 但属新类别: 主动写、有返回、暴露失败。
 *
 * <p>设计取舍 (已定): 只暴露高层单操作"把这份字节变成活区块", 不向调用方暴露
 * evict/suppressSave 等原语 —— 顺序正确性归拥有不变量的一方 (BAS), BB 只给一个事务性动词。
 */
public final class SaveCoordination {

    private SaveCoordination() {
    }

    /**
     * 活服把已加载 chunk 的地形回退到 restoredTag。必须在 server 主线程调用。
     *
     * <p>贵的反序列化下沉 BAS load worker, 主线程只做廉价的原地内容安装 + 异步光照触发 + 重发。
     * 返回的 future 在主线程完成 (load worker 解析完后续到主线程安装), 完成线程不保证, 调用方
     * 应在 whenComplete 内自行 marshal 回主线程再发消息。
     *
     * @param level      目标维度 (活 ServerLevel)
     * @param pos        目标 chunk 坐标
     * @param restoredTag 快照里该 chunk 的 vanilla NBT (BB 已从 slot 字节还原)
     * @return 回退结果 future; outcome 见 {@link ChunkRestoreOutcome}
     */
    public static CompletableFuture<ChunkRestoreResult> restoreChunkLive(
            ServerLevel level, ChunkPos pos, CompoundTag restoredTag) {
        return OnlineChunkRestorer.restoreChunkLive(level, pos, restoredTag);
    }
}
