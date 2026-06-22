package com.shinoyuki.betterautosave.core.load;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步加载 read-parity 回归 (docs/ASYNC_LOAD_DESIGN.md 第五节, 对称存盘侧 issue #8 的
 * {@link com.shinoyuki.betterautosave.core.snapshot.ChunkForgeCapsParityTest})。
 *
 * <p>数据安全模型: PARTIAL 路径把整段 vanilla {@code ChunkSerializer.read} 经 {@code Operation.call} 整体跑在
 * load worker 上 (ChunkMapLoadMixin 的 wrap), worker 不自写序列化器、不挑步骤; {@code ChunkSerializerLoadMixin}
 * 只用 {@code @Redirect} 把 read 体内三类<b>主线程独占副作用</b> (POI 一致性 / 光照 section / ChunkDataEvent.Load
 * 派发) 截走延后到主线程回放。read 体内一切 thread-confined 的纯解析 (含 {@code readCapsFromNBT} 读回 ForgeCaps、
 * {@code setBlockEntityNbt} 存方块实体 NBT) 必须<b>原样 inline</b> 留在 worker 跑 —— 它们就是 vanilla 自己的读回
 * 逻辑, 不被 redirect 触碰即天然零丢失。
 *
 * <p>反编译金源 1.20.1 ChunkSerializer.read 实证 (净源 :162 / :230):
 * <pre>
 *   if (tag.contains("ForgeCaps")) ((LevelChunk)chunkaccess).readCapsFromNBT(tag.getCompound("ForgeCaps"));
 *   ...
 *   chunkaccess.setBlockEntityNbt(compoundtag1);
 * </pre>
 * 这两步是 ForgeCaps / 方块实体 NBT 的读回。一旦有人给它们也加 {@code @Redirect} 把它们从 worker 的 vanilla read
 * 里截走 (按 "也 defer" 的错误直觉), 而 defer 分支又没忠实回放, 就等价 issue #8 的镜像: "写得回去但读不回来" =
 * 数据丢失。本测试钉死这条不变式 —— deletion-sensitive 的反向: <b>给读回步骤新增 redirect 立刻挂</b>。
 *
 * <p>同 {@link AsyncLoadSplitParityTest}: bare JUnit 无 MC 运行期, 无法真跑 mixin, 故对 build/classes 编译产物的
 * {@code @Redirect} 注解 (CLASS 保留, ASM 读作 invisibleAnnotations) 解析其 {@code at.target} 描述符集合断言。
 */
class AsyncLoadReadParityTest {

    private static final String CLASSES_DIR = "build/classes/java/main";
    private static final String MIXIN = "com.shinoyuki.betterautosave.mixin.ChunkSerializerLoadMixin";

    private static final String REDIRECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String WRAPOP_DESC = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

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

