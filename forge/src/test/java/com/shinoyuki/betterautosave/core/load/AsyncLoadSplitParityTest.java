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
 *   <li>Codec 细粒度锁 (v2.1 L1): ChunkLoadTask.execute 直接调 ChunkSerializer.read 且<b>整段不再持锁</b>;
 *       唯一跨线程竞态的结构解码由 ChunkSerializerLoadMixin 的两个 @WrapOperation handler 在 read 内部精确锁住
 *       (各 LoadCodecGuard.lock 一次 + finally unlock 一次), 其余解码无锁并行。删掉这两处锁 -> 结构 dispatch
 *       Codec / RegistryOps 缓存被多 worker 并发读坏 (数据损坏)。</li>
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
    void worker_calls_read_without_bracketing_whole_read_in_lock() throws IOException {
        // v2.1 L1: execute 直接调 ChunkSerializer.read 跑 vanilla 解析, 但<b>整段不再持锁</b> (锁收缩进 read 内部的
        // 结构解码 @WrapOperation, 见 structure_decode_handlers_bracket_codec_lock)。execute 自身既不 lock 也不 unlock。
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.core.load.ChunkLoadTask", "execute");
        assertTrue(countCalls(m, "read") >= 1,
                "ChunkLoadTask.execute 必须直接调 ChunkSerializer.read 跑 vanilla 纯解析");
        assertEquals(0, countCalls(m, "lock"),
                "v2.1 L1: execute 不得再 LoadCodecGuard.lock() 包整段 read (锁已收缩到 read 内结构解码 handler); "
                        + "若此处仍 lock 则退回 v2 粗粒度串行 '一次只解一个'");
        assertEquals(0, countCalls(m, "unlock"),
                "v2.1 L1: execute 不得再 unlock (锁不在此); lock/unlock 均应只出现在结构解码 @WrapOperation handler 内");
    }

    /**
     * v2.1 L1 细粒度锁 (deletion-sensitive, 核心数据安全闸门): read 内唯一跨线程竞态的结构拼图解码
     * (unpackStructureStart / unpackStructureReferences, 经共享 dispatch Codec + RegistryOps 缓存) 必须各由一个
     * ChunkSerializerLoadMixin 的 @WrapOperation handler 用 LoadCodecGuard.lock()/finally unlock() 精确包住,
     * 内部经 original.call(...) 触发被锁的原解码。删掉任一处 lock/unlock -> 该结构 Codec 在 worker 间并发解码读坏
     * 共享缓存 = 罕见数据损坏。lock 在 try 外恰一次 (==1); unlock 在 finally, javac 沿正常 + 异常出口各内联一份故
     * >=1; original.call 触发被 wrap 的原 INVOKE 恰一次 (==1)。
     */
    @Test
    void structure_decode_handlers_bracket_codec_lock() throws IOException {
        String[] handlers = {
                "betterautosave$lockStructureStartDecode",
                "betterautosave$lockStructureReferencesDecode"};
        for (String handler : handlers) {
            MethodNode m = loadMethod("com.shinoyuki.betterautosave.mixin.ChunkSerializerLoadMixin", handler);
            assertEquals(1, countCalls(m, "lock"),
                    handler + ": 必须 LoadCodecGuard.lock() 恰一次串行化结构解码 (唯一跨线程竞态 Codec)");
            assertTrue(countCalls(m, "unlock") >= 1,
                    handler + ": 必须在 finally unlock() (>=1, javac 沿正常+异常出口内联); 缺失则锁泄漏卡死 load worker");
            assertEquals(1, countCalls(m, "call"),
                    handler + ": 必须经 Operation.call() 在锁内触发被 wrap 的原结构解码 (而非裸调或不调)");
        }
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
        // handleAsync 单点承接两类失败源 (workerError!=null 的 worker 解析失败 + replay-stage 自身抛), 各自 record
        // 一次 fallback, 故计 2。删掉两源区分 (回退成共用一条 fallback) -> 计数变 1 -> 此断言挂。
        assertEquals(2, totalCalls(chain, "recordChunkLoadFallback"),
                "chain: worker 解析失败与主线程 replay 失败两类各经一次 recordChunkLoadFallback 退回 vanilla 主线程 read");
        // 两类失败源必须各记各的 LOGGER.error (deserialize-failed vs replay-failed), 不共用一条误导文案。
        assertTrue(totalCalls(chain, "error") >= 2,
                "chain: worker 解析失败与 replay 失败必须分别记 LOGGER.error, 不把 replay 失败误报成反序列化损坏");
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
