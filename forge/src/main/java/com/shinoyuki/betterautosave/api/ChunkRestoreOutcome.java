package com.shinoyuki.betterautosave.api;

/**
 * 在线单 chunk 回退的结构化结果枚举 (DESIGN §3.1)。
 *
 * <p>这是 BAS api 包里的新类别: 不同于现有 swallow-and-log 的观察者总线 (无返回),
 * restore 是"主动写 + 有返回 + 暴露失败"的事务性动词。BB 据此枚举决定给服主的反馈
 * (成功/各类拒绝/失败), 不静默 no-op。常量集合与命名为 BB 侧 ChunkRestoreMessages 的
 * switch 逐字依赖, 任何增删/改名都会破坏需方编译, 必须保持稳定。
 *
 * <p>注意: 本方案 (内存原地替换) <b>没有</b> REJECT_BUSY —— 它不要求目标 chunk 未被
 * ticket 钉死, 这正是相对 evict 方案的根本优势 (玩家正盯着的已加载 chunk 也能退)。
 */
public enum ChunkRestoreOutcome {
    /** 回退成功: 地形/方块实体/高度图已原地替换, 已触发异步光照与重发, 已标脏待存盘。 */
    OK,
    /** 不可用: BAS 未安装 / load.enabled=false / FULL 兼容模式 / load 池未起线程。 */
    REJECT_DISABLED,
    /** 被拒: BAS 管线当前降级, 在线写入暂不可用。 */
    REJECT_DEGRADED,
    /** 被拒: 目标 chunk 当前未加载 (无玩家视距覆盖)。在线回退只作用于已加载 chunk。 */
    REJECT_NOT_LOADED,
    /** 失败: 快照 NBT 反序列化抛错 (字节损坏 / 版本不符)。世界未改动。 */
    PARSE_FAILED,
    /** 失败: 主线程安装阶段抛错, 已尽力回滚, 不留半个 chunk。 */
    INSTALL_FAILED
}
