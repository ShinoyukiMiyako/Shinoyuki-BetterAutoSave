package com.shinoyuki.betterautosave.core.load;

import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 区块加载 capability 子系统 off-thread 对称补齐的回归 (本次修复核心)。两层断言:
 *
 * <ol>
 *   <li><b>行为层</b> (真跑 {@link LoadDeferredActions} 捕获状态机): {@code LevelChunkCapsLoadMixin} 与
 *       {@code ChunkSerializerLoadMixin} 的 cap defer handler 共享同一条判据
 *       ({@code LoadDeferredActions.current() != null ? sink.add(action) : action.run()})。bare JUnit 无 MC 运行期
 *       不能真起 {@code new LevelChunk(...)}, 但能用一个探针 {@code Runnable} (代表 {@code initInternal} /
 *       {@code readCapsFromNBT}) 直接驱动这条真实状态机: 捕获期 (beginCapture 后, current()!=null) 必须 defer 进
 *       sink、绝不立即执行; 主线程期 (current()==null) 必须 inline 立即执行。删掉 defer 分支 -> 捕获期探针被立即
 *       执行 -> "捕获期零执行" 断言挂; 删掉 inline 分支 -> 主线程期探针不执行 -> "主线程立即执行" 断言挂。</li>
 *   <li><b>字节码层</b> (deletion-sensitive 计数, 同 {@link AsyncLoadSplitParityTest}): 断言两个 mixin handler 确实
 *       在 worker 路径 defer 这两类 cap 副作用 —— {@code LevelChunkCapsLoadMixin} 的 {@code <init>} redirect handler
 *       与 {@code ChunkSerializerLoadMixin} 的 {@code readCapsFromNBT} wrap handler 都必须经 {@code current()} 判据 +
 *       {@code add} 入 sink。删掉任一 handler / 退回直接执行 -> 计数变 -> 断言挂。</li>
 * </ol>
 */
class AsyncLoadCapsDeferParityTest {

    private static final String CLASSES_DIR = "build/classes/java/main";
    private static final String CAPS_MIXIN = "com.shinoyuki.betterautosave.mixin.LevelChunkCapsLoadMixin";
    private static final String SERIALIZER_MIXIN = "com.shinoyuki.betterautosave.mixin.ChunkSerializerLoadMixin";
    private static final String CAPS_HANDLER = "betterautosave$deferCapsGather";
    private static final String READCAPS_HANDLER = "betterautosave$deferReadCapsFromNBT";

    @AfterEach
    void clearCapture() {
        // 池化 worker 线程语义: 每个测试后必须清 ThreadLocal, 防残留 sink 污染后续测试 (与 ChunkLoadTask 的
        // finally endCapture 对齐)。本测试在单线程跑, 显式清更稳。
        LoadDeferredActions.endCapture();
    }

    // ---- 行为层: 真跑 LoadDeferredActions 捕获状态机 ----

    /**
     * 把 cap defer handler 的真实判据 ({@code current()!=null ? add : run}) 抽出来用探针驱动。返回探针是否被
     * <b>立即执行</b> (inline)。捕获期应返回 false (defer 进 sink, 未立即执行); 主线程期应返回 true (inline)。
     */
    private boolean runCapDeferDecision(LoadDeferredActions sinkOrNull, Runnable probe) {
        // 这一段必须与 LevelChunkCapsLoadMixin#betterautosave$deferCapsGather /
        // ChunkSerializerLoadMixin#betterautosave$deferReadCapsFromNBT 的判据逐字一致 (current() 非空才 defer)。
        LoadDeferredActions actions = LoadDeferredActions.current();
        if (actions != null) {
            actions.add(probe);
            return false;
        }
        probe.run();
        return true;
    }

