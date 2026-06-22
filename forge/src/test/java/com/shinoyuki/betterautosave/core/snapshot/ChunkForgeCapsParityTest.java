package com.shinoyuki.betterautosave.core.snapshot;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #8 回归: PARTIAL/DISABLED 手拼 core tag 必须复刻 Forge {@code ChunkSerializer.write} 末尾对
 * LevelChunk 注入的 {@code ForgeCaps} (区块 capability 序列化), 否则任何挂区块 cap 的 mod (illusion)
 * 在异步存档时静默丢数据 —— 正常关服再开整个 world 的 ForgeCaps 全失。
 *
 * <p><b>为何用字节码断言</b>: 同 {@link com.shinoyuki.betterautosave.mixin.InFlightVanillaParityTest} ——
 * 本项目 test 任务裸 JUnit 无 MC 运行期, 无法在单测里构造真实 LevelChunk 调 capture。故对 build/classes 下
 * 编译产物断言 {@code ChunkCaptureProcedure} 内既读 chunk caps ({@code writeCapsToNBT}) 又以 "ForgeCaps"
 * 键写入 core tag。删任一侧 -> 计数归零 -> 断言挂。
 *
 * <p>判定: 删 capture 处 {@code writeCapsToNBT} 取值 -> 调用数 0, 第一条挂; 删 buildCoreTag 的
 * {@code put("ForgeCaps", ...)} -> "ForgeCaps" 常量消失, 第二条挂。
 */
class ChunkForgeCapsParityTest {

    private static final String CLASSES_DIR = "build/classes/java/main";
    private static final String TARGET = "com.shinoyuki.betterautosave.core.snapshot.ChunkCaptureProcedure";

    private ClassNode loadClass(String className) throws IOException {
        Path classFile = Path.of(CLASSES_DIR, className.replace('.', '/') + ".class");
        assertTrue(Files.exists(classFile),
                "编译产物缺失 (先跑 compileJava): " + classFile.toAbsolutePath());
        ClassNode node = new ClassNode();
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    /** PARTIAL/DISABLED 路径必须在主线程 capture 期读取区块 capability。 */
    @Test
    void capture_reads_chunk_forge_caps() throws IOException {
        ClassNode node = loadClass(TARGET);
        int writeCapsCalls = 0;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.name.equals("writeCapsToNBT")) {
                    writeCapsCalls++;
                }
            }
        }
        assertTrue(writeCapsCalls >= 1,
                "ChunkCaptureProcedure 必须在主线程 capture 期调 chunk.writeCapsToNBT() 取区块 capability; "
                        + "缺失则 PARTIAL/DISABLED 手拼 tag 丢 ForgeCaps (issue #8)");
    }

    /** 取到的 capability 必须以 vanilla 同名 "ForgeCaps" 键写进 core tag。 */
    @Test
    void core_tag_writes_forge_caps_key() throws IOException {
        ClassNode node = loadClass(TARGET);
        boolean hasForgeCapsKey = false;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof LdcInsnNode ldc && "ForgeCaps".equals(ldc.cst)) {
                    hasForgeCapsKey = true;
                }
            }
        }
        assertTrue(hasForgeCapsKey,
                "ChunkCaptureProcedure 必须以 \"ForgeCaps\" 键把区块 capability 写进 core tag; "
                        + "缺失则与 Forge ChunkSerializer.write 不对齐, 异步存档丢数据 (issue #8)");
    }
}
