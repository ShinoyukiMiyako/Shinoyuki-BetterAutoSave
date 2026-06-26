package com.shinoyuki.betterautosave.api;

import net.minecraft.world.level.ChunkPos;

/**
 * 在线单 chunk 回退的结果载体 (DESIGN §3.1)。
 *
 * @param outcome 结构化结果枚举
 * @param pos     目标 chunk 坐标 (回填便于调用方拼文案/定位)
 * @param cause   失败原因 (PARSE_FAILED / INSTALL_FAILED 时非空, 其余为 null)
 */
public record ChunkRestoreResult(ChunkRestoreOutcome outcome, ChunkPos pos, Throwable cause) {
}
