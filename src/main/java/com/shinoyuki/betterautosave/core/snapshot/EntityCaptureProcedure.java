package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.core.state.EntitySaveState;
import com.shinoyuki.betterautosave.util.ServerThreadAssert;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.ChunkEntities;
import org.slf4j.Logger;

/**
 * v0.6 主线程 entity capture: 把一组 entities 在主线程内调 {@code entity.save}
 * 序列化为 ListTag, 包装成 EntitySnapshot 投递到 worker queue.
 *
 * <p>与 {@link ChunkCaptureProcedure} 设计原则一致, 但实现简单得多 — 因为
 * vanilla {@code EntityStorage.storeEntities} 本身只做三件事 (entity.save
 * 循环 / 包装 outer tag / 提交 IO), 主线程不可避免的部分仅 entity.save 循环.
 *
 * <p>容错策略: 单个 entity.save 抛异常时记录 ERROR 并跳过 (vanilla 行为
 * 一致, 单个 entity 损坏不阻塞整个 chunk 的 entity 保存). 不重新抛.
 */
public final class EntityCaptureProcedure {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

    private EntityCaptureProcedure() {
    }

    public static EntitySnapshot capture(
            ChunkEntities<Entity> chunkEntities,
            ServerLevel level,
            EntitySaveState state) {
        ServerThreadAssert.assertOnServerThread(level.getServer());

        long captured = state.enterSerializing();
        int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();

        ListTag entitiesNbt = new ListTag();
        chunkEntities.getEntities().forEach(entity -> {
            CompoundTag entityTag = new CompoundTag();
            try {
                if (entity.save(entityTag)) {
                    entitiesNbt.add(entityTag);
                }
            } catch (Throwable t) {
                LOGGER.error("[BetterAutoSave] entity {} at {} save threw, dropping (vanilla equivalence)",
                        entity.getType(), chunkEntities.getPos(), t);
            }
        });

        return new EntitySnapshot(
                chunkEntities.getPos(),
                level.dimension(),
                dataVersion,
                entitiesNbt,
                captured,
                state);
    }
}
