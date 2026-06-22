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
 * 异步加载 v2 降级回退 + 边界 + 跨线程交付不变式的字节码回归 (docs/ASYNC_LOAD_DESIGN.md 第六/九节)。同
 * {@link AsyncLoadSplitParityTest}: bare JUnit 无 MC 运行期 (构造 ChunkLoadTask 需 ServerLevel/ProtoChunk 等
 * MC 类), 无法真跑 execute()。故对 build/classes 编译产物按目标方法内调用计数断言, deletion-sensitive
 * (删某步 -> 计数变 -> 断言挂):
 *
 * <ul>
 *   <li><b>worker 内重试</b>: ChunkLoadTask.execute 必须有 recordChunkLoadRetried (重试路径存在) 且每次尝试前
 *       clearForRetry (复位上次失败尝试残留的延迟副作用, 防成功那次带出叠加陈旧 POI/光照写)。</li>
 *   <li><b>不静默吞错</b>: 重试耗尽必须 onUnhandledError -> completeExceptionally 把 worker 解析失败异常完成
 *       future, 让 replay-stage exceptionallyAsync 走 vanilla 主线程 read 兜底; 绝不在 worker 内吞掉异常返回半解析 chunk。</li>
 *   <li><b>主线程独占断言护栏</b>: LoadDeferredActions.replayOnMainThread 必须 assertOnServerThread,
 *       ChunkLoadTask.execute 必须 assertOnWorkerThread —— 越界 (回放误跑 worker / 解析误跑主线程) 显式炸出
 *       而非静默竞态 (第六节断言护栏)。</li>
 *   <li><b>v2 deferred 经返回值显式交付</b>: ChunkLoadTask.execute 必须在 read 成功那刻 drainCaptured 取延迟副作用
 *       快照并构造 LoadResult 带出 (v2 不靠 v1 的 join 隐式共享传 deferred; 删掉显式交付退回隐式共享此断言挂)。</li>
 * </ul>
 */
class AsyncLoadRetryFallbackTest {

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

