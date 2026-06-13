package com.shinoyuki.betterautosave.util;

import net.minecraft.server.MinecraftServer;

public final class ServerThreadAssert {

    public static void assertOnServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Expected execution on server main thread, but was on " + Thread.currentThread().getName());
        }
    }

    private ServerThreadAssert() {
    }
}
