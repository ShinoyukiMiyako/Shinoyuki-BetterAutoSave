package com.shinoyuki.betterautosave.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("level")
    ServerLevel betterautosave$getLevel();

    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> betterautosave$getVisibleChunkMap();

    /**
     * 暴露 scheduleChunkLoad 续段所在的主线程 executor (ChunkMap.java:123
     * {@code private final BlockableEventLoop<Runnable> mainThreadExecutor})。异步加载 wrap 用它的
     * {@code isSameThread()} 校验 wrap 确实跑在主线程 (vanilla thenApplyAsync(mainThreadExecutor) 的保证),
     * 防未来 coremod 改续段线程后 POI/光照延迟回放误落非主线程。
     */
    @Accessor("mainThreadExecutor")
    BlockableEventLoop<Runnable> betterautosave$getMainThreadExecutor();
}
