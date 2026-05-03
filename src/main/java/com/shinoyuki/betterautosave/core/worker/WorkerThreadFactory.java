package com.shinoyuki.betterautosave.core.worker;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import org.slf4j.Logger;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkerThreadFactory implements ThreadFactory {

    private static final Logger LOGGER = BetterAutoSaveMod.LOGGER;

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
        t.setDaemon(false);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        t.setUncaughtExceptionHandler((thread, throwable) -> {
            LOGGER.error("Worker thread {} died from uncaught throwable, triggering degraded mode",
                    thread.getName(), throwable);
            if (onUncaught != null) {
                try {
                    onUncaught.run();
                } catch (Throwable inner) {
                    LOGGER.error("onUncaught hook itself threw", inner);
                }
            }
        });
        return t;
    }
}
