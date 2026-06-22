package com.shinoyuki.betterautosave.core.worker;

/**
 * 标记/断言 "当前线程是 BetterAutoSave 序列化 worker"。worker 入口自标, 退出清除; 只能在 worker 上跑的纯数据
 * 封装 (如 NBT assemble) 据此断言, 防误在主线程内联执行堵 tick。纯 ThreadLocal 不依赖 Minecraft, 故与状态机/
 * 调度器同属可多 loader 共享的 core。需要 MinecraftServer 的主线程断言仍在 loader 侧 ServerThreadAssert。
 */
public final class WorkerThreadAssert {

    private static final ThreadLocal<Boolean> WORKER_MARK = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void markCurrentThreadAsWorker() {
        WORKER_MARK.set(Boolean.TRUE);
    }

    public static void unmarkCurrentThreadAsWorker() {
        WORKER_MARK.remove();
    }

    public static void assertOnWorkerThread() {
        if (!WORKER_MARK.get()) {
            throw new IllegalStateException(
                    "Expected execution on a BetterAutoSave worker thread, but was on " + Thread.currentThread().getName());
        }
    }

    private WorkerThreadAssert() {
    }
}
