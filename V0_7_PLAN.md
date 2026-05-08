# v0.7 + SaveListener API 实施计划

| 字段 | 值 |
|---|---|
| 状态 | 草稿 (2026-05-08), 等用户审批 |
| 范围 | (1) BAS 暴露 SaveListener API; (2) v0.7 SavedData / DimensionDataStorage 异步化 |
| 关联 | BetterBackup MVP 依赖此处 SaveListener API; ROADMAP §v0.7 |
| 工作量 | SaveListener API 1 commit (~80 行); v0.7 SavedData 6-7 commit (~600 行); 共约 1.5 周 |
| 触发 | v0.6 已 24h+ soak 通过, 0 failed / 0 fallback |

## 0. 背景与目标

### 0.1 v0.7 目标 (SavedData 异步化)

vanilla `DimensionDataStorage.save()` 在 autosave 周期内**主线程同步遍历** cache map, 对每个 dirty SavedData 调 `savedData.save(file)` 直接写盘, 内部走 `NbtIo.writeCompressed` (gzip + 文件 IO)。

mod 注册的 SavedData 文件越大单次序列化越慢:
- MTR `mtr_train_data.dat` 包含全部路线 / 车站 / 列车调度 NBT, 大型铁路服可达 10-50 MB
- ANTE 进出闸统计持续累积, 无清理机制
- 实测 > 10 MB 单文件 50-200 ms 主线程 spike

v0.7 把 NBT 序列化 + IO 部分搬到 worker 线程, 主线程只保留 `savedData.save(CompoundTag)` 内部 tag 构建 (mod 内部状态, 必须主线程)。

### 0.2 SaveListener API 目标

BetterBackup MVP 需要在 BAS 完成 chunk / entity / SavedData 保存时拿到 (位置, 内容) 元组做 dedup 备份。当前 BAS 没有外部扩展点, BetterBackup 唯一选项是 mixin BAS 内部, 这是反模式。

正确做法: BAS 暴露 `SaveListenerRegistry` 静态注册中心, BetterBackup 启动时 register, 关服时 unregister。

### 0.3 为什么 SaveListener API 跟 v0.7 一起做

v0.7 SavedData 路径上线后, 自然多出第三类 listener (`SavedDataSaveListener.onSavedDataWritten`)。一次性把三类 listener 接口设计完, 比 v0.7 上线后再追加更干净。

## 1. CLAUDE.md 第 0 步法则: 垃圾回收前置检查

按 CLAUDE.md, 在 >300 行文件结构性重构前必须先做垃圾回收。本次工作:

- **新增代码为主**, 不涉及现有 >300 行文件的重构
- ChunkSaveTask.java (96 行) / EntitySaveTask.java (98 行) 各只追加一行 listener fire 调用, 不算重构
- 不删除任何现有功能 / 配置项 / 公开类
- 不改动 v0.2 / v0.4 / v0.5 / v0.6 已落地路径

**结论**: 第 0 步不适用 (无垃圾可清), 直接进入实施计划。

## 2. 切入点确认 (代码核对)

### 2.1 SaveListener API: ChunkSaveTask / EntitySaveTask hook 点

已读 [ChunkSaveTask.java](src/main/java/com/shinoyuki/betterautosave/core/snapshot/ChunkSaveTask.java) + [EntitySaveTask.java](src/main/java/com/shinoyuki/betterautosave/core/snapshot/EntitySaveTask.java) 全文。

ChunkSaveTask.execute() 工作流 (line 37-78):
1. line 38: `tag = ChunkNbtAssembler.assemble(snapshot)` — worker 拼装完整 tag
2. line 45: `future = ioBridge.storeChunk(level, pos, tag)` — 提交 vanilla IOWorker
3. line 46-77: `future.whenComplete((ignored, error) -> ...)` — IO 完成回调
   - 成功: state.ioCompletedSuccessfully() → outcome = CLEAN_LANDED 或 REQUEUE_DIRTY
   - 失败: state.ioFailed() → outcome = FAILED_TERMINAL 或 retry

