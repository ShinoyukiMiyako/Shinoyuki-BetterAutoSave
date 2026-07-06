package com.shinoyuki.betterautosave.mixin;

import com.shinoyuki.betterautosave.BetterAutoSaveMod;
import com.shinoyuki.betterautosave.config.LoadMixinGate;
import net.minecraftforge.fml.loading.FMLPaths;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 按 load.enabled 门控四个异步加载 (opt-in, 默认 false) 的 load 侧 mixin 的应用: 关闭时字节码零介入, 使与重写
 * scheduleChunkLoad / ChunkSerializer.read 的 mod (C2ME-forge) 共存时不因目标 INVOKE 消失在启动期硬崩
 * (defaultRequire=1 下 InjectionError = FML 致命崩溃), 恢复 opt-in 的安全预期。save 侧 mixin 与 accessor 恒应用。
 *
 * <p>门控依据只能读磁盘 common.toml (shouldApplyMixin 早于 Forge config 加载, 见 {@link LoadMixinGate})。因此改
 * load.enabled 需重启服务器才改变 mixin 应用 —— 运行期 config 热重载不改已定的字节码。load.enabled 开启时若装了
 * 重写这些点的 mod, 仍按项目 数据安全>稳定性 取舍"响亮早崩", 不 fail-soft 放任两套 off-thread IO 并存。
 */
public final class BetterAutoSaveMixinPlugin implements IMixinConfigPlugin {

    // 一次读盘缓存: 每个 mixin 调一次 shouldApplyMixin, 避免重复解析 toml。
    private Boolean loadEnabled;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!LoadMixinGate.isLoadSideMixin(mixinClassName)) {
            return true;
        }
        return isLoadEnabled();
    }

    private boolean isLoadEnabled() {
        Boolean cached = loadEnabled;
        if (cached != null) {
            return cached;
        }
        boolean enabled = LoadMixinGate.readLoadEnabled(configPath());
        loadEnabled = enabled;
        return enabled;
    }

    private static Path configPath() {
        try {
            return FMLPaths.CONFIGDIR.get()
                    .resolve(BetterAutoSaveMod.SERIES_CONFIG_DIR)
                    .resolve(BetterAutoSaveMod.MOD_ID)
                    .resolve("common.toml");
        } catch (Throwable t) {
            // FMLPaths 未就绪的极端早期: 退回相对 game 目录的 config/ (dedicated server CWD = game 目录)。
            return Path.of("config", BetterAutoSaveMod.SERIES_CONFIG_DIR, BetterAutoSaveMod.MOD_ID, "common.toml");
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
