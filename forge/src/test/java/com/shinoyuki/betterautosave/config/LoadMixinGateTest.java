package com.shinoyuki.betterautosave.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步加载 mixin 门控判据的行为回归。门控本身跑在 mixin 应用极早期无法在裸 JUnit 起 MC 验证, 但判据逻辑
 * (load 侧识别 + section-aware 的 [load].enabled 解析 + 缺文件 opt-in) 抽在纯 {@link LoadMixinGate} 里, 可直接单测。
 */
class LoadMixinGateTest {

    @Test
    void load_side_mixins_are_gated_save_side_and_accessors_are_not() {
        String pkg = "com.shinoyuki.betterautosave.mixin.";
        for (String load : new String[]{"ChunkMapLoadMixin", "ChunkSerializerLoadMixin",
                "LevelChunkCapsLoadMixin", "SectionStorageLoadMixin"}) {
            assertTrue(LoadMixinGate.isLoadSideMixin(pkg + load),
                    load + " 必须被识别为 load 侧 (受 load.enabled 门控)");
        }
        for (String other : new String[]{"ChunkMapSaveMixin", "ChunkMapMixin", "DimensionDataStorageMixin",
                "EntityStorageMixin", "SavedDataMixin", "ChunkAccessMixin", "MinecraftServerMixin",
                "accessor.ChunkMapAccessor", "accessor.ChunkSerializerInvoker"}) {
            assertFalse(LoadMixinGate.isLoadSideMixin(pkg + other),
                    other + " 是 save 侧 / accessor, 必须恒应用不被门控");
        }
    }

    @Test
    void parse_is_section_aware_and_only_reads_load_enabled() {
        // [general] 与 [prometheus] 段都有同名 enabled=true, 但 load 开关只认 [load] 段; 若丢了 section 追踪
        // 会把 [general].enabled=true 当成 load 开关误判 true -> 此断言挂。
        List<String> lines = List.of(
                "[general]",
                "\tenabled = true",
                "[load]",
                "\tenabled = false",
                "[prometheus]",
                "\tenabled = true");
        assertFalse(LoadMixinGate.parseLoadEnabled(lines),
                "[load].enabled=false 时必返 false, 不得被 [general]/[prometheus] 段的 enabled=true 污染");

        List<String> enabled = List.of("[general]", "\tenabled = false", "[load]", "\tenabled = true");
        assertTrue(LoadMixinGate.parseLoadEnabled(enabled), "[load].enabled=true 时必返 true");
    }

    @Test
    void parse_defaults_false_when_load_section_or_key_absent() {
        assertFalse(LoadMixinGate.parseLoadEnabled(List.of("[general]", "\tenabled = true")),
                "无 [load] 段 -> opt-in 默认 false");
        assertFalse(LoadMixinGate.parseLoadEnabled(List.of("[load]", "\tsomeOther = 1")),
                "[load] 段缺 enabled 键 -> opt-in 默认 false");
        assertFalse(LoadMixinGate.parseLoadEnabled(List.of()), "空文件 -> opt-in 默认 false");
    }

    @Test
    void read_from_disk_reflects_file_and_missing_file_is_false(@TempDir Path dir) throws IOException {
        Path toml = dir.resolve("common.toml");
        Files.write(toml, List.of("[load]", "\tenabled = true"), StandardCharsets.UTF_8);
        assertTrue(LoadMixinGate.readLoadEnabled(toml), "磁盘 [load].enabled=true -> true");

        Files.write(toml, List.of("[load]", "\tenabled = false"), StandardCharsets.UTF_8);
        assertFalse(LoadMixinGate.readLoadEnabled(toml), "磁盘 [load].enabled=false -> false");

        assertFalse(LoadMixinGate.readLoadEnabled(dir.resolve("nonexistent.toml")),
                "文件不存在 (首次启动尚未生成 config) -> opt-in 默认 false");
    }
}
