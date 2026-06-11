package com.shinoyuki.betterautosave.core.snapshot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FlushHandler.handleFlush 决策单测 (Major 修复).
 *
 * <p>现场: ChunkMapMixin 对 saveAllChunks(true) 不区分关服与运营中 /save-all flush,
 * 一律 drainPending 同步等待, drainPending 内 Thread.sleep(50) 轮询卡死主线程.
 *
 * <p>判定标准: 把 handleFlush 里的 if (shutdownMode) 改成无条件 drain.blockUntilDrained()
 * 第二个测试 (运营中不阻塞) 挂; 删掉 drain.blockUntilDrained() 第一个测试 (关服阻塞) 挂.
 */
class FlushHandlerTest {

    @Test
    void shutdown_flush_blocks_with_synchronous_drain() {
        AtomicInteger drainCalls = new AtomicInteger();
        AtomicInteger noticeCalls = new AtomicInteger();

        boolean blocked = FlushHandler.handleFlush(true,
                drainCalls::incrementAndGet,
                noticeCalls::incrementAndGet);

        assertTrue(blocked, "关服 flush 必须走阻塞 drain 分支");
        assertEquals(1, drainCalls.get(), "关服 flush 必须同步 drain 一次");
        assertEquals(0, noticeCalls.get(), "关服 flush 不应走运营提示分支");
    }

    @Test
    void operational_flush_does_not_block_main_thread() {
        AtomicInteger drainCalls = new AtomicInteger();
        AtomicInteger noticeCalls = new AtomicInteger();

        boolean blocked = FlushHandler.handleFlush(false,
                drainCalls::incrementAndGet,
                noticeCalls::incrementAndGet);

        assertFalse(blocked, "运营中 flush 必须走非阻塞分支");
        assertEquals(0, drainCalls.get(),
                "运营中 flush 绝不能调用阻塞 drain (否则主线程 sleep 锁服)");
        assertEquals(1, noticeCalls.get(), "运营中 flush 应输出一次非阻塞提示");
    }
}
