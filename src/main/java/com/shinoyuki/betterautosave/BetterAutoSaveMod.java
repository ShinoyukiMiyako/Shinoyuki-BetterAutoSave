package com.shinoyuki.betterautosave;

import com.mojang.logging.LogUtils;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.command.BetterAutoSaveCommand;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.dispatch.SaveDispatcher;
import com.shinoyuki.betterautosave.core.io.AsyncIoBridge;
import com.shinoyuki.betterautosave.core.scheduler.SaveScheduler;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.DiagnosticLogger;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(BetterAutoSaveMod.MOD_ID)
public final class BetterAutoSaveMod {

    public static final String MOD_ID = "shinoyuki_betterautosave";
    public static final String SERIES_CONFIG_DIR = "Shinoyuki-Optimize";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BetterAutoSaveMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(BetterAutoSaveConfig::onLoad);
        modBus.addListener(BetterAutoSaveConfig::onReload);

        Path configRoot = FMLPaths.CONFIGDIR.get().resolve(SERIES_CONFIG_DIR).resolve(MOD_ID);
        try {
            Files.createDirectories(configRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create config directory " + configRoot, e);
        }
        String configRelative = SERIES_CONFIG_DIR + "/" + MOD_ID + "/common.toml";
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ConfigSpec.SPEC, configRelative);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[BetterAutoSave] common setup complete");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[BetterAutoSave] pipeline starting for {}", event.getServer().name());
        SaveMetrics metrics = new SaveMetrics();
        SaveScheduler scheduler = new SaveScheduler(metrics);
        AsyncIoBridge ioBridge = new AsyncIoBridge();
        SnapshotPipeline pipeline = new SnapshotPipeline(scheduler, ioBridge, metrics);
        DiagnosticLogger diagnosticLogger = new DiagnosticLogger(metrics);
        SaveDispatcher dispatcher = new SaveDispatcher(pipeline, metrics);

        pipeline.setChunkResolutionHook(dispatcher);
        pipeline.setEntityResolutionHook(dispatcher);
        pipeline.start(event.getServer());

        BetterAutoSaveCore.install(metrics, scheduler, pipeline, ioBridge, diagnosticLogger);
        LOGGER.info("[BetterAutoSave]   |- workers: chunk={} entity={}",
                BetterAutoSaveConfig.workerThreads(), BetterAutoSaveConfig.entityWorkerThreads());
        LOGGER.info("[BetterAutoSave]   |- throttle: base={}/tick adaptive={} guard={}s",
                BetterAutoSaveConfig.chunksPerTickBase(),
                BetterAutoSaveConfig.adaptiveEnabled(),
                BetterAutoSaveConfig.deadlineGuardSeconds());
        LOGGER.info("[BetterAutoSave]   |- event compat: {}", BetterAutoSaveConfig.eventCompatMode());
        LOGGER.info("[BetterAutoSave]   `- config: {}/{}/common.toml", SERIES_CONFIG_DIR, MOD_ID);
        LOGGER.info("[BetterAutoSave] pipeline installed");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BetterAutoSaveCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (!BetterAutoSaveCore.isInstalled()) {
            return;
        }
        LOGGER.info("[BetterAutoSave] server stopping, draining workers");
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        long t0 = System.currentTimeMillis();
        pipeline.drainPending(BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L);
        boolean joined = pipeline.joinWorkers(BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L);
        long elapsed = System.currentTimeMillis() - t0;
        if (joined) {
            LOGGER.info("[BetterAutoSave] all workers joined cleanly in {}ms", elapsed);
        } else {
            LOGGER.warn("[BetterAutoSave] worker join timed out after {}ms; vanilla synchronous flush will catch remaining writes",
                    elapsed);
        }
        BetterAutoSaveCore.uninstall();
    }
}
