package com.shinoyuki.betterautosave.core.snapshot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;

/**
 * v0.6 worker 线程 entity NBT 包装器. 与 vanilla
 * {@code EntityStorage.storeEntities} 内 outer tag 构建逻辑等价:
 *
 * <pre>
 * CompoundTag compoundtag = NbtUtils.addCurrentDataVersion(new CompoundTag());
 * compoundtag.put("Entities", listtag);
 * writeChunkPos(compoundtag, chunkpos);
 * </pre>
 *
 * <p>不调 vanilla 私有 helper (NbtUtils.addCurrentDataVersion 实际只是
 * putInt DataVersion, BAS capture 已记录), 与 ChunkNbtAssembler 不同
 * (后者必须调 ChunkSerializer 私有 helper). 实现极简, 纯数据封装.
 */
public final class EntityNbtAssembler {

    private EntityNbtAssembler() {
    }

    public static CompoundTag assemble(EntitySnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", snapshot.dataVersion());
        tag.put("Entities", snapshot.entitiesNbt());
        tag.put("Position", new IntArrayTag(new int[]{snapshot.pos().x, snapshot.pos().z}));
        return tag;
    }
}
