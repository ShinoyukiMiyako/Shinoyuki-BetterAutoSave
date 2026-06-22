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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 异步加载 v2 (future 链, 非阻塞) 切分机制的字节码回归。同
 * {@link com.shinoyuki.betterautosave.core.snapshot.AuditFixParityTest}: bare JUnit 无 MC 运行期, 无法在单测里真跑
 * mixin / 起 ChunkMap, 故对 build/classes 编译产物按目标方法内的调用计数断言, deletion-sensitive (删某步 -> 计数
 * 归零 -> 断言挂)。覆盖 docs/ASYNC_LOAD_DESIGN.md 第四/六/九节 + v2 不阻塞不变式:
 *
 * <ul>
 *   <li>Codec 串行锁存在 (第四节并发护栏): ChunkLoadTask.execute 必须在 LoadCodecGuard.lock/unlock 之间直接调
 *       ChunkSerializer.read(=vanilla read), 否则多 worker 并发解码读坏 static 分发 Codec 的 DFU 缓存。</li>
 *   <li>主线程独占副作用确实被 defer (第六节切分纪律): ChunkSerializerLoadMixin 四个 redirect handler 必须经
 *       LoadDeferredActions.current() 判据决定 inline/defer, 且 defer 分支 add 进延迟列表 —— 即 POI/光照/
 *       ChunkDataEvent.Load 在 off-thread 路径上绝不直接在 worker 执行。</li>
 *   <li>延迟副作用回主线程回放 + fallback 兜底 (第四节钩子落点): ChunkMapLoadMixin 的 redirect handler 链 (含其
 *       合成 lambda) 必须 replayOnMainThread (把截走的副作用落回主线程) 且有 recordChunkLoadFallback (worker 解析
 *       失败退回 vanilla 主线程 read)。</li>
 *   <li><b>v2 主线程零阻塞</b> (任务核心不变式): redirect handler 链及其全部合成 lambda 字节码里绝不出现
 *       {@code CompletableFuture.join} / {@code CompletableFuture.get} —— v2 钩点从 v1 的 @WrapOperation+join 改为
 *       @Redirect thenApplyAsync 两段 future 链, 主线程必须直接返回未完成 future 让 worker 并行 deserialize,
 *       一旦退回 v1 的 join 阻塞此断言挂。</li>
 * </ul>
 */
class AsyncLoadSplitParityTest {

    private static final String CLASSES_DIR = "build/classes/java/main";
    private static final String MIXIN = "com.shinoyuki.betterautosave.mixin.ChunkMapLoadMixin";
    private static final String CHAIN_HANDLER = "betterautosave$asyncLoadChain";

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

    private ClassNode loadClass(String className) throws IOException {
        Path classFile = mainClassesDir().resolve(className.replace('.', '/') + ".class");
        assertTrue(Files.exists(classFile), "编译产物缺失 (先跑 compileJava): " + classFile.toAbsolutePath());
        ClassNode node = new ClassNode();
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private MethodNode loadMethod(String className, String methodName) throws IOException {
        ClassNode node = loadClass(className);
        for (MethodNode m : node.methods) {
            if (m.name.equals(methodName)) {
                return m;
            }
        }
        fail("方法未找到: " + className + "#" + methodName);
        return null;
    }

    /**
     * 收集 handler 方法本体 + javac 为其 lambda 编出的全部合成方法 (name == handler 或以
     * {@code lambda$<handler>$} 起头)。v2 把 replay / fallback / offer 等逻辑拆进 thenComposeAsync /
     * thenApplyAsync / exceptionallyAsync 的 lambda, javac 编成合成方法, 断言必须跨这些方法聚合计数, 否则调用藏在
     * lambda 里漏检。
     */
    private List<MethodNode> chainMethodAndItsLambdas(String className, String handler) throws IOException {
        ClassNode node = loadClass(className);
        List<MethodNode> out = new ArrayList<>();
        String lambdaPrefix = "lambda$" + handler + "$";
        for (MethodNode m : node.methods) {
            if (m.name.equals(handler) || m.name.startsWith(lambdaPrefix)) {
                out.add(m);
            }
        }
        assertTrue(!out.isEmpty(), "未找到 handler 方法或其 lambda: " + className + "#" + handler);
        return out;
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

    private int totalCalls(List<MethodNode> methods, String calleeName) {
        int n = 0;
        for (MethodNode m : methods) {
            n += countCalls(m, calleeName);
        }
        return n;
    }

    /**
     * 仅统计指定 owner 上的同名调用。no-block 断言必须按 owner=CompletableFuture 过滤: 否则 {@code Optional.get()}
     * (链里 {@code opt.get()} 取 CompoundTag) 会被误计入 {@code get} 阻塞调用导致假阳性。
     */
    private int totalCallsOnOwner(List<MethodNode> methods, String owner, String calleeName) {
        int n = 0;
        for (MethodNode m : methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.equals(owner) && call.name.equals(calleeName)) {
                    n++;
                }
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
        // v2: worker read-stage 直接调 ChunkSerializer.read (v1 是经 Operation.call 调被 wrap 的 read)。
        assertTrue(countCalls(m, "read") >= 1,
                "ChunkLoadTask.execute 必须在锁内直接调 ChunkSerializer.read 跑 vanilla 纯解析");
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
    void chain_replays_on_main_thread_and_has_fallback() throws IOException {
        List<MethodNode> chain = chainMethodAndItsLambdas(MIXIN, CHAIN_HANDLER);
        assertEquals(1, totalCalls(chain, "replayOnMainThread"),
                "chain: worker read-stage 完成后 replay-stage 必须 replayOnMainThread 把 POI/光照/事件副作用落回主线程");
        assertEquals(1, totalCalls(chain, "recordChunkLoadFallback"),
                "chain: worker 解析失败必须经 exceptionallyAsync recordChunkLoadFallback 并退回 vanilla 主线程 read (兜底)");
        // v2 replay-stage 在主线程补做 vanilla 续段里 read 之后的 markPosition (经 accessor @Invoker)。
        assertEquals(1, totalCalls(chain, "betterautosave$markPosition"),
                "chain: replay-stage 必须 markPosition 对齐 vanilla 续段 (read 后写 chunkTypeCache)");
    }

    @Test
    void main_thread_chain_never_blocks_on_future() throws IOException {
        List<MethodNode> chain = chainMethodAndItsLambdas(MIXIN, CHAIN_HANDLER);
        String cf = "java/util/concurrent/CompletableFuture";
        assertEquals(0, totalCallsOnOwner(chain, cf, "join"),
                "v2 主线程编排绝不能 CompletableFuture.join 阻塞: 必须直接返回 CompletableFuture 让 worker 并行 "
                        + "deserialize (退回 v1 的 result.join() 此断言挂)");
        assertEquals(0, totalCallsOnOwner(chain, cf, "get"),
                "v2 主线程编排绝不能 CompletableFuture.get 阻塞: 同 join, 主线程拿到 future 必须直接返回");
        // join 还有无参与 timeout 重载, get 同理; 上面按精确 name 已覆盖 (CompletableFuture.join()/get() 名恒为
        // join/get)。getNow 是非阻塞取值不在禁列, 但 v2 链根本不用它, 一并确认零出现防 future 误用。
        assertEquals(0, totalCallsOnOwner(chain, cf, "getNow"),
                "v2 主线程编排不应出现 CompletableFuture.getNow (链全程 compose/apply 不取即时值)");
    }
}
