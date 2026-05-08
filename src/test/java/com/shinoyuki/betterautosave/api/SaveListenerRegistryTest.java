package com.shinoyuki.betterautosave.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * SaveListenerRegistry 行为单测.
 *
 * <p>覆盖: 注册 / 注销 / 多 listener 并存 / 异常 listener 不影响其他 /
 * 空 listener fast path / chunk 与 entity 通道独立.
 *
 * <p><b>避免 vanilla 类静态初始化</b>: dimension 参数透传, 测试用 null 即可,
 * 不构造真实 ResourceKey&lt;Level&gt; 避免触发 BuiltInRegistries bootstrap
 * 在单测环境下导致 NoClassDefFoundError.
 */
class SaveListenerRegistryTest {

    private static final ChunkPos POS = new ChunkPos(1, 2);
    @SuppressWarnings("unchecked")
    private static final ResourceKey<Level> NULL_DIM = (ResourceKey<Level>) (ResourceKey<?>) null;

    private final AtomicReference<ChunkSaveListener> chunkListener = new AtomicReference<>();
    private final AtomicReference<EntityChunkSaveListener> entityListener = new AtomicReference<>();
    private final AtomicReference<SavedDataSaveListener> savedDataListener = new AtomicReference<>();

    @AfterEach
    void cleanup() {
        ChunkSaveListener c = chunkListener.getAndSet(null);
        if (c != null) SaveListenerRegistry.unregisterChunk(c);
        EntityChunkSaveListener e = entityListener.getAndSet(null);
        if (e != null) SaveListenerRegistry.unregisterEntityChunk(e);
        SavedDataSaveListener s = savedDataListener.getAndSet(null);
        if (s != null) SaveListenerRegistry.unregisterSavedData(s);
    }

    @Test
    void empty_registry_fire_is_noop() {
        // 不注册任何 listener, 直接 fire — 不应抛
        SaveListenerRegistry.fireChunkSaved(POS, NULL_DIM, new CompoundTag());
        SaveListenerRegistry.fireEntityChunkSaved(POS, NULL_DIM, new CompoundTag());
        SaveListenerRegistry.fireSavedDataWritten("test", new CompoundTag());
        assertEquals(0, SaveListenerRegistry.chunkListenerCount());
        assertEquals(0, SaveListenerRegistry.entityChunkListenerCount());
        assertEquals(0, SaveListenerRegistry.savedDataListenerCount());
    }

    @Test
    void registered_chunk_listener_receives_fire() {
        AtomicReference<ChunkPos> capturedPos = new AtomicReference<>();
        AtomicReference<CompoundTag> capturedTag = new AtomicReference<>();

        ChunkSaveListener l = (pos, dim, tag) -> {
            capturedPos.set(pos);
            capturedTag.set(tag);
        };
        chunkListener.set(l);
        SaveListenerRegistry.registerChunk(l);

        CompoundTag tag = new CompoundTag();
        tag.putInt("test", 42);
        SaveListenerRegistry.fireChunkSaved(POS, NULL_DIM, tag);

        assertEquals(POS, capturedPos.get());
        assertSame(tag, capturedTag.get());
        assertEquals(42, capturedTag.get().getInt("test"));
    }

    @Test
    void unregister_stops_callbacks() {
        AtomicInteger count = new AtomicInteger();
        ChunkSaveListener l = (pos, dim, tag) -> count.incrementAndGet();
        SaveListenerRegistry.registerChunk(l);

        SaveListenerRegistry.fireChunkSaved(POS, NULL_DIM, new CompoundTag());
        assertEquals(1, count.get());

        SaveListenerRegistry.unregisterChunk(l);
        SaveListenerRegistry.fireChunkSaved(POS, NULL_DIM, new CompoundTag());
        assertEquals(1, count.get(), "unregister 后不应再触发");
    }

    @Test
    void multiple_listeners_all_receive_fire() {
        AtomicInteger countA = new AtomicInteger();
        AtomicInteger countB = new AtomicInteger();
        ChunkSaveListener a = (pos, dim, tag) -> countA.incrementAndGet();
        ChunkSaveListener b = (pos, dim, tag) -> countB.incrementAndGet();
        SaveListenerRegistry.registerChunk(a);
        SaveListenerRegistry.registerChunk(b);

        SaveListenerRegistry.fireChunkSaved(POS, NULL_DIM, new CompoundTag());

        assertEquals(1, countA.get());
        assertEquals(1, countB.get());
        assertEquals(2, SaveListenerRegistry.chunkListenerCount());

        SaveListenerRegistry.unregisterChunk(a);
        SaveListenerRegistry.unregisterChunk(b);
    }