    /**
     * 递归把一个注解值树里所有 "看起来是方法描述符" 的 String (含 '(' 与 ')', 即 @At.target 的形如
     * {@code Lowner;name(args)ret}) 收进 out。ASM tree 把嵌套注解 (@Redirect 的 at=@At) 存成 AnnotationNode,
     * 数组存成 List, 平铺成 [name0,val0,...]; 直接递归全树比逐层按键名取值对 ASM 表示更鲁棒。
     */
    private static void collectDescriptorStrings(Object value, List<String> out) {
        if (value instanceof String s) {
            if (s.indexOf('(') >= 0 && s.indexOf(')') >= 0) {
                out.add(s);
            }
        } else if (value instanceof AnnotationNode ann) {
            if (ann.values != null) {
                for (Object v : ann.values) {
                    collectDescriptorStrings(v, out);
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object v : list) {
                collectDescriptorStrings(v, out);
            }
        }
    }

    /**
     * 收集 ChunkSerializerLoadMixin 全部拦截器 (@Redirect 与 @WrapOperation) 的 at.target 描述符 (visible + invisible
     * 都扫)。事件 post 用 @WrapOperation (与 architectury 等 @ModifyArg 同指令共存), POI/光照用 @Redirect, 两者都是
     * "把 read 体内调用截走"的同一语义, 故 read-parity 的正反向断言都须把两类一并视作"被拦截"。
     */
    private List<String> redirectTargets() throws IOException {
        ClassNode node = loadClass(MIXIN);
        List<String> targets = new ArrayList<>();
        for (MethodNode m : node.methods) {
            for (List<AnnotationNode> anns : List.of(
                    m.invisibleAnnotations != null ? m.invisibleAnnotations : List.<AnnotationNode>of(),
                    m.visibleAnnotations != null ? m.visibleAnnotations : List.<AnnotationNode>of())) {
                for (AnnotationNode ann : anns) {
                    if (REDIRECT_DESC.equals(ann.desc) || WRAPOP_DESC.equals(ann.desc)) {
                        collectDescriptorStrings(ann, targets);
                    }
                }
            }
        }
        return targets;
    }

    /**
     * 正向 (deletion-sensitive): 三类主线程独占副作用必须各有一个 @Redirect 把它们截走。删任一 redirect ->
     * 该 vanilla 调用在 worker 上直接 inline 执行 -> 跨线程写 PoiManager/LevelLightEngine 崩, 或事件在 worker 派发
     * 破坏第三方 listener 主线程假设。这里只断言 redirect 存在 (defer 后是否回放由 AsyncLoadSplitParityTest 把关)。
     */
    @Test
    void main_thread_exclusive_side_effects_are_redirected() throws IOException {
        List<String> targets = redirectTargets();
        assertTrue(targets.stream().anyMatch(t -> t.contains("checkConsistencyWithBlocks")),
                "POI 一致性 checkConsistencyWithBlocks 必须被 @Redirect 截走留主线程 (否则 worker 跨线程写 PoiManager)");
        assertTrue(targets.stream().anyMatch(t -> t.contains("retainData")),
                "光照 retainData 必须被 @Redirect 截走留主线程 (否则 worker 跨线程写 LevelLightEngine)");
        assertTrue(targets.stream().anyMatch(t -> t.contains("queueSectionData")),
                "光照 queueSectionData 必须被 @Redirect 截走留主线程 (否则 worker 跨线程写 LevelLightEngine)");
        assertTrue(targets.stream().anyMatch(t -> t.contains("IEventBus") && t.contains("post")),
                "ChunkDataEvent.Load 派发 (IEventBus.post) 必须被 @WrapOperation 截走留主线程派发 (第三方 listener 假设主线程)");
    }

    /**
     * 反向数据安全闸门 (read-parity 核心): ForgeCaps 读回 readCapsFromNBT 与方块实体 NBT 读回 setBlockEntityNbt
     * 是 thread-confined 的纯解析 (只写本 chunk 实例, 反编译金源 :162 / :230), 必须留在 worker 的 vanilla read 里
     * inline, 绝<b>不</b>能被 @Redirect 截走。一旦有人给它们加 redirect (按 "副作用都 defer" 的错误直觉), 读回逻辑
     * 就被从 read 路径剥离, 等价 issue #8 镜像的丢数据 ("写得回去但读不回来")。给读回步骤新增 redirect -> 本断言挂。
     */
    @Test
    void chunk_data_read_back_steps_are_not_redirected() throws IOException {
        List<String> targets = redirectTargets();
        assertFalse(targets.stream().anyMatch(t -> t.contains("readCapsFromNBT")),
                "readCapsFromNBT (ForgeCaps 读回) 绝不能被 @Redirect 截走: 它必须 inline 留在 worker 的 vanilla read; "
                        + "截走则区块 capability 读不回来 = 镜像 issue #8 丢数据");
        assertFalse(targets.stream().anyMatch(t -> t.contains("setBlockEntityNbt")),
                "setBlockEntityNbt (方块实体 NBT 读回) 绝不能被 @Redirect 截走: 它必须 inline 留在 worker 的 vanilla "
                        + "read; 截走则方块实体数据读不回来 = 丢数据");
    }

    /** 防御性: 确认确实解析到了 redirect (注解保留/解析逻辑若失效会让上面反向断言假性 PASS)。 */
    @Test
    void redirects_were_actually_parsed() throws IOException {
        List<String> targets = redirectTargets();
        assertTrue(targets.size() >= 4,
                "应解析到至少 4 个 @Redirect (POI/光照x2/事件); 实得 " + targets.size()
                        + " — 注解保留或 ASM 解析失效, 反向 read-parity 断言不可信");
    }
}
