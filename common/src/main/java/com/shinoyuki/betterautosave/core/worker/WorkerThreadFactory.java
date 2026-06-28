package com.shinoyuki.betterautosave.core.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkerThreadFactory implements ThreadFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("BetterAutoSave");

    private final String namePrefix;
    private final Runnable onUncaught;
    private final AtomicInteger counter = new AtomicInteger();

    public WorkerThreadFactory(String namePrefix, Runnable onUncaught) {
        this.namePrefix = namePrefix;
        this.onUncaught = onUncaught;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, namePrefix + "-" + counter.incrementAndGet());
        // daemon: 不让 worker 钉死 JVM。否则只要别的 mod 在 ServerStoppingEvent 里早于 BAS 抛异常,
        // 跳过 BAS 的 onServerStopping (唯一停 worker 的入口), 这些线程就空转不退, 把整台服务器拖成关不掉。
        // 在途序列化/写盘的安全由 SnapshotPipeline 的 JVM shutdown hook 在 halt 前 drain+join 兜底保证。
        t.setDaemon(true);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        t.setUncaughtExceptionHandler((thread, throwable) -> {
            LOGGER.error("[BetterAutoSave] worker {} died from uncaught throwable, triggering degraded mode",
                    thread.getName(), throwable);
            if (onUncaught != null) {
                try {
                    onUncaught.run();
                } catch (Throwable inner) {
                    LOGGER.error("[BetterAutoSave] degraded-mode hook itself threw", inner);
                }
            }
        });
        return t;
    }
}