**Listener fire 点**: line 73 `metrics.recordChunkCompleted()` 之后 (CLEAN_LANDED 分支), 同时也在 REQUEUE_DIRTY 分支 fire (那次 IO 写盘成功了, 只是 chunk 又被 dirty)。**不在 FAILED 分支 fire** — IO 失败说明字节没落盘, 不应被 BetterBackup 记入备份。

EntitySaveTask 同结构, 同 fire 时机。

**线程模型**: fire 在 worker 线程上下文 (IOWorker 完成 future 的线程, 通常是 IOWorker 自己的 worker pool)。Listener 实现需要 thread-safe, 文档明示。

### 2.2 v0.7 SavedData mixin 点确认

已读 vanilla [DimensionDataStorage.java](build/tmp/.cache/expanded/zip_a9a427a7c089e71073f9cb67c16a7bac/net/minecraft/world/level/storage/DimensionDataStorage.java) (133 行) + [SavedData.java](build/tmp/.cache/expanded/zip_a9a427a7c089e71073f9cb67c16a7bac/net/minecraft/world/level/saveddata/SavedData.java) (61 行)。

**vanilla 逻辑** (DimensionDataStorage.save() line 125-132):
```java
public void save() {
    this.cache.forEach((name, savedData) -> {
        if (savedData != null) {
            savedData.save(this.getDataFile(name));   // 主线程 IO
        }
    });
}
```

**SavedData.save(File) 内部** (line 46-60):
```java
public void save(File pFile) {
    if (this.isDirty()) {
        CompoundTag tag = new CompoundTag();
        tag.put("data", this.save(new CompoundTag()));   // 主线程 mod-specific tag 构建
        NbtUtils.addCurrentDataVersion(tag);
        try {
            NbtIo.writeCompressed(tag, pFile);            // 主线程 gzip + IO ← worker 化目标
        } catch (IOException e) { LOGGER.error(...); }
        this.setDirty(false);
    }
}
```

