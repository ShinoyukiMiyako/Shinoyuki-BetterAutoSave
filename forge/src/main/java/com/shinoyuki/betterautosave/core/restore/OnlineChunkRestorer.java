package com.shinoyuki.betterautosave.core.restore;

import com.shinoyuki.betterautosave.BetterAutoSaveCore;
import com.shinoyuki.betterautosave.api.ChunkRestoreOutcome;
import com.shinoyuki.betterautosave.api.ChunkRestoreResult;
import com.shinoyuki.betterautosave.config.BetterAutoSaveConfig;
import com.shinoyuki.betterautosave.config.ConfigSpec;
import com.shinoyuki.betterautosave.core.load.ChunkLoadTask;
import com.shinoyuki.betterautosave.core.load.LoadDeferredActions;
import com.shinoyuki.betterautosave.core.load.LoadResult;
import com.shinoyuki.betterautosave.core.snapshot.SnapshotPipeline;
import com.shinoyuki.betterautosave.diagnostic.SaveMetrics;
import com.shinoyuki.betterautosave.mixin.accessor.ChunkMapAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * 在线单 chunk 回退的 BAS 侧编排 (DESIGN §3)。
 *
 * <p>整体不卡主线程 (硬约束): 反序列化(贵)下沉 BAS load worker (复用 {@link ChunkLoadTask}),
 * 光照传播(贵)走 {@link ThreadedLevelLightEngine} 异步; 主线程只做 O(1 个 chunk) 的引用替换
 * + 入队 + 发包 (约等于 vanilla 平时加载一个 chunk)。
 *
 * <p>原地替换全程只用 vanilla 公有 API 操作 live {@link LevelChunk} (getSections 返回内部数组引用,
 * clearAllBlockEntities / addAndRegisterBlockEntity / setHeightmap / initializeLightSources / setUnsaved
 * 皆 public), 故<b>不需要</b>对 LevelChunk 注入 mixin —— 保对象 identity 不变, ChunkHolder 的 future
 * 仍指向同一 live 对象 (内容已换)。
 */
public final class OnlineChunkRestorer {

    private OnlineChunkRestorer() {
    }

    /**
     * @see com.shinoyuki.betterautosave.api.SaveCoordination#restoreChunkLive
     */
    public static CompletableFuture<ChunkRestoreResult> restoreChunkLive(
            ServerLevel level, ChunkPos pos, CompoundTag tag) {
        MinecraftServer server = level.getServer();

        // 门禁: 与 ChunkMapLoadMixin 同源 (复用 load worker 必须这些前提成立)。语义反转: 返回原因枚举, 不静默 no-op。
        if (!BetterAutoSaveCore.isInstalled()
                || !BetterAutoSaveConfig.loadEnabled()
                || BetterAutoSaveConfig.loadEventCompatMode() == ConfigSpec.LoadCompatMode.FULL) {
            return completed(ChunkRestoreOutcome.REJECT_DISABLED, pos, null);
        }
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline == null || !pipeline.isLoadPoolActive()) {
            return completed(ChunkRestoreOutcome.REJECT_DISABLED, pos, null);
        }
        if (pipeline.isDegraded()) {
            return completed(ChunkRestoreOutcome.REJECT_DEGRADED, pos, null);
        }
        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        if (metrics == null || server == null) {
            return completed(ChunkRestoreOutcome.REJECT_DISABLED, pos, null);
        }

        // 主线程权威校验 (M2): 命令线程解析 level 到此刻状态可能已变, getChunkNow 是唯一可信判据
        // (非主线程它直接返回 null, 故本方法契约要求主线程调用)。
        ServerChunkCache chunkSource = level.getChunkSource();
        LevelChunk live = chunkSource.getChunkNow(pos.x, pos.z);
        if (live == null) {
            return completed(ChunkRestoreOutcome.REJECT_NOT_LOADED, pos, null);
        }
        PoiManager poiManager = ((ChunkMapAccessor) chunkSource.chunkMap).betterautosave$getPoiManager();

        // 反序列化下沉 load worker (ChunkLoadTask 吃传入 tag, 不读盘); 解析期截走的 POI/光照/事件副作用
        // 随 LoadResult.deferred 回来, 主线程回放。
        // poiPrefetch=false: 回退的是已加载的 live chunk, 其 POI 列多半已在内存, 预读会被 populate 护栏跳过而纯
        // 浪费一次 IOWorker 读 (restore 也不调 populateColumnOnMain); 故 restore 显式不预读, 走 vanilla 主线程 POI。
        ChunkLoadTask task = new ChunkLoadTask(level, poiManager, pos, tag, metrics,
                BetterAutoSaveConfig.loadMaxRetries(), false);
        pipeline.loadWorkerQueue().offer(task);