    /** 重试路径必须存在且每次尝试前复位延迟列表 (防成功那次回放叠加上次失败残留 = POI/光照脏写)。 */
    @Test
    void worker_retries_and_clears_deferred_between_attempts() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.core.load.ChunkLoadTask", "execute");
        assertEquals(1, countCalls(m, "recordChunkLoadRetried"),
                "execute 必须在重试分支 recordChunkLoadRetried (worker 内重试 read, 省主线程 fallback 开销)");
        assertEquals(1, countCalls(m, "clearForRetry"),
                "execute 每次尝试前必须 clearForRetry 复位 deferred 列表; 缺失则重试成功后回放叠加上次失败尝试的"
                        + "残留 POI/光照副作用 (脏写)");
    }

    /** 重试耗尽不静默吞错: 经 onUnhandledError -> completeExceptionally 把失败异常完成 future, 触发主线程 fallback。 */
    @Test
    void exhausted_retries_propagate_to_exceptional_completion() throws IOException {
        MethodNode onError = loadMethod("com.shinoyuki.betterautosave.core.load.ChunkLoadTask", "onUnhandledError");
        assertEquals(1, countCalls(onError, "completeExceptionally"),
                "onUnhandledError 必须 completeExceptionally 异常完成 future (唤醒阻塞 join 的主线程走 vanilla "
                        + "fallback); 缺失则 worker 解析失败后主线程 join 永挂或拿到半解析 chunk");
    }

    /**
     * M3 load-worker 占用 gauge: execute 进入时 inc (codec 锁外, 含阻塞等锁的占用), 退出 finally dec, 否则
     * /betterautosave debug 的 "In-flight parsing" 失真 (inc 缺失恒 0 看不到占用; dec 缺失永久正偏移, 一次加载就把
     * gauge 顶到 worker 数永不归零)。
     *
     * <p>inc 在 try 外只 emit 一次, 直接 == 1。dec 在 finally 体内, javac 会把 finally 沿正常出口 + 异常出口各内联
     * 一份字节码, 故同一 finally 内的调用计数 >1 是编译产物常态而非逻辑重复。为剥离这层编译噪声又保持
     * deletion-sensitive: 断言 dec 计数 == 同 finally 内既有兄弟调用 recordLoadDeserializeNs 的计数 (二者必随 finally
     * 复制次数同步缩放), 即 "dec 与延迟记录在同一 finally"。删 dec -> 计数 != recordLoadDeserializeNs -> 挂; 删 dec
     * 同时 finally 仅剩别的调用也会暴露差值。
     */
    @Test
    void execute_balances_in_flight_load_parsing_gauge() throws IOException {
        MethodNode execute = loadMethod("com.shinoyuki.betterautosave.core.load.ChunkLoadTask", "execute");
        assertEquals(1, countCalls(execute, "incInFlightLoadParsing"),
                "execute 进入时必须 incInFlightLoadParsing 一次 (codec 锁外, 含阻塞等锁的占用)");
        int decCount = countCalls(execute, "decInFlightLoadParsing");
        int finallySibling = countCalls(execute, "recordLoadDeserializeNs");
        assertTrue(decCount >= 1,
                "execute finally 必须 decInFlightLoadParsing 覆盖成功/重试耗尽抛/异常全路径; 否则 gauge 永久泄漏");
        assertEquals(finallySibling, decCount,
                "dec 必须与 recordLoadDeserializeNs 同在 execute 的最外 finally (计数随 finally 内联份数同步); "
                        + "二者不等说明 dec 漏在某条出口或被错放进 try, 占用 gauge 会在该出口泄漏");
    }

    /** 断言护栏: 回放必须主线程断言, 解析必须 worker 断言 (越界显式炸出而非静默跨线程竞态)。 */
    @Test
    void thread_boundary_asserts_guard_replay_and_parse() throws IOException {
        MethodNode replay = loadMethod("com.shinoyuki.betterautosave.core.load.LoadDeferredActions",
                "replayOnMainThread");
        assertEquals(1, countCalls(replay, "assertOnServerThread"),
                "replayOnMainThread 必须 assertOnServerThread: POI/光照/事件回放越界跑到 worker 立刻炸出");

        MethodNode execute = loadMethod("com.shinoyuki.betterautosave.core.load.ChunkLoadTask", "execute");
        assertEquals(1, countCalls(execute, "assertOnWorkerThread"),
                "execute 必须 assertOnWorkerThread: 纯解析误在主线程内联 (堵 tick) 立刻炸出");
    }

    /**
     * v2 跨线程交付: worker read-stage 必须把截走的 deferred 副作用经 {@code drainCaptured} 取快照、构造
     * {@code LoadResult} 显式作为 future 值带出 (而非 v1 "主线程预持列表 + join 等可见" 的隐式共享)。删掉显式交付
     * 退回隐式 join 共享 -> drainCaptured 计数归零或 LoadResult 不再构造 -> 断言挂。
     */
    @Test
    void execute_hands_deferred_out_via_return_value() throws IOException {
        MethodNode execute = loadMethod("com.shinoyuki.betterautosave.core.load.ChunkLoadTask", "execute");
        assertEquals(1, countCalls(execute, "drainCaptured"),
                "execute 必须在 read 成功那刻 drainCaptured 取延迟副作用快照 (随即封进 LoadResult 交给 future); "
                        + "缺失说明 deferred 没被显式带出, 退回 v1 的 join 隐式共享");
        assertTrue(countConstructorsOf(execute, "com/shinoyuki/betterautosave/core/load/LoadResult") >= 1,
                "execute 必须构造 LoadResult(chunk, deferred) 把解析结果 + 延迟副作用一并经 future 值显式交给 "
                        + "replay-stage; 缺失说明 worker 没把产物经返回值流给主线程");
    }

    /** 统计 method 内对指定 owner 的构造调用 (INVOKESPECIAL <init>)。 */
    private int countConstructorsOf(MethodNode m, String ownerInternalName) {
        int n = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.name.equals("<init>") && call.owner.equals(ownerInternalName)) {
                n++;
            }
        }
        return n;
    }
}
