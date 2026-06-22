package com.shinoyuki.betterautosave.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * in-flight 碰撞分支复刻 vanilla 副作用的字节码回归.
 *
 * <p>现场: vanilla {@code ChunkMap.save} 首行无条件 {@code poiManager.flush(pos)}, vanilla
 * {@code EntityStorage.storeEntities} 非空分支末尾无条件 {@code emptyChunks.remove(pos)}。BAS 在 clean-bypass
 * 与常规 dispatch 路径都忠实复刻了, 但 in-flight 碰撞分支 (setReturnValue/cancel 短路返回前) 漏掉:
 * chunk 侧 POI region 滞后一 cycle 短暂不一致; entity 侧 stale emptyChunks 条目使 reload 命中快速路径返空
 * chunk, 已落盘 entity 静默丢失 (不可重建)。
 *
 * <p><b>为何用字节码断言</b>: 本项目 test 任务是裸 JUnit Platform 无 mixin agent (见 SavedDataDirtyVisibilityTest
 * 说明), mixin @Inject 处理方法无法在单测里以真实 PoiManager/EntityStorage 实例调用。flush/remove 是无分支
 * 逻辑的纯 vanilla-parity 调用, 没有可抽取的业务逻辑可单测。故对 build/classes 下编译产物 (mixin 应用前) 做
 * 字节码断言: 统计目标 @Inject 方法内对 poiManager 字段 / emptyChunks accessor 的引用次数 —— 这是唯一既
 * deletion-sensitive 又不需 mixin 运行期的手段。
 *
 * <p>判定标准: 删 in-flight 分支的 poiManager.flush -> chunk 方法内 poiManager 字段读取从 3 降到 2,
 * 断言挂; 删 in-flight 分支的 emptyChunks.remove -> entity 方法内 betterautosave$getEmptyChunks 调用从 2 降到 1。
 */
class InFlightVanillaParityTest {

    private static final String CLASSES_DIR = "build/classes/java/main";

    private MethodNode loadMethod(String className, String methodName) throws IOException {
        Path classFile = Path.of(CLASSES_DIR, className.replace('.', '/') + ".class");
        assertTrue(Files.exists(classFile),
                "编译产物缺失 (先跑 compileJava): " + classFile.toAbsolutePath());
        ClassNode node = new ClassNode();
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(node, 0);
        }
        for (MethodNode m : node.methods) {
            if (m.name.equals(methodName)) {
                return m;
            }
        }
        fail("方法未找到: " + className + "#" + methodName);
        return null;
    }

    /**
     * chunk in-flight 分支必须复刻 poiManager.flush。统计 betterautosave$interceptSave 内对自身 poiManager
     * 字段的 GETFIELD 次数: clean-bypass (1) + in-flight 碰撞 (1) + 常规 dispatch (1) = 3。
     */
    @Test
    void chunk_intercept_save_flushes_poi_in_all_takeover_paths() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.ChunkMapSaveMixin",
                "betterautosave$interceptSave");

        int poiFieldReads = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field
                    && field.name.equals("poiManager")
                    && field.owner.equals("com/shinoyuki/betterautosave/mixin/ChunkMapSaveMixin")) {
                poiFieldReads++;
            }
        }
        assertEquals(3, poiFieldReads,
                "chunk interceptSave 必须在三条接管路径 (clean-bypass / in-flight 碰撞 / 常规 dispatch) 各 flush "
                        + "一次 POI; in-flight 分支漏 flush 则降到 2");
    }

    /**
     * entity in-flight 分支必须复刻 emptyChunks.remove。统计 betterautosave$interceptStoreEntities 内对
     * betterautosave$getEmptyChunks accessor 的调用次数: in-flight 碰撞 (1) + 常规 dispatch (1) = 2。
     */
    @Test
    void entity_intercept_store_removes_empty_chunks_in_inflight_and_regular() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.EntityStorageMixin",
                "betterautosave$interceptStoreEntities");

        int emptyChunksAccessorCalls = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.name.equals("betterautosave$getEmptyChunks")) {
                emptyChunksAccessorCalls++;
            }
        }
        assertEquals(2, emptyChunksAccessorCalls,
                "entity interceptStoreEntities 必须在 in-flight 碰撞分支与常规 dispatch 分支各调一次 "
                        + "emptyChunks.remove; in-flight 分支漏 remove 则降到 1");
    }

    /** 防御性: 确认两个被测方法都真实存在 (重命名 @Inject 方法会让上面计数静默归零误判 PASS)。 */
    @Test
    void target_inject_methods_exist() throws IOException {
        List<MethodNode> ignored = List.of(
                loadMethod("com.shinoyuki.betterautosave.mixin.ChunkMapSaveMixin", "betterautosave$interceptSave"),
                loadMethod("com.shinoyuki.betterautosave.mixin.EntityStorageMixin", "betterautosave$interceptStoreEntities"));
        assertEquals(2, ignored.size());
    }
}
