package com.shinoyuki.betterautosave.core.load;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 异步加载 (M1) 切分机制的字节码回归。同 {@link com.shinoyuki.betterautosave.core.snapshot.AuditFixParityTest}:
 * bare JUnit 无 MC 运行期, 无法在单测里真跑 mixin / 起 ChunkMap, 故对 build/classes 编译产物按目标方法内的调用
 * 计数断言, deletion-sensitive (删某步 -> 计数归零 -> 断言挂)。覆盖 docs/ASYNC_LOAD_DESIGN.md 第四/六/九节定下的
 * 三条不可回退的不变式:
 *
 * <ul>
 *   <li>Codec 串行锁存在 (第四节并发护栏): ChunkLoadTask.execute 必须在 LoadCodecGuard.lock/unlock 之间跑
 *       Operation.call(=vanilla read), 否则多 worker 并发解码读坏 static 分发 Codec 的 DFU 缓存。</li>
 *   <li>主线程独占副作用确实被 defer (第六节切分纪律): ChunkSerializerLoadMixin 四个 redirect handler 必须经
 *       LoadDeferredActions.current() 判据决定 inline/defer, 且 defer 分支 add 进延迟列表 —— 即 POI/光照/
 *       ChunkDataEvent.Load 在 off-thread 路径上绝不直接在 worker 执行。</li>
 *   <li>延迟副作用回主线程回放 + fallback 兜底 (第四节钩子落点): ChunkMapLoadMixin.wrap 必须 replayOnMainThread
 *       (把截走的副作用落回主线程) 且有 recordChunkLoadFallback (worker 解析失败退回 vanilla 主线程 read)。</li>
 * </ul>
 */
class AsyncLoadSplitParityTest {

    private static final String CLASSES_DIR = "build/classes/java/main";

    private Path mainClassesDir() {
        for (String candidate : new String[]{
                CLASSES_DIR,
                "../classes/java/main",
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
    void codec_guard_brackets_read_on_worker() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.core.load.ChunkLoadTask", "execute");
        // lock 与 unlock 都必须出现; unlock 在 finally 双分支 (正常 + 异常) 各一次故 >=1, lock 恰一次。
        assertTrue(countCalls(m, "lock") >= 1,
                "Codec 锁: ChunkLoadTask.execute 必须 LoadCodecGuard.lock() 串行化 static Codec 解码");
        assertTrue(countCalls(m, "unlock") >= 1,
                "Codec 锁: ChunkLoadTask.execute 必须在 finally unlock(), 否则锁泄漏卡死全部 load worker");
        assertTrue(countCalls(m, "call") >= 1,
                "ChunkLoadTask.execute 必须经 Operation.call 调起被 wrap 的 vanilla read");
    }

    @Test
    void redirect_handlers_defer_via_deferred_actions() throws IOException {
        // 四个 handler 各自: current() 判 off-thread, defer 分支 add()。任一缺失 = 该副作用要么不判线程直接 inline
        // (POI/光照在 worker 执行 -> 跨线程写崩), 要么 defer 但不回放 (副作用丢失)。
        String[] handlers = {
                "betterautosave$deferCheckConsistency",
                "betterautosave$deferRetainData",
                "betterautosave$deferQueueSectionData",
                "betterautosave$deferLoadEvent"};
        for (String handler : handlers) {
            MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.ChunkSerializerLoadMixin", handler);
            assertEquals(1, countCalls(m, "current"),
                    handler + ": 必须经 LoadDeferredActions.current() 判 off-thread 捕获 vs 主线程 inline");
            assertEquals(1, countCalls(m, "add"),
                    handler + ": defer 分支必须 add 进延迟列表 (off-thread 不在 worker 执行该主线程独占副作用)");
        }
    }

    @Test
    void wrap_replays_on_main_thread_and_has_fallback() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.ChunkMapLoadMixin", "betterautosave$wrapRead");
        assertEquals(1, countCalls(m, "replayOnMainThread"),
                "wrap: worker join 出 ProtoChunk 后必须 replayOnMainThread 把 POI/光照/事件副作用落回主线程");
        assertEquals(1, countCalls(m, "recordChunkLoadFallback"),
                "wrap: worker 解析失败必须 recordChunkLoadFallback 并退回 vanilla 主线程 read (兜底)");
        assertTrue(countCalls(m, "isSameThread") >= 1,
                "wrap: 必须用 mainThreadExecutor.isSameThread() 校验在主线程, 防 coremod 改续段线程后回放越界");
    }
}