    @Test
    void exception_in_listener_does_not_affect_others() {
        AtomicInteger sane = new AtomicInteger();
        ChunkSaveListener throwing = (pos, dim, tag) -> {
            throw new RuntimeException("intentional test failure");
        };
        ChunkSaveListener sanityCheck = (pos, dim, tag) -> sane.incrementAndGet();
        SaveListenerRegistry.registerChunk(throwing);
        SaveListenerRegistry.registerChunk(sanityCheck);

        // throwing listener 抛异常, 不应阻止 sanityCheck 被调用 — Registry 内部 try-catch
        SaveListenerRegistry.fireChunkSaved(POS, NULL_DIM, new CompoundTag());

        assertEquals(1, sane.get(), "异常 listener 不应影响后续 listener 触发");

        SaveListenerRegistry.unregisterChunk(throwing);
        SaveListenerRegistry.unregisterChunk(sanityCheck);
    }

    @Test
    void chunk_and_entity_channels_are_independent() {
        AtomicInteger chunkCount = new AtomicInteger();
        AtomicInteger entityCount = new AtomicInteger();
        ChunkSaveListener cl = (pos, dim, tag) -> chunkCount.incrementAndGet();
        EntityChunkSaveListener el = (pos, dim, tag) -> entityCount.incrementAndGet();
        SaveListenerRegistry.registerChunk(cl);
        SaveListenerRegistry.registerEntityChunk(el);

        SaveListenerRegistry.fireChunkSaved(POS, NULL_DIM, new CompoundTag());
        assertEquals(1, chunkCount.get());
        assertEquals(0, entityCount.get(), "fireChunk 不应触发 entity listener");

        SaveListenerRegistry.fireEntityChunkSaved(POS, NULL_DIM, new CompoundTag());
        assertEquals(1, chunkCount.get(), "fireEntityChunk 不应触发 chunk listener");
        assertEquals(1, entityCount.get());

        SaveListenerRegistry.unregisterChunk(cl);
        SaveListenerRegistry.unregisterEntityChunk(el);
    }

    @Test
    void saved_data_listener_receives_fire() {
        AtomicReference<String> capturedName = new AtomicReference<>();
        AtomicReference<CompoundTag> capturedTag = new AtomicReference<>();

        SavedDataSaveListener l = (name, tag) -> {
            capturedName.set(name);
            capturedTag.set(tag);
        };
        savedDataListener.set(l);
        SaveListenerRegistry.registerSavedData(l);

        CompoundTag tag = new CompoundTag();
        tag.putInt("data", 99);
        SaveListenerRegistry.fireSavedDataWritten("raids", tag);

        assertEquals("raids", capturedName.get());
        assertSame(tag, capturedTag.get());
        assertEquals(99, capturedTag.get().getInt("data"));
    }

    @Test
    void saved_data_channel_independent_from_chunk_and_entity() {
        AtomicInteger chunkCount = new AtomicInteger();
        AtomicInteger savedDataCount = new AtomicInteger();
        ChunkSaveListener cl = (pos, dim, tag) -> chunkCount.incrementAndGet();
        SavedDataSaveListener sl = (name, tag) -> savedDataCount.incrementAndGet();
        SaveListenerRegistry.registerChunk(cl);
        SaveListenerRegistry.registerSavedData(sl);

        SaveListenerRegistry.fireChunkSaved(POS, NULL_DIM, new CompoundTag());
        assertEquals(1, chunkCount.get());
        assertEquals(0, savedDataCount.get(), "fireChunk 不应触发 savedData listener");

        SaveListenerRegistry.fireSavedDataWritten("test", new CompoundTag());
        assertEquals(1, chunkCount.get(), "fireSavedData 不应触发 chunk listener");
        assertEquals(1, savedDataCount.get());

        SaveListenerRegistry.unregisterChunk(cl);
        SaveListenerRegistry.unregisterSavedData(sl);
    }

    @Test
    void counts_reflect_active_registrations() {
        assertEquals(0, SaveListenerRegistry.chunkListenerCount());
        ChunkSaveListener l1 = (pos, dim, tag) -> {};
        ChunkSaveListener l2 = (pos, dim, tag) -> {};
        SaveListenerRegistry.registerChunk(l1);
        SaveListenerRegistry.registerChunk(l2);
        assertEquals(2, SaveListenerRegistry.chunkListenerCount());
        SaveListenerRegistry.unregisterChunk(l1);
        assertEquals(1, SaveListenerRegistry.chunkListenerCount());
        SaveListenerRegistry.unregisterChunk(l2);
        assertEquals(0, SaveListenerRegistry.chunkListenerCount());
    }
}
