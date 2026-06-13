package com.shinoyuki.betterautosave.core.state;

/**
 * 状态机消费接力快照时所需的唯一视图: capture 时刻锁定的内容代。
 *
 * <p>{@link ChunkSaveState} 与 {@link EntitySaveState} 只据此判定 IO 落地的
 * CLEAN_LANDED / REQUEUE_DIRTY, 快照其余字段对协议不透明。借此把状态机与持 Minecraft
 * 类型的具体 snapshot 实现解耦, 使协议成为零依赖纯算法 —— 可被多 loader 共享, 并以纯 Java
 * stub 实现本接口直接单测, 无需 bootstrap Minecraft。
 */
public interface CapturedSnapshot {

    long capturedGeneration();
}
