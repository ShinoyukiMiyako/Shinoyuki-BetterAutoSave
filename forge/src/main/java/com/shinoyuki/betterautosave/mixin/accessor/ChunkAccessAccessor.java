package com.shinoyuki.betterautosave.mixin.accessor;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 取 ChunkAccess 的 protected final {@code pendingBlockEntities} 映射引用。
 *
 * <p>在线回退 (OnlineChunkRestorer.install) 的事务回滚需要它: {@code clearAllBlockEntities()} 只清
 * live 的 {@code blockEntities} (已物化 BE), <b>不碰</b> {@code pendingBlockEntities} (keepPacked 形态
 * 尚未物化的 BE NBT)。故回滚拍快照时必须连 pending 一起存, 回灌时必须连 pending 一起还原, 否则回退非
 * byte-exact (残留被覆盖前的 pending 项 / 丢失被覆盖的 pending 项)。LevelChunk 无公有 API 读/清/写此表,
 * 经 accessor 拿到引用后在 install 内就地 {@code clear()} + {@code putAll()}。
 *
 * <p>字段 {@code final} 故只暴露 getter (拿引用后原地改), 不暴露 setter。仅回滚路径需要, 成功路径不碰。
 */
@Mixin(ChunkAccess.class)
public interface ChunkAccessAccessor {

    @Accessor("pendingBlockEntities")
    Map<BlockPos, CompoundTag> betterautosave$getPendingBlockEntities();
}
