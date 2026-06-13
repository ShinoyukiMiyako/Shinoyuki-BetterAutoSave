package com.shinoyuki.betterautosave.mixintests;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * saveAllChunks 拦截层 ci.cancel 分布的字节码回归.
 *
 * <p>现场: 运营中 /save-all flush 时 ChunkMapMixin 的 flush 分支若只 log 不 ci.cancel, vanilla
 * saveAllChunks(true) 的 do-while ("本轮任一 save() 返 true 即再来一轮") 照常跑; 运营态
 * ChunkMapSaveMixin 对 clean LevelChunk 也 setReturnValue(true) (vanilla 原返 false), 使该判据恒真 ->
 * 主线程死循环 -> ServerWatchdog 60s 强杀 (issue #5). 修复让运营 flush 走入队路径后必须 ci.cancel
 * 跳过 vanilla do-while.
 *
 * <p><b>为何用字节码断言</b>: 本项目 test 任务是裸 JUnit Platform 无 mixin agent (见
 * InFlightVanillaParityTest / SavedDataDirtyVisibilityTest 说明), @Inject 方法无法用真实 vanilla
 * ChunkMap 运行期实例调用. ci.cancel 是控制流取消调用, 没有可抽取的纯业务逻辑可单测. 故对 build/classes
 * 下编译产物 (mixin 应用前) 做字节码断言: 统计 @Inject 方法内对 CallbackInfo.cancel 的调用点数 ——
 * 这是唯一既 deletion-sensitive 又不需 mixin 运行期的手段.
 *
 * <p>判定标准: 删运营 flush 路径的 ci.cancel -> interceptSaveAllChunks 内 cancel 调用从 2 降到 1,
 * 死循环 bug 复发, 第一个断言挂; 把 ci.cancel 误塞进共享入队 helper -> 第二个断言挂.
 */
class SaveAllChunksCancelParityTest {

    private static final String CALLBACK_INFO_OWNER =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";

    private MethodNode loadMethod(String className, String methodName) throws IOException {
        String resource = className.replace('.', '/') + ".class";
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "编译产物缺失 (不在 test classpath): " + resource);
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

    private int countCallbackInfoCancel(MethodNode m) {
        int cancels = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.name.equals("cancel")
                    && call.owner.equals(CALLBACK_INFO_OWNER)) {
                cancels++;
            }
        }
        return cancels;
    }

    /**
     * interceptSaveAllChunks 内必须有且只有两处 ci.cancel: 周期 autosave (flush=false) 路径 1 +
     * 运营中 /save-all flush 路径 1. 关服 flush 路径不 cancel (放行 vanilla 同步兜底). 删运营路径的
     * cancel 则降到 1 -> 死循环 bug 复发.
     */
    @Test
    void intercept_save_all_chunks_cancels_in_periodic_and_operational_flush() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.ChunkMapMixin",
                "betterautosave$interceptSaveAllChunks");

        assertEquals(2, countCallbackInfoCancel(m),
                "interceptSaveAllChunks 必须在周期 autosave 与运营 flush 两条路径各 ci.cancel 一次 "
                        + "(关服 flush 不 cancel); 漏掉运营 flush 的 cancel 则降到 1, vanilla do-while 死循环复发");
    }

    /**
     * 共享入队 helper 绝不能自行 ci.cancel: cancel 责任留在调用点 (周期/运营各一处), helper 仅入队.
     * 若 cancel 误下沉到 helper, 关服路径 (复用 drain 后不应 cancel) 与退化路径也会被连带 cancel.
     */
    @Test
    void enqueue_helper_does_not_cancel() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.ChunkMapMixin",
                "betterautosave$enqueueDirtyChunks");

        assertEquals(0, countCallbackInfoCancel(m),
                "betterautosave$enqueueDirtyChunks 仅负责入队, cancel 责任留在调用点; helper 内出现 cancel "
                        + "会污染关服与退化路径");
    }

    /** 防御性: 确认两个被测方法都真实存在 (重命名会让上面计数静默归零误判 PASS). */
    @Test
    void target_methods_exist() throws IOException {
        loadMethod("com.shinoyuki.betterautosave.mixin.ChunkMapMixin",
                "betterautosave$interceptSaveAllChunks");
        loadMethod("com.shinoyuki.betterautosave.mixin.ChunkMapMixin",
                "betterautosave$enqueueDirtyChunks");
    }
}
