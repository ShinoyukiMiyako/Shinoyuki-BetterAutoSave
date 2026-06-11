package com.shinoyuki.betterautosave.core.snapshot;

/**
 * saveAllChunks(flush=true) 拦截的处理策略 (Major 修复).
 *
 * <p>vanilla saveAllChunks(true) 同时服务两个场景:
 * <ol>
 *   <li>关服 flush (MinecraftServer.stopServer): 必须同步等 BAS in-flight 落盘, 不能丢.</li>
 *   <li>运营中 /save-all flush (op level 4 命令): drainPending 的 Thread.sleep 轮询会卡死
 *       主线程最多 shutdownTimeoutSeconds 秒, 触发 "Can't keep up" + 玩家卡顿.</li>
 * </ol>
 *
 * <p>把"是否阻塞 drain"的决策抽成纯函数 seam 以便单测: 关服才 drain, 运营中不阻塞.
 * saveAllChunks 返回 void, vanilla 调用方不依赖 BAS 是否 drain 完, 运营中直接放行让
 * vanilla 自己的有界同步 flush 兜底当前 dirty chunk 是安全的.
 */
public final class FlushHandler {

    @FunctionalInterface
    public interface DrainAction {
        void blockUntilDrained();
    }

    @FunctionalInterface
    public interface OperationalNotice {
        void log();
    }

    private FlushHandler() {
    }

    /**
     * @param shutdownMode  true=关服 flush, false=运营中 /save-all flush
     * @param drain         关服时执行的同步阻塞 drain (运营中绝不调用)
     * @param notice        运营中执行的非阻塞提示 (关服时绝不调用)
     * @return true 表示执行了阻塞 drain (关服路径), false 表示走了非阻塞提示 (运营路径)
     */
    public static boolean handleFlush(boolean shutdownMode, DrainAction drain, OperationalNotice notice) {
        if (shutdownMode) {
            drain.blockUntilDrained();
            return true;
        }
        notice.log();
        return false;
    }
}