**调用链** (vanilla 1.20.1):
- `MinecraftServer` 主 tick / shutdown
- → `ServerLevel.save(progress, flush, skipSave)` ([ServerLevel.java:739](build/tmp/.../ServerLevel.java#L739))
- → `saveLevelData()` ([ServerLevel.java:765](build/tmp/.../ServerLevel.java#L765))
- → `getChunkSource().getDataStorage().save()` ([ServerLevel.java:770](build/tmp/.../ServerLevel.java#L770))

**线程**: 主线程 (server thread), 与 v0.4 ChunkMap.save 路径同 tick 上下文。

**Mixin 拦截点选择**:

| 选项 | 拦截点 | 优劣 |
|---|---|---|
| **A (推荐)** | `DimensionDataStorage.save()` HEAD inject + cancellable | 单一拦截点, 接管整个 cache 遍历 + dispatch; 关服 / 异常守卫易加 |
| B | `SavedData.save(File)` HEAD inject + cancellable | 拦截每个个体, 但 SavedData 是 mod 也会继承的基类, 有 mod 重写 save(File) 时 mixin 可能失效或撞车 |
| C | TAIL inject `DimensionDataStorage.save()` 后追加 | vanilla 已经做了 IO, 我们再异步意义不大 |

**结论**: 选 A. HEAD inject + cancellable, 自己实现 cache 遍历, 对每个 dirty SavedData:
1. 主线程构内部 tag (`savedData.save(new CompoundTag())`) — mod-specific, 必须主线程
2. 包装外层 `{ data: ..., DataVersion: ... }` (主线程, 廉价)
3. 入 savedDataWorkerQueue (新增独立 worker pool)
4. 主线程立即 `setDirty(false)` (乐观清理, race 见 §7.3)
5. ci.cancel(), 跳过 vanilla iteration

worker:
1. `NbtIo.writeCompressed(tag, file)` — gzip + IO
2. 失败: re-mark dirty, 让下个 autosave 周期重试; 重试用尽 → 日志 ERROR (vanilla equivalence: vanilla 失败也只 log 不抛)
3. 成功: 触发 `SavedDataSaveListener.onSavedDataWritten(filename, tag)` 事件 (BetterBackup hook)

### 2.3 vanilla 调用站点核对

`DimensionDataStorage` 出现在 9 个 vanilla 类中 (grep 结果), 但只有 [ServerLevel.java:770](build/tmp/.../ServerLevel.java#L770) 是 **save() 调用站点**, 其他都是引用 / get / computeIfAbsent。所以 mixin 一处即可全覆盖 autosave + shutdown 两条路径。

**关服守卫**: 跟 v0.4 同结构, 由 `SaveScheduler.isShutdownMode()` 守卫. 关服时 `MinecraftServer.stopServer()` → `ServerLevel.save(progress, true, false)` → `saveLevelData()` → `dataStorage.save()`, mixin 检测 `isShutdownMode()` 返 true 时不接管, 走 vanilla 同步路径保证落盘。

## 3. 数据结构设计

### 3.1 SaveListener API 接口

新建 package `com.shinoyuki.betterautosave.api`:

```java
// api/ChunkSaveListener.java
public interface ChunkSaveListener {
    /**
     * Fired on worker thread after chunk NBT successfully written to region file.
     * Listener implementations MUST be thread-safe.
     * Listener MUST NOT block — heavy work should be queued to listener's own pool.
     * Listener exceptions are caught + logged, won't propagate to BAS.
     *
     * @param tag the complete CompoundTag that was written (mutable! defensively copy if you keep a reference)
     */
    void onChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag);
}

// api/EntityChunkSaveListener.java
public interface EntityChunkSaveListener {
    void onEntityChunkSaved(ChunkPos pos, ResourceKey<Level> dimension, CompoundTag tag);
}

// api/SavedDataSaveListener.java (v0.7)
public interface SavedDataSaveListener {
    void onSavedDataWritten(String fileName, CompoundTag tag);
}

// api/SaveListenerRegistry.java
public final class SaveListenerRegistry {
    private static final List<ChunkSaveListener> chunk = new CopyOnWriteArrayList<>();
    private static final List<EntityChunkSaveListener> entity = new CopyOnWriteArrayList<>();
    private static final List<SavedDataSaveListener> savedData = new CopyOnWriteArrayList<>();

    public static void registerChunk(ChunkSaveListener l) { chunk.add(l); }
    public static void unregisterChunk(ChunkSaveListener l) { chunk.remove(l); }
    public static void registerEntity(EntityChunkSaveListener l) { entity.add(l); }
    public static void unregisterEntity(EntityChunkSaveListener l) { entity.remove(l); }
    public static void registerSavedData(SavedDataSaveListener l) { savedData.add(l); }
    public static void unregisterSavedData(SavedDataSaveListener l) { savedData.remove(l); }

    static void fireChunkSaved(ChunkPos pos, ResourceKey<Level> dim, CompoundTag tag) {
        if (chunk.isEmpty()) return;  // fast path 0 alloc
        for (ChunkSaveListener l : chunk) {
            try { l.onChunkSaved(pos, dim, tag); }
            catch (Throwable t) { LOGGER.error("ChunkSaveListener {} threw", l.getClass(), t); }
        }
    }
    // 同 fireEntity / fireSavedData
}
```

**性能开销** (空 listener 场景, BAS 单装无 BetterBackup):
- 每次 ChunkSaveTask 完成 +1 次 `chunk.isEmpty()` 检查 → 0 内存分配, < 1ns
- 完全可忽略

**API 暴露策略**:

| 方案 | 优劣 |
|---|---|
| **当前方案** | API 接口在 BAS 主 mod jar 内, BetterBackup 编译期依赖 BAS jar |
| 独立 `betterautosave-api` 子模块 jar | 最干净但 gradle 子项目改动大, **延后到 BAS v0.8 / BetterBackup v0.2** |

第一阶段直接放主 jar, BetterBackup `mods.toml` 声明 `mandatory=true` 依赖 BAS, 编译 / 运行期都要 BAS 在 classpath。

### 3.2 v0.7 SavedDataSnapshot

新建 [src/main/java/com/shinoyuki/betterautosave/core/snapshot/SavedDataSnapshot.java](src/main/java/com/shinoyuki/betterautosave/core/snapshot/SavedDataSnapshot.java):

```java
public record SavedDataSnapshot(
    String fileName,                   // 不含 .dat 后缀, 例 "raids" "Forced" "mtr_train_data"
    File targetFile,                   // 完整路径 (含 .dat)
    CompoundTag preBuiltTag,           // 主线程已构好的外层 { data: ..., DataVersion }
    SavedData savedDataRef,            // 用于 setDirty(false) 回调 + REQUEUE_DIRTY 重新入队
    long capturedDirtyMark,            // 主线程 capture 时的 dirty=true 标记 (语义参考 ChunkSaveState.generation)
    SavedDataSaveState state           // 状态机, 与 ChunkSaveState 同构
) {}
```

### 3.3 v0.7 SavedDataSaveState

参考 [ChunkSaveState.java](src/main/java/com/shinoyuki/betterautosave/core/state/ChunkSaveState.java) 同构, 但更简化 (SavedData 没有 unload 路径, 不需要 mustDrain):

```java
public final class SavedDataSaveState {
    public enum Phase { CLEAN, DIRTY, SERIALIZING, IO_PENDING, FAILED }
    private volatile Phase phase = Phase.CLEAN;
    private volatile int retryCount = 0;
    
    // CAS 状态转换 (与 ChunkSaveState 同款)
}
```

### 3.4 v0.7 SavedDataSaveTask

类似 [ChunkSaveTask.java](src/main/java/com/shinoyuki/betterautosave/core/snapshot/ChunkSaveTask.java) 但更简短:

```java
public final class SavedDataSaveTask implements SaveTask {
    public void execute() {
        try {
            NbtIo.writeCompressed(snapshot.preBuiltTag(), snapshot.targetFile());
            // 触发 BetterBackup 等 listener
            SaveListenerRegistry.fireSavedDataWritten(snapshot.fileName(), snapshot.preBuiltTag());
            metrics.recordSavedDataCompleted();
        } catch (IOException e) {
            // 失败重试: 主线程 schedule 重新 setDirty(true)
            server.execute(() -> snapshot.savedDataRef().setDirty(true));
            metrics.recordSavedDataFailed();
            LOGGER.error("[BetterAutoSave] SavedData {} write failed, re-marked dirty for next cycle", snapshot.fileName(), e);
        }
    }
}
```

## 4. Mixin 设计

### 4.1 DimensionDataStorageMixin

新建 [src/main/java/com/shinoyuki/betterautosave/mixin/DimensionDataStorageMixin.java](src/main/java/com/shinoyuki/betterautosave/mixin/DimensionDataStorageMixin.java):

```java
@Mixin(DimensionDataStorage.class)
public abstract class DimensionDataStorageMixin {

    @Shadow @Final
    private Map<String, SavedData> cache;

    // dataFolder 通过 invoker 拿 getDataFile(name) 即可, 不需要直接访问

    @Inject(method = "save()V", at = @At("HEAD"), cancellable = true)
    private void betterautosave$interceptSave(CallbackInfo ci) {
        // 守卫 (跟 v0.4 同款): isInstalled / enabled / isDegraded / isShutdownMode
        if (!BetterAutoSaveCore.isInstalled() || !BetterAutoSaveConfig.enabled()) return;
        SnapshotPipeline pipeline = BetterAutoSaveCore.pipeline();
        if (pipeline == null || pipeline.isDegraded()) return;
        SaveScheduler scheduler = BetterAutoSaveCore.scheduler();
        if (scheduler == null || scheduler.isShutdownMode()) return;
        SaveMetrics metrics = BetterAutoSaveCore.metrics();
        if (metrics == null) return;

        // 主线程遍历 cache, 对每个 dirty SavedData dispatch
        cache.forEach((name, savedData) -> {
            if (savedData != null && savedData.isDirty()) {
                try {
                    File file = ((DimensionDataStorageInvoker)(Object)this).betterautosave$getDataFile(name);
                    CompoundTag tag = new CompoundTag();
                    tag.put("data", savedData.save(new CompoundTag()));
                    NbtUtils.addCurrentDataVersion(tag);

                    SavedDataSnapshot snapshot = new SavedDataSnapshot(name, file, tag, savedData, ...);
                    pipeline.savedDataWorkerQueue().offer(new SavedDataSaveTask(snapshot, ...));
                    savedData.setDirty(false);  // 乐观清理, 失败时 worker 在主线程 setDirty(true)
                    metrics.recordSavedDataSubmitted();
                } catch (Throwable t) {
                    LOGGER.error("[BetterAutoSave] SavedData {} dispatch failed, falling back to vanilla", name, t);
                    savedData.save(file);  // fallback 走 vanilla 同步路径
                    metrics.recordSavedDataFallback();
                }
            }
        });
        ci.cancel();  // 跳过 vanilla iteration
    }
}
```

### 4.2 DimensionDataStorageInvoker

新建 accessor mixin 暴露私有 `getDataFile(String)`:

```java
@Mixin(DimensionDataStorage.class)
public interface DimensionDataStorageInvoker {
    @Invoker("getDataFile")
    File betterautosave$getDataFile(String name);
}
```

### 4.3 ChunkSaveTask / EntitySaveTask 改动 (SaveListener fire)

最小改动, 各加一行:

```java
// ChunkSaveTask.java line ~73 (CLEAN_LANDED 分支后追加)
if (outcome == ChunkSaveState.IoOutcome.CLEAN_LANDED) {
    metrics.recordChunkCompleted();
} else {
    metrics.recordChunkRetried();
}
SaveListenerRegistry.fireChunkSaved(snapshot.pos(), snapshot.dimension(), tag);  // <-- 新增
```

注意 fire 点在 CLEAN_LANDED + REQUEUE_DIRTY 两条成功路径上, 不在 FAILED 路径。

## 5. 配置项 / 命令套件改动

### 5.1 新增配置项

`config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml`:

```toml
[workers]
chunkWorkerThreads = 2          # 已存在
entityWorkerThreads = 2         # 已存在
savedDataWorkerThreads = 1      # 新增, 默认 1 (SavedData 文件少, 不需要多 worker)

[safety]
shutdownTimeoutSeconds = 60     # 已存在
maxRetries = 3                  # 已存在
savedDataMaxFileSizeMB = 50     # 新增, 单文件超过此值降级为同步, 防 worker queue 被 50 MB 文件堵死
```

### 5.2 SnapshotPipeline 扩展

新增 `savedDataWorkerQueue` 字段 + worker pool 启停, 复用 v0.2 已有 `SerializationWorker` 框架。

### 5.3 命令套件改动

`/betterautosave debug` 输出新增 SavedData 段:

```
SavedData (v0.7):
  |- submitted: 12
  |- completed: 12
  |- failed: 0
  |- fallback: 0
  `- last write: raids @ 142us
```

`DiagnosticLogger` 输出 SavedData 行 (与 chunk / entity 同 idle 节流策略):

```
[BAS] savedData: submitted=12 completed=12 failed=0 fallback=0
```

## 6. 测试策略

### 6.1 单元测试

- `SavedDataSaveStateTest`: 状态机 CAS 转换 (与 `ChunkSaveStateTest` 同构)
- `SavedDataSaveTaskTest`: 模拟 IOException → 验证 setDirty(true) 重新调度
- `SaveListenerRegistryTest`: 多 listener 并发注册 / 注销 / fire, 异常 listener 不影响其他

### 6.2 集成测试 (gradle runServer)

- 触发 autosave (5 min) → 验证 `dataStorage.save()` 不在主线程产生 NBT 编码 frame (用 spark profile 主线程)
- 装 MTR + 模拟 mtr_train_data.dat 生长 → 验证主线程 spike 消失
- BetterBackup hello world: 注册一个 listener 打印 saved chunks, 验证回调正确触发 + tag 内容正确

### 6.3 生产 soak

- 24h+ 跟 v0.6 soak 同强度: failed=0 / fallback=0 / 所有 listener 不报错
- 关服时 SavedData 全部 flush 到盘, 不丢数据

### 6.4 BetterBackup 联动

- BAS v0.7 + BetterBackup MVP 同时跑, 验证 SaveListener API 正常工作, BetterBackup dedup store 正确累积

## 7. 风险评估

### 7.1 SavedData mod 实现假设

vanilla SavedData 子类 (Raids / ForcedChunksSavedData / 等) 的 `save(CompoundTag)` 必须读 mod 内部状态构建 tag。我们调用这一步在主线程, 跟 vanilla 等价。

**风险**: 如果 mod 的 SavedData 实现内部有 lazy initialization / cross-thread access / 异步副作用, 主线程同步调用 `save(CompoundTag)` 可能行为差异。

**缓解**: 跟 vanilla 完全同行为 (vanilla 也是主线程调 save(CompoundTag)), 不引入新风险。

### 7.2 SavedData.save(File) override 风险

vanilla SavedData 是抽象基类, `save(File)` 不是 abstract, 个别 mod 可能 override (跳过 isDirty() 检查或加自定义副作用)。

**当前接管策略**: mixin DimensionDataStorage.save() HEAD, 我们调 `savedData.save(new CompoundTag())` (mod 必 override 的抽象方法), 不调 `savedData.save(File)` (mod 偶尔 override 的具象方法), 然后自己写文件。

**结果**: override `save(File)` 的 mod 副作用不会触发。这跟 vanilla 行为有差异。

**缓解**: 添加配置 `compat.respectSavedDataFileOverride = false` (默认), 显式声明 BAS 假设 mod 不 override `save(File)`。如果用户报兼容问题, 可切 `true` 走 vanilla 同步 fallback。

### 7.3 setDirty(false) race

主线程乐观清 dirty → 下一 tick mod 改了状态调 setDirty(true) → 同时 worker 还在写 tag 文件 (这次 tag 是上一版本)。

**结果**: 文件落盘后是上一版本, 但 mod 内存版本是新的, isDirty=true → 下个 autosave 周期重写 (覆盖文件)。**没有数据丢失, 只有一次额外 IO**。

**与 vanilla 的差异**: vanilla 同步, dirty bit 在 IO 后清。BAS 异步, dirty bit 在 dispatch 时清。窗口期 = worker IO 完成时间 (~10-100ms)。

**缓解**: 接受。同 v0.4 ticks race 一样的非新增风险类别。

### 7.4 worker IO 失败重试

worker 写失败 → re-mark dirty + 等下个周期重试。重试上限到 `maxRetries`, 之后 FAILED 状态终止。

**风险**: SavedData 持续失败 → 数据永久不落盘。

**缓解**: 跟 vanilla 行为等价 (vanilla 失败也只 log "Could not save data" 不抛不重试)。BAS 至少多了重试。FAILED 后 fallback 到下个 autosave 周期再走 vanilla 同步 (vanilla 自己也会触发, 因为 dirty=true 一直在)。

### 7.5 关服 flush

关服路径: `MinecraftServer.stopServer()` → `ServerLevel.save(progress, flush=true, false)` → `saveLevelData()` → `dataStorage.save()`. 

`SaveScheduler.isShutdownMode()` 守卫已在 v0.4 onServerStopping 设置, 此时 mixin 不接管, 走 vanilla 同步路径。**保证关服 SavedData 100% flush**。

`pipeline.drainPending` 已有逻辑, 关服前 join savedDataWorkerQueue (新增 worker pool 一并 join)。

### 7.6 性能开销

空 listener 场景 (BAS 单装):
- ChunkSaveTask / EntitySaveTask 各加一次 `list.isEmpty()` 检查 → < 1ns, 0 alloc
- 无可观测开销

装 BetterBackup:
- 每次 chunk save 多一次 listener 调用 → BetterBackup 入自己 worker queue → 不阻塞 BAS worker

### 7.7 SaveListener 异常

listener 实现抛异常 (BetterBackup bug 等) → 当前设计 `try { l.onChunkSaved() } catch(t) { LOGGER.error }` 吞掉, 不传播。

**理由**: BAS 路径必须不被 listener 影响, 否则一个差 mod 拖死 BAS。

**风险**: BetterBackup 持续抛异常 → 备份漏了一些 chunk → 用户不易察觉。

**缓解**: BetterBackup 自己应在 `DiagnosticLogger` 输出 listener 异常计数 + 自动 degraded mode。

## 8. Commit 计划 (原子提交)

### 8.1 SaveListener API (1 commit, 优先做, 跟 BetterBackup 解耦)

**Commit 0**: `feat(api): SaveListener API + Registry, ChunkSaveTask / EntitySaveTask fire 点`

变更范围:
- 新增 `api/ChunkSaveListener.java` (5 行接口)
- 新增 `api/EntityChunkSaveListener.java` (5 行接口)
- 新增 `api/SaveListenerRegistry.java` (~80 行)
- 修改 `core/snapshot/ChunkSaveTask.java` (line 72-77 区域加一行 fire)
- 修改 `core/snapshot/EntitySaveTask.java` (同款)
- 新增 `test/SaveListenerRegistryTest.java` (~50 行 单测)

**注意**: 这一 commit **不**包含 `SavedDataSaveListener`, 那个 v0.7 commit 5 时再加。SaveListener API 自身可独立测试, 给 BetterBackup MVP Phase 0 解锁。

### 8.2 v0.7 SavedData 异步化 (6 commits)

**Commit 1**: `feat(mixin): DimensionDataStorageInvoker 暴露 getDataFile`
- 新增 `mixin/accessor/DimensionDataStorageInvoker.java`
- 注册到 `mixins.json`

**Commit 2**: `feat(state): SavedDataSaveState 状态机`
- 新增 `core/state/SavedDataSaveState.java`
- 单测 `SavedDataSaveStateTest`

**Commit 3**: `feat(snapshot): SavedDataSnapshot record + SavedDataSaveTask`
- 新增 `core/snapshot/SavedDataSnapshot.java` (record)
- 新增 `core/snapshot/SavedDataSaveTask.java` (~80 行)
- 单测 `SavedDataSaveTaskTest`

**Commit 4**: `feat(pipeline): SnapshotPipeline 加 savedDataWorkerQueue + worker pool`
- 修改 `core/snapshot/SnapshotPipeline.java`
- 修改 `BetterAutoSaveConfig.java` 新增 `savedDataWorkerThreads` / `savedDataMaxFileSizeMB`
- 修改 `core/worker/SerializationWorker` 复用 (已有逻辑)

**Commit 5**: `feat(api): SavedDataSaveListener + Registry 扩展`
- 新增 `api/SavedDataSaveListener.java`
- 修改 `api/SaveListenerRegistry.java` 加 savedData 通道
- 修改 `core/snapshot/SavedDataSaveTask.java` 在成功路径 fire

**Commit 6**: `feat(mixin): DimensionDataStorage.save HEAD 拦截 v0.7 SavedData 异步化`
- 新增 `mixin/DimensionDataStorageMixin.java` (~100 行)
- 注册到 `mixins.json`
- 修改 `SaveMetrics` 加 savedDataSubmitted / Completed / Failed / Fallback 计数

**Commit 7**: `feat(diag+command): DiagnosticLogger + /betterautosave debug 输出 SavedData 段`
- 修改 `diagnostic/DiagnosticLogger.java`
- 修改 `command/BetterAutoSaveCommand.java`

**Commit 8** (文档收尾): `docs(readme+roadmap): v0.7 SavedData 实施说明 + version bump 0.7.0`
- 修改 `gradle.properties` 版本号 0.6.0 → 0.7.0
- 修改 `README.md` 路线图状态
- 修改 `ROADMAP.md` v0.7 标 [已落地]

**总计**: 9 commits (1 SaveListener API + 7 v0.7 主体 + 1 文档收尾), 估约 600-800 行新代码 + 100 行测试。

## 9. 待确认问题 (开始实施前需要拍板)

1. **SaveListener API 放主 jar 还是独立 api jar 子模块?**
   - 当前推荐: 放主 jar (Phase 1 简单), 后续如有需要再拆子模块
   - 影响: BetterBackup 编译期依赖 BAS 主 jar (vs 仅依赖小 api jar)

2. **mixin 拦截策略 A vs B?**
   - 当前推荐: A (mixin DimensionDataStorage.save HEAD), 单一拦截点
   - 影响: 不接管个别 mod override SavedData.save(File) 的副作用 (默认假设无 mod 这么做)

3. **savedDataMaxFileSizeMB 默认值?**
   - 当前推荐: 50 MB
   - 超过此阈值的 SavedData 走 vanilla 同步路径 (避免 worker queue 被几十 MB 单文件堵死)
   - 真实场景: MTR mtr_train_data.dat 大型铁路服可达 10-50 MB, 默认 50 应该够; ANTE 之类持续增长的可能突破。可调

4. **listener fire 时机: CLEAN_LANDED only 还是含 REQUEUE_DIRTY?**
   - 当前推荐: 都 fire (REQUEUE_DIRTY 也是真实落盘成功, 仅是 chunk 又被 dirty)
   - 影响: BetterBackup 看到的 chunk 版本比当前内存稍旧, 但落盘的就是这版本, dedup 正确

5. **listener 异常捕获策略**
   - 当前推荐: 全部 try-catch, 只 log, 不向 BAS 传播
   - 影响: 差 listener 不会拖死 BAS, 但也意味着 listener 静默失败需要 listener 自己监控

6. **commit 0 (SaveListener API) 是否独立先 push?**
   - 推荐: 是. 独立 push 后 BetterBackup MVP Phase 0 可以立即开始, 跟 v0.7 主体并行
   - v0.7 主体落地之前 SaveListener API 已稳定一段时间, BetterBackup 可以提前接入测试

## 10. 时间估算

按 v0.6 实施速度类比 (10 commit / ~3 天):

- Commit 0 (SaveListener API): 半天 (设计简单, 2 个接口 + 1 个 registry + fire 点改动)
- Commit 1-7 (v0.7 主体): 3-4 天 (mixin / state / snapshot / pipeline / mixin / diag, 与 v0.6 同结构同强度)
- Commit 8 (文档): 半天

**总计 4-5 天工作日, 1.5 周日历时间** (含 v0.6 同步 soak 监控)。

## 11. 跟 BetterBackup MVP 的接力

| 阶段 | 时间 | BAS 工作 | BetterBackup 工作 |
|---|---|---|---|
| T+0 | 第 1 天 | Commit 0 (SaveListener API) push | 等 |
| T+1 | 第 2 天 | 开始 v0.7 主体 (Commit 1-3) | Phase 0 项目骨架 + 依赖 BAS Commit 0 |
| T+2 | 第 3-4 天 | v0.7 主体 (Commit 4-7) | Phase 1 核心 dedup + manifest |
| T+5 | 第 5 天 | Commit 8 文档 + version bump 0.7.0 | Phase 1 收尾 |
| T+6 | 第 6 天起 | v0.7 soak (24h+) | Phase 2 调度 + 命令 |

`SavedDataSaveListener` 接口在 Commit 5 暴露后, BetterBackup 可以立即接入 Phase 2 (那时 BetterBackup 进度大约也到 SavedData 备份阶段), 时机吻合。

---

## 待你回复

请逐项回复 §9 的 6 个待确认问题, 我据此调整最终方案后开始 Commit 0。

如果你有其他考虑没在 §9 列出的, 也一并提出。
