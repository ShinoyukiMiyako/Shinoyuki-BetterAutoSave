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
import org.slf4j.Logger;

@Mod(BetterAutoSaveMod.MOD_ID)
public final class BetterAutoSaveMod {

    public static final String MOD_ID = "betterautosave";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BetterAutoSaveMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(BetterAutoSaveConfig::onLoad);
        modBus.addListener(BetterAutoSaveConfig::onReload);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ConfigSpec.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("BetterAutoSave common setup complete");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("BetterAutoSave installing pipeline for {}", event.getServer().name());
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
        LOGGER.info("BetterAutoSave pipeline installed");
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
        LOGGER.info("BetterAutoSave server stopping, draining workers");
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        pipeline.drainPending(BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L);
        boolean joined = pipeline.joinWorkers(BetterAutoSaveConfig.shutdownTimeoutSeconds() * 1000L);
        if (joined) {
            LOGGER.info("BetterAutoSave all workers joined cleanly");
        } else {
            LOGGER.warn("BetterAutoSave worker join timed out; vanilla synchronous flush will catch remaining writes");
        }
        BetterAutoSaveCore.uninstall();
    }
}
