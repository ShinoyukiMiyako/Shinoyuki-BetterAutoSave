package com.shinoyuki.betterautosave.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 异步加载 mixin 的应用期门控判据 (供 {@code BetterAutoSaveMixinPlugin} 用)。抽成不依赖 Mixin/Forge 的纯类,
 * 让门控逻辑 (load 侧 mixin 识别 + [load].enabled 解析) 可在裸 JUnit 单测。
 *
 * <p>为何在此读盘: {@code shouldApplyMixin} 跑在类变换极早期, 远早于 mod 构造与 Forge config 加载。此时
 * {@code ConfigSpec.LOAD_ENABLED.get()} 会抛 (spec 未 attach), {@code BetterAutoSaveConfig.loadEnabled()} 只返回
 * volatile 字段的 JVM 默认 false (refresh 未跑)。唯一可靠信息源是磁盘上的 common.toml; 缺文件 (首次启动尚未生成)
 * 按 opt-in 默认 false。
 */
public final class LoadMixinGate {

    // load 侧 mixin 的简单类名。这四个用 @Redirect/@WrapOperation 钩 scheduleChunkLoad / ChunkSerializer.read /
    // LevelChunk 构造 / SectionStorage; 与重写这些点的 mod (C2ME-forge) 共存且 load.enabled 开启时才可能因目标
    // INVOKE 消失在启动期硬崩。四者互相耦合 (ChunkMapLoadMixin 运行期 cast SectionStorageLoadAccess), 全 gate 于
    // 同一 load.enabled, 要么全应用要么全不应用。
    private static final Set<String> LOAD_SIDE_MIXIN_SIMPLE_NAMES = Set.of(
            "ChunkMapLoadMixin",
            "ChunkSerializerLoadMixin",
            "LevelChunkCapsLoadMixin",
            "SectionStorageLoadMixin");

    private LoadMixinGate() {
    }

    /** mixinClassName (FQCN) 是否属 load 侧 (受 load.enabled 门控)。save 侧与 accessor 恒应用返回 false。 */
    public static boolean isLoadSideMixin(String mixinClassName) {
        int dot = mixinClassName.lastIndexOf('.');
        String simple = dot >= 0 ? mixinClassName.substring(dot + 1) : mixinClassName;
        return LOAD_SIDE_MIXIN_SIMPLE_NAMES.contains(simple);
    }

    /**
     * 从 common.toml 的行读 [load].enabled。section-aware: 只认 [load] 段下的 enabled, 不把 [general] /
     * [prometheus] 段的同名 enabled 键误当 load 开关。缺 [load] 段或缺 enabled 键返回 false (opt-in)。
     */
    public static boolean parseLoadEnabled(List<String> tomlLines) {
        String section = "";
        for (String raw : tomlLines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            if (section.equals("load")) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).trim().equals("enabled")) {
                    return Boolean.parseBoolean(line.substring(eq + 1).trim());
                }
            }
        }
        return false;
    }

    /** 读磁盘 common.toml 判 load.enabled; 文件不存在 / 读失败均按 opt-in 返 false。 */
    public static boolean readLoadEnabled(Path tomlPath) {
        if (tomlPath == null || !Files.isRegularFile(tomlPath)) {
            return false;
        }
        try {
            return parseLoadEnabled(Files.readAllLines(tomlPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }
}
