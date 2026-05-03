package com.shinoyuki.betterautosave.util;

import net.minecraft.server.MinecraftServer;

public final class ServerThreadAssert {

    private static final ThreadLocal<Boolean> WORKER_MARK = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void assertOnServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Expected execution on server main thread, but was on " + Thread.currentThread().getName());
        }
    }

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

    private ServerThreadAssert() {
    }
}
