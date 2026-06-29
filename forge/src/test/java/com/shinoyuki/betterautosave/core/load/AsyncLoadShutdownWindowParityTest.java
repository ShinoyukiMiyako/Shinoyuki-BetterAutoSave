package com.shinoyuki.betterautosave.core.load;

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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 关服窄窗下加载侧不挂死的回归 (L4-2 + POI 预读关服闸门)。bare JUnit 无 MC 运行期不能真起 IOWorker / 真跑
 * {@code ChunkLoadTask.execute} (需 ServerLevel/PoiManager/ChunkSerializer.read), 故对 build/classes 编译产物按
 * 目标方法内的调用与常量断言, deletion-sensitive (退回旧逻辑 -> 计数/常量变 -> 挂)。覆盖三条关服不挂死不变式:
 *
 * <ul>
 *   <li><b>POI 预读 join 有限超时</b>: {@code ChunkLoadTask.execute} 对预读 future 必须用有限超时 {@code get(long,
 *       TimeUnit)} 而非无界 {@code join()} —— 关服窄窗 IOWorker 已关、tryRead future 永不完成时, 无界 join 会把
 *       load worker 永久钉死令 inFlightLoadParsing 永不归零拖死关服。删超时退回 join -> get 计数归零 -> 挂。</li>
 *   <li><b>POI 预读提交闸门</b>: {@code ChunkMapLoadMixin} 提交 poiPrefetch task 前必须查
 *       {@code pipeline.isWorkersStopping()}, 关停态退 poiPrefetch=false 走 vanilla 内联读 POI。删闸门 ->
 *       isWorkersStopping 计数归零 -> 挂。</li>
 *   <li><b>drainPending 纳入加载侧</b>: 关服 drain 屏障 {@code SnapshotPipeline.drainPending} 的轮询条件必须查
 *       {@code inFlightLoadParsing()} (与 loadWorkerQueue) —— 否则 load 高峰关服会在 worker 仍解析时误判 idle
 *       提前返回。删该项 -> inFlightLoadParsing 计数归零 -> 挂。</li>
 * </ul>
 */
class AsyncLoadShutdownWindowParityTest {

    private static final String CLASSES_DIR = "build/classes/java/main";
    private static final String LOAD_TASK = "com.shinoyuki.betterautosave.core.load.ChunkLoadTask";
    private static final String CHUNK_MAP_MIXIN = "com.shinoyuki.betterautosave.mixin.ChunkMapLoadMixin";
    private static final String PIPELINE = "com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline";
    private static final String CHAIN_HANDLER = "betterautosave$asyncLoadChain";

    /**
     * POI 预读 join 必须有限超时: {@code get(long, TimeUnit)} 恰一次, 且<b>绝无</b> {@code CompletableFuture.join}
     * —— join 是无界等, 关服窄窗会永久钉死本 load worker。{@code get} 的 owner 必是 CompletableFuture (排除别处
     * 同名 get 误计)。
     */
    @Test
    void poi_prefetch_join_uses_bounded_timeout_not_unbounded_join() throws IOException {
        MethodNode execute = loadMethod(LOAD_TASK, "execute");
        int boundedGet = countCallsOnOwner(execute, "java/util/concurrent/CompletableFuture", "get");
        assertEquals(1, boundedGet,
                "execute 必须对 poiFuture 用有限超时 get(timeout, TimeUnit) 恰一次; 缺失说明退回无界等, 关服窄窗"
                        + "IOWorker 关闭后 tryRead future 永不完成会永久钉死 load worker (inFlightLoadParsing 永不归零)");
        assertEquals(0, countCallsOnOwner(execute, "java/util/concurrent/CompletableFuture", "join"),
                "execute 绝不能对 poiFuture 用无界 join(): 关服窄窗会拖死关服; 必须换成有限超时 get");
        assertTrue(usesTimeUnitSeconds(execute),
                "有限超时必须以 TimeUnit 为单位调 get(long, TimeUnit); 缺 TimeUnit 说明不是带超时的重载");
    }

    /**
     * 关服闸门: 提交 poiPrefetch task 前查 pipeline.isWorkersStopping() (关停态退 prefetch=false 走 vanilla 内联读)。
     * 该判定连同其合成 lambda 一并计数 (闸门在最内层 acquire().thenComposeAsync lambda 里建 task 处)。
     */
    @Test
    void poi_prefetch_gated_by_workers_stopping_on_submit() throws IOException {
        List<MethodNode> chain = methodAndLambdas(CHUNK_MAP_MIXIN, CHAIN_HANDLER);
        assertTrue(totalCalls(chain, "isWorkersStopping") >= 1,
                "提交 poiPrefetch task 前必须查 pipeline.isWorkersStopping() 闸门; 缺失则关服窄窗仍向即将关闭的"
                        + "IOWorker 投预读 -> tryRead future 永不完成 -> load worker 钉死拖死关服");
    }

    /** 关服 drain 屏障必须纳入加载侧在途解析 (inFlightLoadParsing), 否则 load 高峰关服在 worker 仍解析时误判 idle。 */
    @Test
    void drain_pending_polls_in_flight_load_parsing() throws IOException {
        MethodNode drain = loadMethod(PIPELINE, "drainPending");
        assertTrue(countCalls(drain, "inFlightLoadParsing") >= 1,
                "drainPending 轮询条件必须查 inFlightLoadParsing(); 缺失则 load 高峰关服会在 load worker 仍跑 "
                        + "ChunkSerializer.read 时误判 idle 提前返回, joinWorkers 紧接 halt 杀掉仍在解析的 worker");
    }

    /** SnapshotPipeline 必须暴露 isWorkersStopping 公开访问器供 mixin 提交闸门读 (volatile workersStopping 的读口)。 */
    @Test
    void pipeline_exposes_workers_stopping_accessor() throws IOException {
        ClassNode node = loadClass(PIPELINE);
        boolean has = node.methods.stream().anyMatch(m -> m.name.equals("isWorkersStopping"));
        assertTrue(has, "SnapshotPipeline 必须有 public isWorkersStopping() 供 ChunkMapLoadMixin 关服闸门读");
    }

    // ---- ASM 辅助 ----

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

    private int countCallsOnOwner(MethodNode m, String ownerInternalName, String calleeName) {
        int n = 0;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(ownerInternalName) && call.name.equals(calleeName)) {
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

    /** 该方法体内是否出现 TimeUnit.SECONDS 字段引用 (get(long, TimeUnit) 的单位实参), 用以确证调的是带超时重载。 */
    private boolean usesTimeUnitSeconds(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof FieldInsnNode f
                    && f.owner.equals("java/util/concurrent/TimeUnit") && f.name.equals("SECONDS")) {
                return true;
            }
        }
        return false;
    }
}
