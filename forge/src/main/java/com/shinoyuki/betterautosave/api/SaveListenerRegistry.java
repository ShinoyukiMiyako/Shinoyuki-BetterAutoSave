package com.shinoyuki.betterautosave.api;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BAS 保存路径 listener 注册中心. 单 BAS 进程全局静态, 支持多 listener 并存
 * (典型场景: BetterBackup + Prometheus exporter + 自定义诊断).
 *
 * <p><b>线程安全</b>: 内部用 {@link CopyOnWriteArrayList}, 注册 / 注销 / fire
 * 三方均线程安全. fire 期间注册的 listener 不会立即触发本次 fire (CoW 语义).
 *
 * <p><b>fire 性能</b>: 空 listener 时仅一次 {@code list.isEmpty()} 检查
 * (≤ 1ns, 0 alloc), 对未装下游 mod 的 BAS 用户零开销.
 *
 * <p><b>API 稳定性</b>: 此 package 是 BAS 公开 API, 接口签名遵循 semver,
 * 主版本号变更才允许 break.
 *
 * <p><b>fire 方法仅供 BAS 内部调用</b>. 第三方 mod 应只用 register / unregister,
 * 调用 fire 是未定义行为.
 *
 * <h2>典型用法</h2>
 *
 * <pre>{@code
 * // 在 mod setup 阶段:
 * ChunkSaveListener myListener = (pos, dim, tag) -> {
 *     // 处理逻辑, 必须线程安全 + 非阻塞
 * };
 * SaveListenerRegistry.registerChunk(myListener);
 *
 * // mod 卸载时:
 * SaveListenerRegistry.unregisterChunk(myListener);
 * }</pre>
 */
public final class SaveListenerRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<ChunkSaveListener> CHUNK = new CopyOnWriteArrayList<>();
    private static final List<EntityChunkSaveListener> ENTITY = new CopyOnWriteArrayList<>();
    private static final List<SavedDataSaveListener> SAVED_DATA = new CopyOnWriteArrayList<>();
    private static final List<PipelineStateListener> PIPELINE_STATE = new CopyOnWriteArrayList<>();

    private SaveListenerRegistry() {
    }

    public static void registerChunk(ChunkSaveListener listener) {
        CHUNK.add(listener);
    }

    public static void unregisterChunk(ChunkSaveListener listener) {
        CHUNK.remove(listener);
    }

    public static void registerEntityChunk(EntityChunkSaveListener listener) {
        ENTITY.add(listener);
    }

    public static void unregisterEntityChunk(EntityChunkSaveListener listener) {
        ENTITY.remove(listener);
    }

    public static void registerSavedData(SavedDataSaveListener listener) {
        SAVED_DATA.add(listener);
    }

    public static void unregisterSavedData(SavedDataSaveListener listener) {
        SAVED_DATA.remove(listener);
    }

    public static void registerPipelineState(PipelineStateListener listener) {
        PIPELINE_STATE.add(listener);
    }

    public static void unregisterPipelineState(PipelineStateListener listener) {
        PIPELINE_STATE.remove(listener);
    }

    /**
     * BAS 内部调用: chunk 成功落盘后触发. 第三方 mod 不应调用.
     */
    public static void fireChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag) {
        if (CHUNK.isEmpty()) {
            return;
        }
        for (ChunkSaveListener l : CHUNK) {
            try {
                l.onChunkSaved(pos, dimension, tag);
            } catch (Throwable t) {
                LOGGER.error("[BetterAutoSave] ChunkSaveListener {} threw, suppressing",
                        l.getClass().getName(), t);
            }
        }
    }

    /**
     * BAS 内部调用: entity chunk 成功落盘后触发. 第三方 mod 不应调用.
     */
    public static void fireEntityChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag) {
        if (ENTITY.isEmpty()) {
            return;
        }
        for (EntityChunkSaveListener l : ENTITY) {
            try {
                l.onEntityChunkSaved(pos, dimension, tag);
            } catch (Throwable t) {
                LOGGER.error("[BetterAutoSave] EntityChunkSaveListener {} threw, suppressing",
                        l.getClass().getName(), t);
            }
        }
    }

    /**
     * BAS 内部调用: SavedData (.dat 文件) 成功落盘后触发. 第三方 mod 不应调用.
     */
    public static void fireSavedDataWritten(String fileName, CompoundTag tag) {
        if (SAVED_DATA.isEmpty()) {
            return;
        }
        for (SavedDataSaveListener l : SAVED_DATA) {
            try {
                l.onSavedDataWritten(fileName, tag);
            } catch (Throwable t) {
                LOGGER.error("[BetterAutoSave] SavedDataSaveListener {} threw, suppressing",
                        l.getClass().getName(), t);
            }
        }
    }

    /**
     * BAS 内部调用: 是否有已注册的 SavedData listener.
     *
     * <p>worker 端落盘后据此决定是否把脱钩字节反序列化回 tag 再 fire —— 无 listener (例如未装
     * BetterBackup) 时跳过反序列化, 不为无人消费的 tag 付出 worker CPU。
     */
    public static boolean hasSavedDataListeners() {
        return !SAVED_DATA.isEmpty();
    }

    /**
     * BAS 内部调用: 管线首次进入 degraded mode 时触发. 第三方 mod 不应调用.
     */
    public static void firePipelineDegraded() {
        if (PIPELINE_STATE.isEmpty()) {
            return;
        }
        for (PipelineStateListener l : PIPELINE_STATE) {
            try {
                l.onDegraded();
            } catch (Throwable t) {
                LOGGER.error("[BetterAutoSave] PipelineStateListener {} threw, suppressing",
                        l.getClass().getName(), t);
            }
        }
    }

    /**
     * 测试 / 诊断用: 当前注册 listener 数. 不计入 API 稳定性承诺.
     */
    public static int chunkListenerCount() {
        return CHUNK.size();
    }

    /**
     * 测试 / 诊断用: 当前注册 entity listener 数. 不计入 API 稳定性承诺.
     */
    public static int entityChunkListenerCount() {
        return ENTITY.size();
    }

    /**
     * 测试 / 诊断用: 当前注册 savedData listener 数. 不计入 API 稳定性承诺.
     */
    public static int savedDataListenerCount() {
        return SAVED_DATA.size();
    }

    /**
     * 测试 / 诊断用: 当前注册 pipeline state listener 数. 不计入 API 稳定性承诺.
     */
    public static int pipelineStateListenerCount() {
        return PIPELINE_STATE.size();
    }
}