    @Test
    void cap_init_is_deferred_during_worker_capture_not_executed_inline() {
        LoadDeferredActions sink = new LoadDeferredActions();
        LoadDeferredActions.beginCapture(sink);

        AtomicInteger initInternalRuns = new AtomicInteger();
        AtomicInteger readCapsRuns = new AtomicInteger();

        // worker 捕获期: 两个 cap 探针 (gather + deserialize) 都必须 defer, 绝不在捕获线程立即跑。
        boolean gatherInline = runCapDeferDecision(sink, initInternalRuns::incrementAndGet);
        boolean deserInline = runCapDeferDecision(sink, readCapsRuns::incrementAndGet);

        assertTrue(!gatherInline,
                "捕获期 cap gather (initInternal) 必须 defer 不 inline; inline 即 worker 线程派发 AttachCapabilitiesEvent");
        assertTrue(!deserInline,
                "捕获期 cap deserialize (readCapsFromNBT) 必须 defer 不 inline; inline 即 worker 线程跑第三方 cap deserializeNBT");
        assertEquals(0, initInternalRuns.get(),
                "捕获期 initInternal 探针必须零执行 (排进 sink 待主线程 replay); 删 defer 分支则此处变 1 -> 挂");
        assertEquals(0, readCapsRuns.get(),
                "捕获期 readCapsFromNBT 探针必须零执行 (排进 sink 待主线程 replay); 删 defer 分支则此处变 1 -> 挂");

        // 两个 cap 副作用必须按 read 执行序 (gather:161 先于 deserialize:162) 入列, replay 同序保证 "先 gather 后
        // deserialize" 依赖。drainCaptured 取快照后顺序回放, 此刻才执行。
        List<Runnable> deferred = sink.drainCaptured();
        assertEquals(2, deferred.size(), "两个 cap 副作用都必须排进 sink (gather + deserialize)");

        // 手动顺序回放 (不经 replayOnMainThread: 那条要 MinecraftServer + assertOnServerThread, 主线程断言已由
        // AsyncLoadRetryFallbackTest 覆盖; 本测试只验 defer 决策 + 入列顺序)。List 追加序 = read 执行序 (gather 先)。
        for (Runnable action : deferred) {
            action.run();
        }
        assertEquals(1, initInternalRuns.get(), "replay 后 gather 必须恰执行一次");
        assertEquals(1, readCapsRuns.get(), "replay 后 deserialize 必须恰执行一次");
    }

    @Test
    void cap_init_runs_inline_on_main_thread_when_not_capturing() {
        // 主线程 / FULL / fallback 重读路径: 从未 beginCapture, current()==null, cap 副作用必须 inline 立即执行
        // (与 vanilla 零偏差), 绝不排队。
        assertEquals(null, LoadDeferredActions.current(), "未 beginCapture 时 current() 必须为 null");

        AtomicInteger runs = new AtomicInteger();
        boolean inline = runCapDeferDecision(null, runs::incrementAndGet);

        assertTrue(inline, "主线程期 (current()==null) cap 副作用必须 inline; 删 inline 分支则 false -> 挂");
        assertEquals(1, runs.get(),
                "主线程期 cap 探针必须立即执行一次 (与 vanilla 同步 read 等价); 删 inline 分支则 0 -> 挂");
    }

    // ---- 字节码层: 断言两个 mixin handler 确实 defer cap 副作用 ----

    @Test
    void caps_gather_redirect_handler_defers_via_current_and_add() throws IOException {
        MethodNode handler = loadMethod(CAPS_MIXIN, CAPS_HANDLER);
        assertEquals(1, countCalls(handler, "current"),
                "LevelChunkCapsLoadMixin 的 <init> redirect handler 必须经 LoadDeferredActions.current() 判 worker/主线程");
        assertEquals(1, countCalls(handler, "add"),
                "worker 路径必须 add 把 initInternal 排进 sink (defer 派发 AttachCapabilitiesEvent); 删 add -> worker 直接 gather 崩");
        assertEquals(1, countCalls(handler, "initInternal"),
                "inline 分支必须保留 capProvider.initInternal() (主线程零偏差); 注: add 用方法引用 ::initInternal 不计 INVOKE");
    }

    @Test
    void readcaps_wrap_handler_defers_via_current_and_add() throws IOException {
        // wrap handler 的 defer 分支用 lambda (() -> original.call(...)), defer 调用落在 javac 合成的 lambda 方法里,
        // 故对 handler 本体 + 其 lambda 一并计数。
        List<MethodNode> methods = methodAndLambdas(SERIALIZER_MIXIN, READCAPS_HANDLER);
        assertEquals(1, totalCalls(methods, "current"),
                "ChunkSerializerLoadMixin 的 readCapsFromNBT wrap handler 必须经 current() 判 worker/主线程");
        assertTrue(totalCalls(methods, "add") >= 1,
                "worker 路径必须 add 把 readCapsFromNBT 排进 sink (defer 第三方 cap deserialize); 删 add -> worker 直接反序列化崩");
        assertTrue(totalCalls(methods, "call") >= 2,
                "wrap handler 必须保留 original.call: defer 分支 lambda 内 call + inline 分支 call (各路径都不丢原 INVOKE)");
    }

    // ---- ASM 辅助 (同 AsyncLoadSplitParityTest) ----

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
        for (MethodNode m : loadClass(className).methods) {
            if (m.name.equals(methodName)) {
                return m;
            }
        }
        fail("方法未找到: " + className + "#" + methodName);
        return null;
    }

    private List<MethodNode> methodAndLambdas(String className, String handler) throws IOException {
        List<MethodNode> out = new ArrayList<>();
        String lambdaPrefix = "lambda$" + handler + "$";
        for (MethodNode m : loadClass(className).methods) {
            if (m.name.equals(handler) || m.name.startsWith(lambdaPrefix)) {
                out.add(m);
            }
        }
        assertTrue(!out.isEmpty(), "未找到 handler 或其 lambda: " + className + "#" + handler);
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
}
