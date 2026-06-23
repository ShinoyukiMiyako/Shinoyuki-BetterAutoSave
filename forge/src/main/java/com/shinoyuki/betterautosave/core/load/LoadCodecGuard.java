package com.shinoyuki.betterautosave.core.load;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 并发护栏: 串行化 vanilla {@code ChunkSerializer.read} 内<b>唯一</b>跨线程竞态的解码切片——结构拼图解码子树。
 *
 * <p>背景 (docs/ASYNC_LOAD_DESIGN.md L1 取证): {@code read} 体内绝大多数 {@code .parse} 是 thread-confined 的
 * (BLOCK_STATE/biome 经 {@code byNameCodec()} 只读 frozen registry 并解到本 section 私有对象, BlendingData/
 * BelowZeroRetrogen 是纯 {@code RecordCodecBuilder}, read 内不做 DFU)。唯一真竞态在结构解码:
 * {@code unpackStructureStart} 深处的 {@code StructurePoolElement.CODEC}(dispatch codec, 内嵌
 * {@code RegistryFileCodec}) 读写跨线程共享的 {@code RegistryOps} 解析状态与非线程安全的 dispatch 内部 map。
 * C2ME 对此正是只用一把 {@code SynchronizedCodec} 锁 {@code StructurePoolElement.CODEC} 这一处。
 *
 * <p>v2.1 L1 取舍 (替换 v2 "锁整段 read" 的粗粒度串行): 本类仍是单把进程级 {@link ReentrantLock}, 但锁区由
 * {@code ChunkSerializerLoadMixin} 的 {@code @WrapOperation} 收缩到精确包住 read 内的 {@code unpackStructureStart}
 * /{@code unpackStructureReferences} 两个调用 (无法下钻到单 {@code .parse}: 竞态深埋在 {@code PiecesContainer.load}
 * 的反射式 piece 分发, 锁整调用是唯一覆盖完全的粒度)。结构解码之外的 section/调色板/biome/heightmap/方块实体/
 * ForgeCaps 解码全部无锁并行, 直接消解 v2 "N 个 worker 一次只解一个" 的串行瓶颈。
 *
 * <p>锁<b>无条件</b>施加于 worker 与主线程两条 read 路径 (不区分线程): 它守护的是进程级共享 Codec/{@code RegistryOps}
 * 缓存, 竞态是 "任意两个 decode 并发" 而非 "两个 worker"; fallback 时主线程重读会与仍在解码别区块的 worker 并发触同
 * 一 Codec, 故主线程在结构解码切片上也须持锁。这是叶锁、无嵌套获取、worker 从不反向等主线程, 无锁序环不死锁; 主线程
 * 仅在罕见 fallback/FULL 路径的微秒级结构解码上短暂阻塞, PARTIAL 稳态主线程不走 read 故零影响。
 *
 * <p>lock 是 fair=false 默认 (吞吐优先, 加载是请求驱动无饥饿公平性要求), 可重入 (vanilla read 不在锁区内递归触结构
 * 解码, 可重入仅作防御)。
 */
public final class LoadCodecGuard {

    private static final ReentrantLock LOCK = new ReentrantLock();

    public static void lock() {
        LOCK.lock();
    }

    public static void unlock() {
        LOCK.unlock();
    }

    private LoadCodecGuard() {
    }
}
