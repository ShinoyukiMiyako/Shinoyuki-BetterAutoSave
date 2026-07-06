package com.shinoyuki.betterautosave.core.snapshot;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 代码审查 (issue #6/#8 举一反三) 修复的字节码回归。同 {@link com.shinoyuki.betterautosave.mixin.InFlightVanillaParityTest}:
 * bare JUnit 无 MC 运行期, 无法在单测里跑 mixin 方法 / 构造真实队列, 故对 build/classes 编译产物按目标方法内的
 * 调用计数断言, deletion-sensitive (删修复 -> 计数归零 -> 断言挂)。
 *
 * <ul>
 *   <li>M1: DimensionDataStorageMixin 异步前把 mod save() 返回的 tag 序列化成脱钩字节 (serializeUncompressed)
 *       脱钩 (worker 序列化期间 mod mutate 不损坏)。issue #12 起由 copy() 改为序列化字节: 同样脱钩但不分配
 *       平行 NBT 树, 对超大 SavedData 不再是主线程秒级尖峰</li>
 *   <li>M2: SnapshotPipeline.drainQueueOnDegrade 逐出三条队列残留 task 各自 abandon 善后 (worker 全灭防静默丢数据)</li>
 *   <li>m6: chunk/entity dispatch 的 inc-offer 之间补偿 (offer 抛时 decInFlightSerializing) 防 serializing gauge 泄漏</li>
 * </ul>
 */
class AuditFixParityTest {

    private static final String CLASSES_DIR = "build/classes/java/main";

    /**
     * main 类目录, 不依赖工作目录。FG6 (forge) 测试工作目录=模块根, 故 build/classes/java/main 可用; MDG
     * (neoforge) 测试工作目录=模块/build/minecraft-junit, 此时 main 类在上一级的 ../classes/java/main。逐个
     * 试候选相对路径取第一个存在的, 两套工具链都覆盖。
     */
    private Path mainClassesDir() {
        for (String candidate : new String[]{
                CLASSES_DIR,                  // FG6: 工作目录=模块根
                "../classes/java/main",       // MDG: 工作目录=模块/build/minecraft-junit -> 上一级 build/
                "../../build/classes/java/main"}) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Path.of(CLASSES_DIR);
    }

    private MethodNode loadMethod(String className, String methodName) throws IOException {
        Path classFile = mainClassesDir().resolve(className.replace('.', '/') + ".class");
        assertTrue(Files.exists(classFile), "编译产物缺失 (先跑 compileJava): " + classFile.toAbsolutePath());
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

    private int countCalls(MethodNode m, String calleeName) {
        int n = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.name.equals(calleeName)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void m1_savedData_mixin_decouples_mod_tag_before_async() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.DimensionDataStorageMixin",
                "betterautosave$interceptSave");
        assertTrue(countCalls(m, "serializeUncompressed") >= 1,
                "M1: interceptSave 必须把 mod save() 返回的 tag 序列化成脱钩字节 (serializeUncompressed), 否则 worker"
                        + "异步序列化期间 mod mutate -> CME / torn write。issue #12 起由 copy() 改为序列化字节脱钩。");
    }

    @Test
    void m2_degraded_drain_abandons_all_three_task_types() throws IOException {
        // 善后路由抽到 abandonStrandedTask (drainStrandedOnDegrade 与 offer 后的 reclaimIfDegradedAfterOffer 共用)。
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline",
                "abandonStrandedTask");
        assertTrue(countCalls(m, "abandonToRecoveryOnDegrade") >= 1,
                "M2: degraded 善后必须对 chunk task 调 abandonToRecoveryOnDegrade 还原坐标走 vanilla 兜底");
        assertTrue(countCalls(m, "abandonOnDegrade") >= 2,
                "M2: degraded 善后必须对 entity + savedData task 各调 abandonOnDegrade 善后 (>=2)");
    }

    @Test
    void m6_chunk_dispatch_compensates_serializing_on_offer_throw() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline",
                "captureAndDispatchChunk");
        assertTrue(countCalls(m, "decInFlightSerializing") >= 1,
                "m6: captureAndDispatchChunk 的 inc-offer 之间须有 decInFlightSerializing 补偿 (offer 抛时), 否则"
                        + "无界队列 OOM 下 serializing gauge 永久 +1 毒化 drainPending");
    }

    @Test
    void m6_entity_dispatch_compensates_serializing_on_offer_throw() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.EntityStorageMixin",
                "betterautosave$interceptStoreEntities");
        assertTrue(countCalls(m, "decInFlightSerializing") >= 1,
                "m6: interceptStoreEntities 的 inc-offer 之间须有 decInFlightSerializing 补偿 (offer 抛时)");
    }

}