        Executor mainExec = server;
        CompletableFuture<ChunkRestoreResult> out = new CompletableFuture<>();
        task.result().whenCompleteAsync((loadResult, error) -> {
            if (error != null) {
                out.complete(new ChunkRestoreResult(ChunkRestoreOutcome.PARSE_FAILED, pos, unwrap(error)));
                return;
            }
            try {
                // 主线程回放 worker 截走的副作用 (灌光照 nibble / POI 一致性 / ChunkDataEvent.Load)。
                LoadDeferredActions.replayOnMainThread(server, loadResult.deferred());
                ChunkRestoreOutcome outcome = install(level, live, pos, loadResult.chunk(), mainExec);
                out.complete(new ChunkRestoreResult(outcome, pos, null));
            } catch (Throwable t) {
                out.complete(new ChunkRestoreResult(ChunkRestoreOutcome.INSTALL_FAILED, pos, t));
            }
        }, mainExec);
        return out;
    }

    /**
     * 主线程: 把解析出的快照内容原地灌进 live chunk, 触发异步光照, 点亮后重发。
     * 全程廉价 (引用替换 + 入队 + 发包), 不逐方块。
     */
    private static ChunkRestoreOutcome install(
            ServerLevel level, LevelChunk live, ChunkPos pos, ChunkAccess parsed, Executor mainExec) {
        // 快照应是 full chunk: ChunkSerializer.read 对 LEVELCHUNK 型返回 ImposterProtoChunk 包着 LevelChunk。
        LevelChunk source;
        if (parsed instanceof ImposterProtoChunk imposter) {
            source = imposter.getWrapped();
        } else if (parsed instanceof LevelChunk levelChunk) {
            source = levelChunk;
        } else {
            return ChunkRestoreOutcome.INSTALL_FAILED;
        }
        // 物化 source 的方块实体: read 把 LEVELCHUNK 的 BE 放进 postLoad 处理器, 须 runPostLoad 触发 (主线程)。
        source.runPostLoad();

        LevelChunkSection[] srcSections = source.getSections();
        LevelChunkSection[] liveSections = live.getSections(); // 返回内部数组引用, 写其内容即改 live
        // 等高守卫 (R7): 同世界天然相等; 不等说明跨不同高度维度, 拒绝以免越界。
        if (srcSections.length != liveSections.length) {
            return ChunkRestoreOutcome.INSTALL_FAILED;
        }

        // 替换期间先标光照失效; 安装尾声由光引擎异步置回。
        live.setLightCorrect(false);
        // 解绑旧方块实体 (onChunkUnloaded + setRemoved + ticker 重绑 NULL_TICKER 失活), vanilla 卸载语义。
        live.clearAllBlockEntities();
        // 整段引用替换 sections (引用拷贝, 非逐方块); source 用后即弃, 浅拷贝即 vanilla 加载语义。
        System.arraycopy(srcSections, 0, liveSections, 0, liveSections.length);
        // 用新方块重算高度图 (而非拷快照高度图): 保证与替换后方块自洽, 消除 R3。仅重算 live 已持有的类型。
        Set<Heightmap.Types> heightmapTypes = EnumSet.noneOf(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : live.getHeightmaps()) {
            heightmapTypes.add(entry.getKey());
        }
        if (!heightmapTypes.isEmpty()) {
            Heightmap.primeHeightmaps(live, heightmapTypes);
        }
        // 用新方块重建天空光源列 (skyLightSources.fillFrom), 供光引擎跨 section 遮挡判定 (R6)。
        live.initializeLightSources();
        // 装入快照方块实体: source 已 runPostLoad, 其 BE 仅 setBlockEntity 过未注册; addAndRegisterBlockEntity
        // 完成首次注册 (ticker + game event listener + onLoad), 不双挂 (R2/R5)。
        for (BlockEntity blockEntity : source.getBlockEntities().values()) {
            live.addAndRegisterBlockEntity(blockEntity);
        }
        // keepPacked 形态 BE 仍是 pending NBT, 原样过继 (上面 live BE 在此 getBlockEntityNbt 返回 null 被跳过)。
        for (BlockPos blockPos : source.getBlockEntitiesPos()) {
            CompoundTag nbt = source.getBlockEntityNbt(blockPos);
            if (nbt != null) {
                live.setBlockEntityNbt(nbt);
            }
        }
        // 标脏: 交 vanilla/BAS 正常存盘把 restored 落盘 (in-memory = restored 即真理, 不直写盘)。
        live.setUnsaved(true);

        // 异步点亮 (光引擎独立线程, 不卡主线程): 镜像 vanilla load 的 INITIALIZE_LIGHT + LIGHT 两阶段。
        // 点亮完成后再回主线程重发, 保证 light 包数据完整 (尤其无烘焙光的快照)。
        ThreadedLevelLightEngine lightEngine = chunkSource(level).getLightEngine();
        boolean hadStoredLight = source.isLightCorrect();
        lightEngine.initializeLight(live, hadStoredLight)
                .thenCompose(ignored -> lightEngine.lightChunk(live, hadStoredLight))
                .thenAcceptAsync(ignored -> resend(level, live, pos), mainExec);
        return ChunkRestoreOutcome.OK;
    }

    /** 重发整块给视距内玩家 (纯服务端, 原版客户端整块覆盖); 不需 clearCache (对象 identity 不变)。 */
    private static void resend(ServerLevel level, LevelChunk live, ChunkPos pos) {
        ServerChunkCache cache = level.getChunkSource();
        ThreadedLevelLightEngine lightEngine = cache.getLightEngine();
        ClientboundLevelChunkWithLightPacket chunkPacket =
                new ClientboundLevelChunkWithLightPacket(live, lightEngine, null, null);
        // full chunk 包里 BE 是构造那刻快照; 容器/比较器等需逐 BE 补 getUpdatePacket (R-net)。
        List<Packet<?>> bePackets = new ArrayList<>();
        for (BlockEntity blockEntity : live.getBlockEntities().values()) {
            Packet<?> updatePacket = blockEntity.getUpdatePacket();
            if (updatePacket != null) {
                bePackets.add(updatePacket);
            }
        }
        for (ServerPlayer player : cache.chunkMap.getPlayers(pos, false)) {
            player.connection.send(chunkPacket);
            for (Packet<?> bePacket : bePackets) {
                player.connection.send(bePacket);
            }
        }
    }

    private static ServerChunkCache chunkSource(ServerLevel level) {
        return level.getChunkSource();
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }

    private static CompletableFuture<ChunkRestoreResult> completed(
            ChunkRestoreOutcome outcome, ChunkPos pos, Throwable cause) {
        return CompletableFuture.completedFuture(new ChunkRestoreResult(outcome, pos, cause));
    }
}
