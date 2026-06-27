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
 * Tier A 异步 POI 预读切分机制的字节码回归。同 {@link AsyncLoadSplitParityTest}: bare JUnit 无 MC 运行期, 无法
 * 真跑 mixin / 起 ChunkMap, 故对 build/classes 编译产物按目标方法内的调用计数与顺序断言, deletion-sensitive
 * (删某步 -> 计数归零 / 顺序反转 -> 断言挂)。覆盖 Tier A 设计的四条不变式:
 *
 * <ul>
 *   <li><b>worker 只读不写</b>: {@code ChunkLoadTask.execute} 在 worker 上 fire POI 列异步读盘恰一次
 *       ({@code betterautosave$readColumnNbtFuture}), 绝不在 worker 上 populate (填缓存写非并发
 *       {@code storage}/{@code dirty}/{@code DistanceTracker} 必须留主线程)。</li>
 *   <li><b>主线程填缓存且严格先于 replay</b>: {@code ChunkMapLoadMixin} 的 replay-stage 用预读字节
 *       {@code betterautosave$populateColumnOnMain} 恰一次, 且<b>顺序上严格先于</b> {@code replayOnMainThread}
 *       (deferred 里含 {@code checkConsistencyWithBlocks -> getOrLoad}, POI 缓存必须先填好才命中 O(1) 而非阻塞读盘)。</li>
 *   <li><b>SectionStorage 读写两步真分离</b>: {@code betterautosave$readColumnNbtFuture} 只触底层 IOWorker
 *       ({@code tryRead}), 绝不 parse 填缓存 ({@code readColumn}); {@code betterautosave$populateColumnOnMain}
 *       有 get 护栏 (已加载跳过) + readColumn parse 各一次。</li>
 * </ul>
 */
class AsyncLoadPoiPrefetchParityTest {

    private static final String CLASSES_DIR = "build/classes/java/main";
    private static final String SECTION_MIXIN = "com.shinoyuki.betterautosave.mixin.SectionStorageLoadMixin";
    private static final String CHUNK_MAP_MIXIN = "com.shinoyuki.betterautosave.mixin.ChunkMapLoadMixin";
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

    /** 同 AsyncLoadSplitParityTest: handler 本体 + javac 为其 lambda 编出的全部合成方法 (populate/replay 在最内层 lambda)。 */
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

    @Test
    void worker_prefetches_poi_off_thread_and_never_populates() throws IOException {
        MethodNode m = loadMethod("com.shinoyuki.betterautosave.core.load.ChunkLoadTask", "execute");
        assertEquals(1, countCalls(m, "betterautosave$readColumnNbtFuture"),
                "ChunkLoadTask.execute 必须恰一次 fire POI 列异步读盘 (Tier A 预读, 与区块反序列化并行); "
                        + "删除则主线程 replay 退回 vanilla 同步阻塞读盘 (spark 实证那 ~15% 主线程残余)");
        assertEquals(0, countCalls(m, "betterautosave$populateColumnOnMain"),
                "worker 绝不能 populate POI 缓存: 填缓存写非并发 storage/dirty/DistanceTracker 必须留主线程");
    }

    @Test
    void main_chain_populates_poi_strictly_before_replay() throws IOException {
        List<MethodNode> chain = chainMethodAndItsLambdas(CHUNK_MAP_MIXIN, CHAIN_HANDLER);
        assertEquals(1, totalCalls(chain, "betterautosave$populateColumnOnMain"),
                "replay-stage 必须恰一次用预读字节填 POI 缓存; 删除则 deferred checkConsistencyWithBlocks 的 "
                        + "getOrLoad 退回主线程阻塞读盘 (Tier A 失效)");
        assertTrue(totalCalls(chain, "poiColumnNbt") >= 1,
                "replay-stage 必须读 LoadResult.poiColumnNbt (null 判未预读 + 取值); 缺失则无视预读结果");
        assertPopulateStrictlyBeforeReplay(chain);
    }

    /**
     * 顺序闸门 (deletion+reorder-sensitive): 在同一 replay lambda 内, {@code populateColumnOnMain} 的指令下标必须
     * 严格小于 {@code replayOnMainThread} —— POI 缓存必须在 deferred {@code checkConsistencyWithBlocks} 回放<b>之前</b>
     * 填好, 否则回放里的 {@code getOrLoad} 仍 cache miss 而在主线程阻塞读盘, Tier A 形同虚设。
     */
    private void assertPopulateStrictlyBeforeReplay(List<MethodNode> chain) {
        for (MethodNode m : chain) {
            int populateIdx = -1;
            int replayIdx = -1;
            int i = 0;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    if (call.name.equals("betterautosave$populateColumnOnMain")) {
                        populateIdx = i;
                    } else if (call.name.equals("replayOnMainThread")) {
                        replayIdx = i;
                    }
                }
                i++;
            }
            if (populateIdx >= 0 && replayIdx >= 0) {
                assertTrue(populateIdx < replayIdx,
                        "populate 必须严格先于 replayOnMainThread (POI 缓存须在 deferred checkConsistency 回放前填好)");
                return;
            }
        }
        fail("未找到同时含 populateColumnOnMain 与 replayOnMainThread 的 replay lambda");
    }

    @Test
    void section_mixin_splits_read_offthread_from_parse_on_main() throws IOException {
        MethodNode readFuture = loadMethod(SECTION_MIXIN, "betterautosave$readColumnNbtFuture");
        assertEquals(1, countCalls(readFuture, "betterautosave$invokeTryRead"),
                "readColumnNbtFuture 必须经 invokeTryRead 触发底层 IOWorker 异步读盘恰一次 (仅磁盘字节, 线程安全)");
        assertEquals(0, countCalls(readFuture, "betterautosave$invokeReadColumn"),
                "readColumnNbtFuture (worker 路径) 绝不能 parse 填缓存: 那会跨线程写非并发 storage");

        MethodNode populate = loadMethod(SECTION_MIXIN, "betterautosave$populateColumnOnMain");
        assertEquals(1, countCalls(populate, "betterautosave$invokeGet"),
                "populateColumnOnMain 必须 get 护栏恰一次: 该列已加载则跳过, 防陈旧预读字节覆盖活 POI 数据");
        assertEquals(1, countCalls(populate, "betterautosave$invokeReadColumn"),
                "populateColumnOnMain 必须 readColumn 解析填缓存恰一次 (= vanilla readColumn 的 parse+put 那半段)");
    }
}
