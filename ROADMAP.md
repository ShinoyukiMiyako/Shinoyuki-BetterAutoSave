# BetterAutoSave 路线图

记录 v0.2 之后的演进路线、技术方案、实战数据与风险评估。

## 目录

- [当前状态总览](#当前状态总览)
- [v0.2 落地实战数据](#v02-落地实战数据)
- [已落地版本详解](#已落地版本详解)
  - [v0.4 — chunk unload + eager save 路径异步化](#v040--chunk-unload--eager-save-路径异步化-phase-c-已落地)
  - [v0.6 — 实体路径异步化](#v060--实体路径异步化-已落地)
  - [v0.7 — SavedData 异步化 + SaveListener API](#v070--saveddata-dimensiondatastorage-异步化--savelistener-api-已落地)
- [Forge 1.20.1 生态调研与 BAS 定位](#forge-1201-生态调研与-bas-定位-2026-05)
- [候选版本](#候选版本)
  - [v0.9 — 工具化与诊断](#v09--工具化与诊断-原-v07)
- [已废弃](#已废弃)
  - [v0.8 — chunk load 异步化](#v080--chunk-load-路径异步化-已废弃)
- [附录: 实战观察笔记](#附录-实战观察笔记)

## 当前状态总览

单一表覆盖已落地、候选、已废弃三类。

| 版本 | 状态 | 范围 | 主要消除点 / 实战指标 |
|---|---|---|---|
| v0.1 | 已落地 (历史) | autosave 节流分摊 + IOWorker 代理 | 集中尖刺 → 分散小幅; fallback 31-68% (跑图场景) |
| v0.2 | 已落地 | NBT 编码异步化 + EventCompatMode 三档 | `ChunkSerializer.write` 完全离开主线程; fallback 0%, capture p99 0.5ms, worker p99 5ms |
| v0.4 | 已落地 | unload + eager save 路径接管 | `processUnloads` 内 `save` 主线程同步 NBT 消除; mustDrain 接通 + 三计数 + drain-unload |
| v0.5.0 | 已落地 | v0.4 三个稳定 fix | drain-unload 异步化 / mustDrain gauge 配对 / DiagnosticLogger 输出 v0.4 计数; 生产 6h+ 跑图 failed=0 fallback=0 leak=0 |
| v0.5.1 | 已落地 | bypass cooldown 优化 + flush 异步化 | mixin 在 isUnsaved=false 时 setReturnValue(true) 让 saveChunkIfNeeded cooldown 正确更新; bypass 速率 100k/s → 400/s |
| v0.6.0 | 已落地 | entity 路径接管 + Histogram bucket 扩展 | `EntityStorage.storeEntities` 主线程 entity.save 循环移到 BAS dispatch + worker; BAS-Entity-Worker 池真实工作 |
| v0.7.0 | 已落地 | SavedData / DimensionDataStorage 异步化 + SaveListener 公开 API | `DimensionDataStorage.save` 主线程同步 NBT/IO 移到 worker; chunk/entity/SavedData 三类 listener API 解锁 BetterBackup |
| **v0.9** | **下一个 minor** | 工具化 (Prometheus exporter / hottest-chunks / mod-tick-trace) | 让用户自助定位 mod / vanilla 瓶颈 |
| ~~v0.8~~ | 已废弃 (2026-05) | ~~chunk load 路径异步化~~ | 2026-05 生态调研后决定不做, 见专门小节 |

> **后续实施顺序建议**: v0.7 已落地, **v0.9 工具化** 是下一个 minor.
> 顺序原则: v0.8 chunk load 已废弃, 不在路线图; v0.9 工具化为最后候选.

> **实施顺序调整**: 原计划 v0.3 (实体路径异步化) → v0.4 (unload). 实施时跳过 v0.3 直接做 v0.4, 因 unload spike 是用户最痛点 (实战 0.55% spike + teleport 集中场景 50-200ms). v0.3 实体路径转为 v0.6 候选 (路线图后挪一档).

### 各版本 narrative 摘要

v0.2 把 vanilla autosave 周期内的 NBT 编码主线程开销 (`PalettedContainer` codec / `BlockState` codec / `biomeCodec`) 完全搬到 worker 线程, 主线程仅保留浅拷贝、BlockEntity 序列化、core tag 构建三件不可避免的工作。

v0.4 把同等的处理覆盖到 vanilla `ChunkMap.save(ChunkAccess)` 入口, 同时接管 `scheduleUnload` (unload 路径) 与 `saveChunkIfNeeded` (eager save 10s cooldown 路径) 两条调用点。设计上**无 Phaser 主线程阻塞** — 详见 v0.4 段。

v0.5.1 修复 v0.5.0 生产观察到的 bypass 暴涨 (chunk 已 clean 但 vanilla 每 tick 全扫 visibleChunkMap 反复进 mixin), 通过让 mixin 在 isUnsaved=false 时主动 flush POI + setReturnValue(true) 让 saveChunkIfNeeded 把 cooldown 设到 10s 后, 该 chunk 安静一段时间不再被反复检查。

v0.7 把同等思路覆盖到 vanilla `DimensionDataStorage.save()` (autosave 周期内顺序写所有 dirty .dat 文件), 主线程仅做 mod-specific tag 构建 (`savedData.save(CompoundTag)` 必须主线程), NBT 序列化 + IO 移到独立 savedDataWorkerQueue. 同时暴露 `SaveListenerRegistry` 公开 API (chunk / entity / SavedData 三通道) 解锁 BetterBackup 等下游 mod 接入.

## v0.2 落地实战数据

(生产 80mod 60p 服, Forge 47.4 / Java 21 ZGC / Ryzen 9950X / 30 GB heap)

### v0.2 自身负担

```
runServer + 2 玩家 (1 挂机 + 1 建筑) 1h+ uptime
BetterAutoSave-Chunk-Worker (x2): 1.46% (873ms / 600s)
BetterAutoSave-Entity-Worker (x2): 1.46% (空闲, v0.2 未接管)
IO-Worker (vanilla, x14): 2.79% (1670ms / 600s)
主线程 BAS frame: 测量不到 (< 0.05%)
```

### MSPT spike 元凶分析

spark `--only-ticks-over 50`, 10 分钟, max=206ms

| 嫌疑 | spike-only frame | 性质 | BAS 路线图覆盖 |
|---|---|---|---|
| MTR `RailwayData.simulateTrains` | 0.52% | mod hot path, 列车 forEach 全表扫 | 否 (mod 配置层解决) |
| `ChunkSerializer.read` (chunk load 同步) | 0.29% | vanilla 加载新 chunk 反序列化 | 原 v0.5 (现 v0.8 已废弃) |
| `DistanceManager.runAllUpdates` (光照传播) | 1.01% | vanilla chunk 距离重排 + light propagation | 间接缓解 |
| `processUnloads` → `saveChunkIfNeeded` | 0.55% | vanilla unload 同步保存 | v0.4 (已落地) |
| Lightman's Currency `BankAPIImpl.GetAllBankAccounts` | 0.16% | mod hot path, 每 tick 全表扫 | 否 |
| ZGC STW | 0.034ms max / 0 stall | 完全无影响 | n/a |

v0.2 已 100% 解决其能解决的部分 (`saveAllChunks(false)` 路径主线程 NBT). 剩余 spike 元凶分两类:
1. mod hot path 需 mod 自身或社区 fork 解决
2. vanilla `read` / unload 路径同步 IO, BAS v0.4 (已落地) 接管 unload, chunk load 路径已废弃 (见 v0.8 废弃节)

## 已落地版本详解

### v0.4.0 — chunk unload + eager save 路径异步化 (Phase C) [已落地]

**优先级**: 高 (实战 spike 0.55% 占比, 用户最痛点)
**实施技术风险**: 中 (低于初设计预期)
**期望收益**: 消除 `processUnloads` 同步 save 路径 + eager save 路径每 tick 主线程 NBT 编码

#### 实施 vs 原设计 — 偏差

原设计 (撰写于 v0.4 实施前):
- 主线程通过 **Phaser 阻塞**等 chunk IO 完成才允许 unload
- 10 个原子提交, 含 MustDrainPhaser + 超时回退 + 独立优先级 + stress test

实施时 (v0.4 落地版本):
- **没有 Phaser**, 主线程 mixin HEAD 接管后立即返回 (`cir.setReturnValue(true)` + cancel)
- 6 个原子提交, mustDrain 仅作"语义标记"供关服 join 与诊断命令查询

为什么砍 Phaser: 重新审视 v0.2 `ChunkCaptureProcedure.capture` 已在主线程对所有可变字段做独立内存的快照 (`PalettedContainer.copy` / `DataLayer.copy` / `Heightmap raw long[].clone` / `BlockEntity` 主线程预序列化为 ListTag), worker 持有的 snapshot 与活 chunk 内存完全解耦。`level.unload(chunk)` 仅 `clearAllBlockEntities` (worker 不再读 BE) 与 `unregisterTickContainerFromLevel` (从 ServerLevel 反注册, 不改 chunk 内部 LevelChunkTicks 实例), 因此 chunk 内存释放与 worker 落盘可以**安全并行**, 不需要 Phaser 阻塞主线程。

副作用: 比原设计简洁约一半, 关服安全由复用 v0.2 已有 `pipeline.drainPending` + `joinWorkers` 流程保证。

#### 实施细节 (落地版本)

mixin 目标:
- `ChunkMap.save(ChunkAccess)` HEAD inject + cancellable, **单点同时接管**:
  * `scheduleUnload` 内 lambda 的 `this.save(chunk)`
  * `saveChunkIfNeeded` 内 cooldown 触发的 `this.save(chunk)`
- 关服路径 `saveAllChunks(true)` 内 `.filter(this::save)` 由 `SaveScheduler.isShutdownMode()` 守卫避开

数据流:
1. mixin 检查守卫 (isInstalled / enabled / isDegraded / isShutdownMode)
2. chunk 校验 (是 LevelChunk + isUnsaved) - 否则 bypass
3. 拿 `ChunkSaveState`, phase 派发 (CLEAN/DIRTY -> dispatch; SNAPSHOTTING/SERIALIZING/IO_PENDING -> 已在管线; FAILED -> 不接管)
4. `tryMarkMustDrain()` CAS false→true, 首次置位 inc `mustDrainPending` gauge
5. `pipeline.captureAndDispatchChunk` 走 v0.2 同款管线
6. dispatch 异常: `compareAndClearMustDrain` 清掉 mustDrain, dec gauge, 让 vanilla 同步路径处理

关服路径:
- `BetterAutoSaveMod.onServerStopping` 第一步调 `scheduler.enterShutdownMode()` (v0.2 遗漏修复)
- 之后 vanilla `saveAllChunks(true)` 内的 `save` 调用因 isShutdownMode 守卫不被异步路径接管, 走 vanilla 同步 flush
- `pipeline.drainPending` + `joinWorkers` 等所有未完成 mustDrain chunks 落盘

#### 已落地 6 commit

1. `fix(lifecycle): onServerStopping 调用 scheduler.enterShutdownMode` (v0.2 遗漏修复)
2. `feat(state): mustDrain 接通状态机 (CLEAN_LANDED / FAILED_TERMINAL 时 atomic clear)`
3. `feat(metrics): chunkMapSave 三计数 + mustDrainPending gauge`
4. `feat(mixin): ChunkMap.save HEAD 拦截 v0.4 unload + eager save 接管`
5. `feat(command): /betterautosave drain-unload + debug 显示 v0.4 计数`
6. `docs(readme+roadmap): v0.4 实施说明 + 与原 Phaser 设计的偏差`

#### 风险评估 (实施后回顾)

| 原设计列出的风险 | 实施后状态 |
|---|---|
| chunk 内存释放但 worker 还在拼 NBT | OK 不发生: capture 已固化独立内存, worker 不读活 chunk |
| Phaser 死锁 | OK N/A: 无 Phaser |
| 主线程阻塞反而比同步慢 | OK N/A: 无主线程阻塞 |
| 服务器崩溃丢数据 | NOTE 边界与 v0.2 autosave 等价: chunk 在 worker 完成前崩溃, 该 chunk 落盘失败, 与 vanilla autosave 失败等价 |

新发现的风险:
- **mod 兼容**: 其他 mod 也 mixin `ChunkMap.save` 时 (例如未知优化 mod), 二者注入顺序可能 conflict, BAS Fallback 计数会上升, 不影响数据安全, 仅性能损失。监控 `/betterautosave debug` 的 ChunkMap.save Fallback 列。
- **ticks 字段 race**: worker 持有 `LevelChunkTicks` 引用, 主线程 `setBlockTick` 与 worker `saveTicks` 并发 — 但此 race **v0.2 autosave 路径已存在**, 不是 v0.4 引入的新风险。

#### kill -9 数据完整性

待 stress test 验证 (后续工作). 理论分析: 已 dispatch 到 worker 但未完成 IO 的 chunk, 在 kill -9 下数据丢失边界与 v0.2 autosave 同等。

### v0.6.0 — 实体路径异步化 [已落地]

**当前状态**: 已落地. 9 commit 连推, gradle build pass, 生产 5min+ 数据 0 failed / 0 fallback.

**优先级**: 中
**技术风险**: 低 (与 v0.2 同构, 实测如此)
**期望收益**: 实体多场景 (大型农场 / 怪物刷怪) 主线程消除 entity NBT 编码开销

#### 范围

mixin 目标:
- `EntityStorage.storeEntities(ChunkEntities)` — 实体保存入口, 在 `world.level.chunk.storage` 包
- `PersistentEntitySectionManager.autoSave` 与 `saveAll` — 调度入口
- `EntityStorage` 内部的 `worker` 字段通过 mixin Accessor 暴露 (与 v0.1 `ChunkStorageAccessor` 同构)

数据结构:
```
EntitySnapshot record {
    ChunkPos pos;
    ResourceKey<Level> dimension;
    int dataVersion;
    ListTag entitiesNbt;       // 主线程 Entity.saveWithoutId 预序列化
    long capturedGeneration;
    EntitySaveState state;     // 与 ChunkSaveState 平行
}
```

主线程做:
1. 遍历 chunk 内所有 Entity, 调 `Entity.saveWithoutId` 预序列化 (实体内部读 AI / 位置 / 库存 不线程安全, 必须主线程)
2. 包装为 ListTag

worker 线程做:
1. 拼装 outer CompoundTag: `{ Entities: [...], Position: [x, z], DataVersion: N }`
2. 调 entity IOWorker.store(pos, tag)
3. future 完成回调按 generation 比对推进状态机

复用 v0.2 已就位的 entity worker pool (默认 2 thread)。

#### 顺手做 (Phase 0 — 垃圾回收, CLAUDE.md 第 0 步法则)

实体路径开发前先做诊断 cosmetic 清理:

1. **`fix(diag): Histogram p99 / max 溢出 bucket 显示 ">5s"`**
   - 现状: `BUCKET_UPPER_BOUNDS_NS` 最后一个 bucket 是 `Long.MAX_VALUE`,
     当 IO p99 落到 [5s, ∞) bucket 时, `percentile()` 返回 `Long.MAX_VALUE`,
     除以 1000 显示为 9223372036854775us, 数字反人类
   - 触发场景: TP 集中 / 跑图集中导致 vanilla IOWorker 单线程串行写
     region file 排队, 单次 IO > 5s. 与 v0.5.x 已知现象一致, 非 BAS bug
   - 修复方案 A (推荐): 扩 bucket 到 10s / 30s / 60s, 让常见极端场景
     有合理 p99 数值, 仅最罕见的 > 60s 才落到 ">60s" 标签
   - 修复方案 B: DiagnosticLogger / debug 命令输出层加格式化, 检测
     `p99Ns == Long.MAX_VALUE` 时显示 ">5s" / ">60s"
   - 选 A: 数据更精确, 代码改动小, percentile 算法不变, 仅扩 bucket 数组
   - 副作用: SaveMetricsTest 已用 `<= 1_000_000L` 等宽容断言, 不需改
   - 工作量: < 30 行, 1 commit

#### 已落地 commit (10 个原子提交)

0. `fix(diag): Histogram bucket 扩展到 60s + p99 溢出 ">60s" 标签` (Phase 0 顺手清理)
1. `feat(mixin): EntityStorageAccessor 暴露 worker 字段` (Phase 1)
2. `feat(state): EntitySaveState 状态机 + EntitySaveStateAccess 接口` (Phase 2.2)
3. `refactor(snapshot): EntitySnapshot v0.6 字段清单 (与 ChunkSnapshot 同构)` (Phase 2.1)
4. `feat(snapshot): EntityCaptureProcedure 主线程预序列化 Entity.save` (Phase 2.3)
5. `feat(snapshot): EntityNbtAssembler worker 拼 outer tag` (Phase 3.1)
6. `feat(snapshot): EntitySaveTask + SaveMetrics 加 entityRetried/Fallback` (Phase 3.2)
7. `feat(mixin): EntityStorage.storeEntities HEAD 拦截 v0.6 entity 路径接管` (Phase 4)
8. `feat(command+diag): /betterautosave debug + DiagnosticLogger entity 段` (Phase 5)
9. `docs(readme+roadmap): v0.6 entity 路径接管说明` (Phase 6)

#### 实施 vs 设计 — 偏差

设计 vs 实施基本吻合. 几个细节调整:

- **拦截点选 EntityStorage.storeEntities 而非 PESM.autoSave**: 后者是上层调度,
  前者是真正主线程开销. 拦底层一刀切, 同时覆盖 autoSave / saveAll /
  processChunkUnload 三条调用点. 与 ChunkMapSaveMixin 拦底层 ChunkMap.save
  策略一致.
- **EntitySaveStateAccess 用 ConcurrentHashMap by packed pos**: EntityStorage
  是 per-level 单例, 不像 ChunkAccess per-chunk 实例可挂字段. 折中方案是
  mixin 给 EntityStorage 实例挂一个 ConcurrentHashMap 索引 per-chunk 状态机.
- **EntitySaveTask 持有 IOWorker 引用而非走 AsyncIoBridge**: 避免
  ServerLevel -> entityManager (private) -> permanentStorage (private) ->
  worker (private) 三层 accessor 链, mixin inject 时 EntityStorage 实例已经
  提供 worker 字段访问.
- **空 chunk 让 vanilla 处理**: vanilla EntityStorage.storeEntities 内 isEmpty()
  分支有 emptyChunks 优化 + null tag 写盘语义, 绕开会破坏该优化.
- **inFlightSerializing / inFlightIoPending 与 chunk 路径共享**: gauge 反映
  总管线压力, 不分 chunk / entity.

#### 风险

| 风险 | 缓解 |
|---|---|
| Entity AI / Goal / Brain 在主线程 tick 与序列化竞争 | 主线程预序列化时调 `Entity.saveWithoutId` 必须在 entity tick 完成后做; mixin 在 PersistentEntitySectionManager.autoSave HEAD 注入而非 entity tick 路径 |
| 持久化实体 (mob, item, vehicle) 与 LootTable / EntityType 注册时序 | 引用持有 RegistryAccess.Frozen, 与 v0.2 同处理 |
| 大批量实体 (刷怪塔) 主线程预序列化时间过长 | 可配置每 tick 实体上限 (复用 entityChunksPerTickBase) |

### v0.7.0 — SavedData (DimensionDataStorage) 异步化 + SaveListener API [已落地]

**当前状态**: 已落地. 7 commit + 1 文档 commit, gradle build pass, 待生产数据回填.

**优先级**: 中 (依赖具体服 mod, 装大型 mod 如 MTR 时升至高)
**技术风险**: 低 (与 v0.2 同构, 文件粒度比 chunk 粗)
**实测收益**: 消除 vanilla autosave 周期内 `dataStorage.save()` 主线程同步序列化, 大型 SavedData 文件场景 (MTR / ANTE) 50-200ms spike 移到 worker 线程.

#### 背景

vanilla `ServerLevel.save` 在 autosave 周期内顺序调用:
```java
this.dataStorage.save();          // SavedData 全部同步序列化 (主线程)
serverChunkCache.save(flush);     // chunk 路径 (v0.2 已接管)
this.entityManager.autoSave();    // entity 路径 (v0.6 已接管)
```

`DimensionDataStorage.save()` 同步遍历 cache map, 每个 dirty SavedData 调 `savedData.save(file)` 走 `NbtIo.writeCompressed` 直接写盘.
mod 注册的 SavedData (典型例子: MTR `mtr_train_data.dat` / ANTE 进出闸统计) 文件越大单次序列化越慢, > 10 MB 时单文件 50-200 ms 主线程 spike.

#### 实施细节 (落地版本)

mixin 目标:
- `DimensionDataStorage.save()` HEAD inject + cancellable
- `DimensionDataStorageInvoker` 暴露 `getDataFile(String)` 私有方法
- `cache` 字段通过 `@Shadow @Final` 直接访问

数据结构 (实际落地, 比 V0_7_PLAN 设计简化):
```java
public record SavedDataSnapshot(
    String fileName,
    File targetFile,
    CompoundTag preBuiltTag,
    SavedData savedData
) {}
```

无独立状态机. 与 ChunkSaveState / EntitySaveState 不同 — DimensionDataStorage.save 是 per-cycle (5min) 调用, 不存在高频并发, 不需要 CAS 状态保护; setDirty 在 mixin dispatch 时乐观清理, 失败时 worker 通过 `server.execute` 重新 setDirty(true) 让下个周期重试.

数据流:
1. mixin 守卫: isInstalled / enabled / isDegraded / isShutdownMode / server 非空
2. 遍历 `cache.entrySet()`, 对每个 dirty SavedData:
   - 大文件守卫: 现存 .dat > `savedDataMaxFileSizeMB` (默认 50 MB) 走 vanilla 同步 fallback
   - 主线程构 tag: `tag.put("data", savedData.save(new CompoundTag()))` + `NbtUtils.addCurrentDataVersion`
   - 入 `savedDataWorkerQueue`, 计 metrics.recordSavedDataSubmitted
   - `savedData.setDirty(false)` 乐观清理
3. ci.cancel() 跳过 vanilla iteration

worker 线程 (`SavedDataSaveTask`):
1. `NbtIo.writeCompressed(tag, file)` 写盘
2. 成功 → metrics.recordSavedDataCompleted + `SaveListenerRegistry.fireSavedDataWritten`
3. 失败 → metrics.recordSavedDataFailed + `server.execute(() -> savedData.setDirty())` 重新 mark dirty 让下个周期重试

#### SaveListener API (附带交付物, 解锁 BetterBackup)

新增公开 API package `com.shinoyuki.betterautosave.api`:
- `ChunkSaveListener` — chunk save 事件
- `EntityChunkSaveListener` — entity chunk save 事件
- `SavedDataSaveListener` — SavedData 事件 (v0.7 新增)
- `SaveListenerRegistry` — 静态注册中心 + fire 方法

特性:
- `CopyOnWriteArrayList` 保证注册 / 注销 / fire 三方线程安全
- 空 listener fast path (`isEmpty()` 检查), 单装 BAS 用户零开销
- listener 异常 try-catch + log, 不传播
- fire 在 IO 成功路径 (CLEAN_LANDED / REQUEUE_DIRTY), 不在 FAILED

工程量: 1 commit (Commit 0), ~80 行 API + ~80 行单测.

#### 已落地 8 commit

0. `feat(api): SaveListener API + Registry, ChunkSaveTask/EntitySaveTask fire 点`
1. `feat(mixin): DimensionDataStorageInvoker 暴露 getDataFile`
2. `feat(api): SavedDataSaveListener + Registry savedData 通道`
3. `feat(snapshot): SavedDataSnapshot record + SavedDataSaveTask + Metrics 计数`
4. `feat(pipeline): SnapshotPipeline 加 savedDataWorkerQueue + 配置项`
5. `feat(mixin): DimensionDataStorage.save HEAD 拦截 v0.7 SavedData 异步化`
6. `feat(diag+command): DiagnosticLogger 与 debug 命令输出 SavedData 段`
7. `docs(readme+roadmap): v0.7 SavedData 落地 + version bump 0.7.0`

#### 实施 vs 设计 — 偏差

设计 vs 实施基本吻合. 几个细节调整:

- **跳过独立状态机 SavedDataSaveState**: V0_7_PLAN §3.3 设计了与 ChunkSaveState / EntitySaveState 同构的状态机. 实施时跳过 — DimensionDataStorage.save() 是 per-cycle 调用 (5min 一次), 不存在高频并发 / unload race / multi-call coordination. 直接用 SavedDataSnapshot record + 失败 setDirty(true) 简单 retry, 比预期少约 100 行代码.
- **大文件守卫基于现存文件大小**: V0_7_PLAN 没指定阈值判断时机. 实施按"现存 .dat > 阈值"判断 (`file.exists() && file.length() > maxBytes`). 新文件首次写无法预判, 仅基于已有文件大小决策 — 第二次 save 大文件时才会被守卫拦下.
- **乐观清 dirty + 失败重 mark**: V0_7_PLAN §7.3 已分析此 race, 实施按计划. setDirty(false) 在 mixin dispatch 时主线程做, IO 失败时 worker 通过 `server.execute(() -> savedData.setDirty())` 上主线程 re-mark dirty. 这跟 vanilla 行为差异很小 (vanilla 也是 try-catch 后 setDirty(false), 失败也清).

#### 配置项

```toml
[workers]
savedDataWorkerThreads = 1                       # SavedData worker 池大小

[safety]
savedDataMaxFileSizeMB = 50                      # 超阈值走 vanilla 同步
```

#### 风险评估 (实施后回顾)

| 原设计列出的风险 | 实施后状态 |
|---|---|
| SavedData 持有 mod 内部 mutable state, worker 序列化时被改 | OK 不发生: 主线程 `savedData.save(new CompoundTag())` 已拿到完整 tag, worker 仅 IO 不读 mod 状态 |
| 关服时 SavedData 仍在 worker queue 未落盘 | OK 已守卫: SnapshotPipeline.joinWorkers 已含 savedDataWorkerThreads 一并 join |
| 文件写到一半崩溃 | NOTE 边界与 vanilla 同等: NbtIo.writeCompressed 不是 atomic rename, kill -9 可能写一半. 跟 vanilla SavedData.save 同等风险 |
| mod 在 SavedData.save 内部抛异常 | OK 已守卫: try-catch fallback 到 vanilla 同步路径, recordSavedDataFallback 计数 |

新发现的风险:
- **大文件守卫第一次写无法保护**: 文件第一次写超过阈值仍会进 worker queue, 单次 IO 占用 worker 几秒; 第二次 save 才会被守卫拦下走 vanilla. 真实场景下单文件第一次写已经存在性能问题, 但生产 workload 单 .dat 极少 > 50 MB.
- **setDirty 主线程 hop**: 失败重 mark dirty 通过 `server.execute` 派回主线程. 在主线程繁忙时 (autosave 阶段) 这条会排队, 但 setDirty 是 O(1) 操作, 实际无可观测影响.

#### 实战触发条件

- `du -h world/data/*.dat world/DIM-1/data/*.dat world/DIM1/data/*.dat` 出现 > 10 MB 单文件
- spark spike-only profile 中 `DimensionDataStorage.save` 或 `SavedData.save` frame 占比 > 0.5%
- mtr_train_data.dat 持续增长 (ANTE 进出闸统计无清理机制)

#### kill -9 数据完整性

待 stress test 验证 (后续工作). 理论分析:
- 已 dispatch 到 worker 但未完成 IO 的 SavedData, kill -9 下数据丢失边界**比 vanilla 更宽** (vanilla setDirty(false) 在 try 之外, 我们 setDirty(false) 在 dispatch 时即清, worker 完成前进程死则丢)
- 但 SavedData 总是从内存 mod 状态构造, 重启后下次 setDirty(true) 仍会再写, 所以 kill -9 影响 = "丢一个 cycle 的 SavedData 增量", 跟 vanilla 行为差异极小

## Forge 1.20.1 生态调研与 BAS 定位 (2026-05)

调研 chunk 异步化方向上的生态竞品后, 确认 BAS 在 Forge 1.20.1 上无活跃维护的竞争对手, 并据此**放弃 v0.8 chunk load 异步化方向** (详见已废弃节).

### 主流 chunk 异步化 mod 状态

| Mod | 平台 | 1.20.1 Forge 支持 | 当前状态 | 覆盖范围 |
|---|---|---|---|---|
| Moonrise (Spottedleaf, [Tuinity/Moonrise](https://github.com/Tuinity/Moonrise)) | Fabric + NeoForge | 否 | 活跃维护 (1.21.x 起) | Paper chunk system 移植 + Starlight |
| C2ME (Fabric, ishland) | Fabric | n/a | 活跃维护 | chunk gen + IO + load 全套 |
| C2MEF ([RelativityMC/C2ME-forge](https://github.com/RelativityMC/C2ME-forge)) | Forge | 是 (0.2.0+alpha.12) | 2025-07-12 已 archived | chunk gen + IO + load, 不碰 SavedData |
| Starlight Forge | Forge | 是 (1.1.2) | 已 archived | LightEngine 重写 |
| BAS | Forge | 是, 活跃维护 | 当前 v0.6 | chunk save + entity save (v0.7 加 SavedData) |

**结论**: Forge 1.20.1 上 BAS 是**唯一活跃维护**的 chunk save 异步化方案, 无活跃竞品. v0.7 SavedData 异步化更是完全空白生态位 — Moonrise / C2MEF / 任何已知 mod 都不覆盖 `DimensionDataStorage.save()`.

### BAS 兼容性矩阵 (代码核对)

BAS mixin 拦截点 (见 [shinoyuki_betterautosave.mixins.json](src/main/resources/shinoyuki_betterautosave.mixins.json)):
- `ChunkMap.saveAllChunks(boolean)` HEAD
- `ChunkMap.save(ChunkAccess)` HEAD
- `EntityStorage.storeEntities(ChunkEntities)` HEAD
- 几个 Accessor / Invoker (纯 getter, 无业务逻辑撞车)

BAS 不碰: LightEngine 内部状态 / chunk 加载 / worldgen / ChunkStatus 升级链 / entity tick / 网络包 / 渲染.

| 优化 mod | 兼容性 | 理由 |
|---|---|---|
| Starlight (Forge 1.20.1) | 兼容 | BAS 只用公共 API `getDataLayerData()` 读 DataLayer, Starlight 必须保持该 API 契约 (vanilla save 也用), `DataLayer.copy()` 是 `byte[].clone` 与底层引擎实现解耦 |
| C2MEF (Forge 1.20.1 alpha) | 直接冲突 | 都拦 `ChunkMap.save()`, 同装会双层 mixin. 但 C2MEF 已 archived, 不构成实际威胁 |
| Modernfix | 兼容 | 内存 / 启动优化, 不碰 chunk save 路径 |
| FerriteCore | 兼容 | 改 BlockState 内存表示, BAS 用 `PalettedContainer.copy()` 走标准路径 |
| Radium / Canary (Lithium 移植) | 兼容 | 改 entity / block tick, 不碰 save 路径 |
| Embeddium / Rubidium | 完全无关 | 客户端渲染, 不影响服务端 chunk save |
| Forge worldgen mod (Terralith / BetterEnd / Tectonic / Cataclysm) | 兼容 (FULL 模式) | FULL 模式调 `ChunkSerializer.write` 触发 `ChunkDataEvent.Save`, 跟 vanilla 路径一致, mod inject 正常 |
| 同上 (DISABLED 模式) | 可能冲突 | DISABLED 跳过事件触发, 深度依赖 `ChunkDataEvent.Save` 的 mod 会丢自定义 NBT. 默认是 FULL, 用户主动切 DISABLED 才有问题, 配置说明已标注 |
| DimThread | 中风险 | 给每维度独立 tick 线程, BAS 的 `ServerThreadAssert` 可能炸. 需单独适配 |

## 候选版本

### v0.9+ — 工具化与诊断 (原 v0.7)

**优先级**: 中 (v0.8 废弃后升一档)
**目的**: 让用户能自己定位 mod hot path / vanilla 瓶颈, 不依赖外部 spark profiler

#### 候选功能

- **Prometheus exporter**: BAS metrics 暴露 HTTP `/metrics` 端点, 接入 Grafana 监控生产服
- **`/betterautosave hottest-chunks`**: 列出最近 N 次 autosave 中 capture / worker p99 最高的 chunks (定位单点慢 chunk, 通常是 BlockEntity 多 / structure 复杂)
- **`/betterautosave mod-tick-trace`**: 用 Forge `ServerTickEvent` 装饰器统计每个 mod 的 ServerTick listener 自身耗时, 一行表格列出 top 10 mod
- **图形化诊断面板**: 替代纯 INFO 日志, embed web UI (类似 spark-viewer 风格)

## 已废弃

### v0.8.0 — chunk load 路径异步化 [已废弃]

**状态**: 废弃 (2026-05 生态调研后决定不做)

#### 放弃理由

1. **vanilla 1.20.1 已经把容易的部分异步化了**: `IOWorker.loadAsync` 已异步, `ChunkSerializer.read` 已在 `Util.backgroundExecutor`, `ProtoChunk` 构造已在 worker 池. 真正还在主线程的是 ChunkStatus 升级链 + LightEngine 增量 + PoiManager 加载 + EntityStorage 注入, 不是简单"丢线程池"能解决.

2. **核心难点是 ChunkStatus 升级链的跨区块依赖图**: feature 阶段需要邻 8 区块的 noise 完成 (worldgen 跨区块结构生成), light 阶段需要邻区块的 features 完成 (光照传播). 这是有向依赖图, 需要 actor 模型 + async/await 重构 (vanilla 自己用 `ChunkTaskPriorityQueueSorter` + `ChunkHolder.FullChunkStatus` 做这件事, 但保留主线程作为协调点). 工作量数量级超出 BAS 单人项目可行范围 — C2ME 团队多人多年迭代 + ishland 自研, 仍然不时报线程安全 bug.

3. **mod 兼容是地雷**: 大量 worldgen mod (Terralith / BetterEnd / Tectonic / Cataclysm 等) 在 ChunkStatus 升级链上 `@Inject`, BAS 动这里几乎必然撞车; chunk save 路径只有少数 mod inject 自定义 NBT, 冲突面小一个数量级.

4. **替代方案存在 (虽不完美)**: 若用户真有 chunk load 痛点, 装 archived 的 C2MEF 0.2.0+alpha.12 + 自担 alpha 风险, 比 BAS 重新设计实现一个生产级 chunk load 异步化更现实. BAS 不复刻 C2ME, 专注 SAVE 路径继续做透.

#### 替代方向

- **v0.9 工具化优先级提升**: 出 chunk save / load 阶段耗时 metrics, 让用户用真实数据自己判断瓶颈, 不盲改.
- **v0.7 完成后**, 如果用户压测仍提到 chunk load spike, 再考虑做**单一阶段** (例如只做 PoiManager 加载或 EntityStorage 注入), 不正面铺开整个 ChunkStatus 升级链.

## 附录: 实战观察笔记

> 以下根据生产 spark 数据归纳, 在做下一版决策时参考。
> 这些观察可能在不同服 mod 组合下不成立。

- **MTR (Minecraft Transit Railway)** 是 80mod 服里最容易引起 mspt spike 的 mod, 它每 tick `LinkedHashMap.forEach` 遍历全部列车数据。无 BAS 可解决空间, 需 mod 配置或 PR 给上游。
- **Lightman's Currency** 每 tick `GetAllBankAccounts` 全表扫银行账户, 类似反模式。
- **`DistanceManager.runAllUpdates` 与 `ChunkTracker` 光照传播** 在 chunk load/unload 集中时相互放大 (光照网络重算), 是 vanilla 设计层面的限制, BAS 路线 v0.5+ 才能间接缓解。
- **ZGC** 在 30GB heap 配置下表现完美 (STW max 34μs, 0 allocation stall), 内存维度无需进一步优化。
- **多人(2+) 跑图 + 建筑** 场景 max mspt 79ms→206ms, 但 95%ile 仍 4ms, TPS 全程 20。spike 是孤立 outlier 而非常态。
