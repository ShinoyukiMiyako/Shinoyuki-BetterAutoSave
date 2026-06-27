package com.shinoyuki.betterautosave.mixin;

import com.mojang.serialization.DynamicOps;
import com.shinoyuki.betterautosave.mixin.accessor.SectionStorageLoadAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Tier A 异步 POI 预读: 把 vanilla {@code SectionStorage.readColumn(ChunkPos)} 内"读盘 + 解析填缓存"两步拆开,
 * 经 duck 接口 {@link SectionStorageLoadAccess} 暴露成可分别在 worker / 主线程调的两个方法。{@code SectionStorage}
 * 的子类 {@code PoiManager} 继承本 mixin 注入的接口与方法, 故 {@code (SectionStorageLoadAccess) poiManager} 可用。
 *
 * <p>拆分依据 (反编译金源 {@code SectionStorage.java}): {@code getOrLoad -> readColumn(ChunkPos)}(:118) =
 * {@code tryRead(pos).join()}(读盘, 仅 IOWorker, 可挪 worker) + 私有泛型 {@code readColumn(pos, ops, nbt)}(:135,
 * parse + {@code storage.put} + {@code onSectionLoad}, 写非并发容器, 必须留主线程)。两步无共享中间态, 干净可切。
 *
 * <p>私有/受保护成员经 {@code @Invoker} 调起 (BAS 惯例: AP 可靠解析 SRG 名, 优于 {@code @Shadow private}, 见
 * {@code ChunkMapAccessor}); 字段 {@code levelHeightAccessor}/{@code registryAccess} 经 {@code @Shadow @Final} 读。
 * {@code @Invoker("readColumn")} 按本方法声明的描述符 {@code (ChunkPos,DynamicOps,Object)V} 精确命中三参泛型重载,
 * 不与单参 {@code readColumn(ChunkPos)} 混淆。
 *
 * <p>线程安全: {@link #betterautosave$readColumnNbtFuture} 只触 IOWorker (线程安全), worker 调安全;
 * {@link #betterautosave$populateColumnOnMain} 写 {@code storage}/{@code dirty}/{@code DistanceTracker}, 仅由
 * {@code ChunkMapLoadMixin} 的 replay-stage 在 mainThreadExecutor 上调 (主线程), 与所有其它 POI 访问同线程序。
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageLoadMixin implements SectionStorageLoadAccess {

    @Shadow
    @Final
    protected LevelHeightAccessor levelHeightAccessor;

    @Shadow
    @Final
    private RegistryAccess registryAccess;

    @Invoker("get")
    protected abstract Optional<?> betterautosave$invokeGet(long key);

    @Invoker("tryRead")
    protected abstract CompletableFuture<Optional<CompoundTag>> betterautosave$invokeTryRead(ChunkPos pos);

    @Invoker("readColumn")
    protected abstract <T> void betterautosave$invokeReadColumn(ChunkPos pos, DynamicOps<T> ops, T nbt);

    @Override
    public CompletableFuture<Optional<CompoundTag>> betterautosave$readColumnNbtFuture(ChunkPos pos) {
        // tryRead = worker.loadAsync(pos).exceptionally(IOException -> empty); 只排底层 IOWorker 任务, 不碰 storage。
        return this.betterautosave$invokeTryRead(pos);
    }

    @Override
    public void betterautosave$populateColumnOnMain(ChunkPos pos, Optional<CompoundTag> nbt) {
        // 护栏: readColumn 整列一次性 put 全部 section key (getKey(pos, minSection..maxSection)), 故首 section 在
        // storage 即代表整列已加载。已加载则跳过 —— 活数据为准, 预读字节(读于 worker 反序列化那刻)可能已陈旧。
        long firstKey = SectionPos.asLong(pos.x, this.levelHeightAccessor.getMinSection(), pos.z);
        if (this.betterautosave$invokeGet(firstKey) != null) {
            return;
        }
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, this.registryAccess);
        // nbt.orElse(null): empty 表示盘上无该列 POI, readColumn(null) 把全列标 Optional.empty (与 vanilla 等价)。
        this.betterautosave$invokeReadColumn(pos, ops, nbt.orElse(null));
    }
}
