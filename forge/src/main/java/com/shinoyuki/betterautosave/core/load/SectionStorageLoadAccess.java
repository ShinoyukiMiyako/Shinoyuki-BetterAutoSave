package com.shinoyuki.betterautosave.core.load;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@link com.shinoyuki.betterautosave.mixin.SectionStorageLoadMixin} 注入 {@code SectionStorage} (含子类
 * {@code PoiManager}) 的 duck 接口, 把 vanilla 私有的 "POI 列读盘" 与 "解析填缓存" 拆成可分别在 worker /
 * 主线程调用的两个方法 (Tier A 异步 POI 预读)。
 *
 * <p>本接口<b>必须放在 mixin 包外</b> (core.load 而非 mixin.accessor): mixins.json 声明的 mixin 包
 * {@code com.shinoyuki.betterautosave.mixin.*} 内的类是 mixin 处理器私有的, 未注册的普通类放在其中会在被直接
 * 引用时抛 {@code IllegalClassLoadError} ("is in a defined mixin package ... cannot be referenced directly")。
 * {@code @Accessor} 接口 (如 ChunkMapAccessor) 因在 mixins.json 注册为 mixin 才可引用; 本接口是被 class mixin
 * 实现的 duck 接口、不进 mixins.json, 故须置于普通包。
 *
 * <p>背景: 异步加载 PARTIAL 模式下 {@code ChunkSerializer.read} 内的 {@code PoiManager.checkConsistencyWithBlocks}
 * 被 defer 回主线程 replay, 但它触发的 {@code getOrLoad -> readColumn} 在主线程 {@code tryRead().join()} 阻塞等
 * POI region 读盘 (spark 实测占主线程 ~15%, 全是 join 干等)。Tier A 让 worker 在反序列化那刻把该列 POI 字节读出
 * 线程, 主线程 replay 前用预读字节填缓存, 令随后的 {@code getOrLoad} 命中缓存 O(1) 返回, 清掉主线程读盘。
 */
public interface SectionStorageLoadAccess {

    /**
     * worker 线程触发该 chunk 列的 POI region 异步读盘, 返回未完成 future。仅经底层 IOWorker (ProcessorMailbox,
     * 线程安全) 读磁盘字节, <b>绝不</b>触碰 SectionStorage 非并发的 {@code storage}/{@code dirty}/
     * {@code DistanceTracker}, 故 off-thread 调安全。worker 取得 {@code CompoundTag} 后封进 {@code LoadResult}
     * 交回主线程 (经 CompletableFuture happens-before)。
     */
    CompletableFuture<Optional<CompoundTag>> betterautosave$readColumnNbtFuture(ChunkPos pos);

    /**
     * 主线程用 worker 预读的 POI region 字节解析并填入 {@code storage} (= vanilla {@code readColumn} 的解析+put
     * 那半段, 跳过其内部 {@code tryRead().join()} 读盘)。
     *
     * <p><b>护栏</b>: 若该列已在 {@code storage} (replay 前已被别的主线程路径加载) 则整列跳过 —— 活数据为准,
     * 丢弃可能陈旧的预读字节。{@code nbt} 为 {@code Optional.empty()} 表示盘上无该列 POI, 与 vanilla 用 null 调
     * {@code readColumn} 把全列标空等价。须在主线程 replay 阶段、deferred {@code checkConsistencyWithBlocks}
     * <b>之前</b>调。touches {@code storage}/{@code dirty}/{@code DistanceTracker}, 仅主线程安全。
     */
    void betterautosave$populateColumnOnMain(ChunkPos pos, Optional<CompoundTag> nbt);
}
