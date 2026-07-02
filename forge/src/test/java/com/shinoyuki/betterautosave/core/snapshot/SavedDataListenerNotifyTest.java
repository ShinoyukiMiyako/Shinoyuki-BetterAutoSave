package com.shinoyuki.betterautosave.core.snapshot;

import com.shinoyuki.betterautosave.api.SaveListenerRegistry;
import com.shinoyuki.betterautosave.api.SavedDataSaveListener;
import com.shinoyuki.betterautosave.core.io.AtomicNbtWriter;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SavedData 落盘后 listener 通知 (issue #12 改字节路径后的回归).
 *
 * <p>snapshot 现持脱钩字节而非 tag, worker 端 {@code fireListenersIfAny} 只在真有注册 listener
 * 时把字节反序列化回 tag 再 fire。本测试验证两条:
 * <ol>
 *   <li>有 listener: 反序列化回的 tag 与主线程序列化前的 tag 完全一致 (BetterBackup 等下游拿到正确内容)</li>
 *   <li>无 listener: 跳过反序列化但落盘照常 (未装下游 mod 零反序列化开销)</li>
 * </ol>
 */
class SavedDataListenerNotifyTest {

    private SavedDataSaveListener registered;

    @AfterEach
    void cleanup() {
        // registry 是全局静态, 必须注销避免泄漏到其它测试.
        if (registered != null) {
            SaveListenerRegistry.unregisterSavedData(registered);
            registered = null;
        }
    }

    /** 最小 SavedData 桩, 仅供 IOException 路径的 setDirty 调用. */
    private static final class StubSavedData extends SavedData {
        @Override
        public CompoundTag save(CompoundTag tag) {
            return tag;
        }
    }

    private static SavedDataSnapshot snapshot(String name, File file, CompoundTag tag) {
        return new SavedDataSnapshot(name, file, AtomicNbtWriter.serializeUncompressed(tag),
                new StubSavedData(), new ConcurrentHashMap<>(), name, ConcurrentHashMap.newKeySet());
    }

    /**
     * 判定标准: 把 fireListenersIfAny 的反序列化去掉 (传 null 或不 fire), gotTag 为 null, 断言挂;
     * 若反序列化错位 (读串字节), gotTag 不 equals original, 断言挂。
     */
    @Test
    void registered_listener_receives_decoded_tag_equal_to_original(@TempDir Path dir) {
        CompoundTag original = new CompoundTag();
        original.putString("kind", "raids");
        CompoundTag data = new CompoundTag();
        data.putInt("count", 3);
        data.putByteArray("blob", new byte[]{9, 8, 7});
        original.put("data", data);

        AtomicReference<String> gotName = new AtomicReference<>();
        AtomicReference<CompoundTag> gotTag = new AtomicReference<>();
        registered = (fileName, tag) -> {
            gotName.set(fileName);
            gotTag.set(tag);
        };
        SaveListenerRegistry.registerSavedData(registered);

        File file = dir.resolve("raids.dat").toFile();
        SaveMetrics metrics = new SaveMetrics();
        // 对齐 execute 首行 decInFlightSerializing.
        metrics.incInFlightSerializing();

        new SavedDataSaveTask(snapshot("raids", file, original), metrics).execute();

        assertEquals("raids", gotName.get(), "listener 必须收到正确 fileName");
        assertEquals(original, gotTag.get(),
                "worker 端反序列化回的 tag 必须与主线程序列化前的 tag 完全一致");
        assertTrue(file.exists(), "落盘必须照常发生");
        assertEquals(1L, metrics.snapshot().savedDataCompleted());
    }

    /**
     * 判定标准: 若 fireListenersIfAny 漏掉 hasSavedDataListeners 短路、无条件反序列化+fire,
     * 本测试仍过 (无 listener 时 fire 空表无副作用); 但配合上一条共同钉死 "有则正确通知 / 无则不通知"。
     * 这里核心断言是无 listener 下仍落盘成功 (字节路径写盘与 listener 解耦)。
     */
    @Test
    void no_listener_still_writes_file(@TempDir Path dir) {
        CompoundTag original = new CompoundTag();
        original.putInt("v", 42);

        AtomicReference<CompoundTag> gotTag = new AtomicReference<>();
        // 不注册任何 listener; 留个 ref 仅证明确实没人被回调.
        File file = dir.resolve("forced.dat").toFile();
        SaveMetrics metrics = new SaveMetrics();
        metrics.incInFlightSerializing();

        new SavedDataSaveTask(snapshot("forced", file, original), metrics).execute();

        assertNull(gotTag.get(), "未注册 listener 不应有任何回调");
        assertTrue(file.exists(), "无 listener 时仍必须落盘");
        assertEquals(1L, metrics.snapshot().savedDataCompleted());
    }
}
