# BetterAutoSave

Minecraft 1.20.1 Forge 服务端存档优化 mod。主线程做最少必要的快照, worker 线程构 NBT 与提交 IO, 消除 chunk / entity / SavedData 保存路径的主线程尖刺并降低累计 CPU 开销。

> **Forge 1.20.1 生态位**: BAS 是当前 Forge 1.20.1 上**唯一活跃维护**的 chunk save 异步化方案 (Moonrise 仅 Fabric / NeoForge, C2MEF 已 archived, Starlight Forge 已 archived). 详见 [ROADMAP — 生态调研](ROADMAP.md#forge-1201-生态调研与-bas-定位-2026-05).
>
> **v0.7 SavedData 异步化**: vanilla `DimensionDataStorage.save()` 主线程同步写 .dat 文件 (raids / Forced / mod 自定义如 MTR mtr_train_data.dat), 大型 SavedData 文件可达 50-200ms 主线程 spike. v0.7 mixin 接管, 主线程仅构 tag, NBT 序列化 + IO 移到独立 worker pool. **完全空白生态位** — 任何已知 Forge mod 都不覆盖此路径.

## 目录

- [工作原理](#工作原理)
- [数据安全保证](#数据安全保证)
- [安装](#安装)
- [配置项](#配置项-configshinoyuki-optimizeshinoyuki_betterautosavecommontoml)
- [运行时命令](#运行时命令-op-level-2)
- [性能预期与实战参考](#性能预期)
- [路线图](#路线图)
- [已知限制 / 兼容性](#已知限制--兼容性)
- [构建 / 开发](#构建--开发)
- [快速回退](#快速回退)
- [跑图观察清单](#跑图观察清单-生产首跑)

## 工作原理

### vanilla 的同步存档路径

vanilla 的 chunk 保存有三条主线程同步路径, 全部走 `ChunkSerializer.write` (内部跑 `PalettedContainer` 的 codec 编码). 大型 mod 包 80 mod / 60 人服上单次尖刺常达 200 ms 至数秒, 主线程完全停摆:

1. **Autosave**: 每 6000 tick (5 分钟) `saveAllChunks(false)` 遍历所有 dirty chunk
2. **Unload**: `ChunkMap.scheduleUnload` 末尾的 `save(chunk)` (玩家 teleport / chunk 卸载集中爆发)
3. **Eager save**: `processUnloads` 末尾每 tick 最多 20 个 `saveChunkIfNeeded` (10s cooldown, 把 autosave 周期摊平)

vanilla 的 entity 保存路径同样主线程同步:

4. **Entity autoSave**: `PersistentEntitySectionManager.autoSave / saveAll` -> `EntityStorage.storeEntities`, 主线程遍历 chunk 内所有 entity 调 `Entity.save(CompoundTag)`. 大型农场 / 刷怪塔 / 长效实体场景该循环可达数十毫秒 spike

vanilla 的 SavedData 保存路径同样主线程同步:

5. **SavedData**: `ServerLevel.save -> saveLevelData -> DimensionDataStorage.save()` 主线程顺序遍历 cache, 对每个 dirty SavedData 调 `savedData.save(file)` 走 `NbtIo.writeCompressed` 直接写盘. mod 注册的 SavedData (典型例子 MTR `mtr_train_data.dat` / ANTE 进出闸统计) > 10 MB 时单文件 50-200 ms 主线程 spike

### BAS 的接管策略

BAS 通过 mixin 把全部五条路径汇入异步管线, 主线程仅做最少必要的快照, NBT 编码与 IO 全部移到 worker 线程。

**chunk 路径 mixin (v0.2 + v0.4)**:
- `ChunkMapMixin` 拦截 `saveAllChunks(false)` (autosave), 把 dirty chunk 入 `SaveScheduler` 优先级队列, `MinecraftServerMixin.tickServer` TAIL hook 每 tick 节流出队
- `ChunkMapSaveMixin` 拦截 `ChunkMap.save(ChunkAccess)` HEAD, **同时覆盖 unload 与 eager save 两条路径** (vanilla 内 `scheduleUnload` 与 `saveChunkIfNeeded` 都走 `this.save`)

**entity 路径 mixin (v0.6)**:
- `EntityStorageMixin` 拦截 `EntityStorage.storeEntities(ChunkEntities)` HEAD, 主线程做 `Entity.save` 循环 (entity 内部读 AI / 库存 / 位置非线程安全, 必须主线程) 包装为 `ListTag`, worker 端做 outer tag 拼装 (Entities / Position / DataVersion) + 调 entity `IOWorker.store`. EntityStorage 是 per-level 单例, 用 `ConcurrentHashMap<Long, EntitySaveState>` 索引 per-chunk 状态机

**SavedData 路径 mixin (v0.7)**:
- `DimensionDataStorageMixin` 拦截 `DimensionDataStorage.save()` HEAD, 主线程遍历 cache 找 dirty SavedData, 调 `savedData.save(new CompoundTag())` 让 mod 序列化内部状态 (必须主线程, mod 实现可能持非线程安全 mutable state), 包装外层 tag (含 `data` 子 tag + DataVersion), 入独立 `savedDataWorkerQueue`. worker 端做 `NbtIo.writeCompressed` 写盘 + 触发 `SavedDataSaveListener`. 大文件守卫: 现存 .dat > `savedDataMaxFileSizeMB` 阈值时走 vanilla 同步 fallback 防 worker queue 堵死

**共用异步管线**:
- 所有路径汇入 `SnapshotPipeline.captureAndDispatchChunk`, 主线程做 *最少必要* 的 capture: `PalettedContainer.copy` 浅拷贝 sections, `DataLayer.copy` 拷贝 light, `Heightmap` raw `long[]` clone, `BlockEntity` 主线程预序列化为 `ListTag`, `ticks / structures / postProcessing / upgradeData` 引用持有
- worker 线程 (`SerializationWorker` -> `ChunkSaveTask` -> `ChunkNbtAssembler.assemble`) 调 mixin Invoker 暴露的 vanilla 私有 helper (`makeBiomeCodec` / `packStructureData` / `saveTicks`) 与 `BLOCK_STATE_CODEC` 字段, 拼装最终 sections `ListTag`, 与主线程构好的 core tag 合并, 调 `IOWorker.store(pos, tag)` 提交 IO
- IO future 完成回调按 `ChunkSaveState.generation` 比对决定 CLEAN 还是 REQUEUE_DIRTY (chunk 在 worker 处理期间被再次修改, 下个周期重新走完整路径)
- `SaveListenerRegistry` 公开 API 让下游 mod (典型: BetterBackup) 订阅 chunk / entity / SavedData 保存事件, IO 成功后 worker 线程触发, 异常 catch + log 不传播

### 节流与关服守卫

`AdaptiveThrottle` 监控 `MinecraftServer.getAverageTickTime()`, TPS<19.5 时主线程 capture 半速, TPS<19 跳过本 tick, 离 deadline 不足 30s 时强制全速保证不溢出周期。

**mustDrain 机制**: unload + eager save 路径下被接管的 chunk 由 mixin 标记 `mustDrain`, 关服前 `/betterautosave drain-unload` 与 `onServerStopping` 的 `joinWorkers` 据此区分 "已离开 vanilla 状态机但 worker 未完成 IO" 的 chunk, 保证不丢盘。设计上**无主线程阻塞** — 因为 capture 已对所有可变字段做了独立内存的快照, chunk 内存释放与 worker 落盘可以安全并行。

## 数据安全保证

- 所有提交不弱于 vanilla: 关服 `saveAllChunks(true)` 走 vanilla 同步 flush, mod 仅在前置阶段 `drainPending` 等 in-flight 落盘
- 关服守卫: `onServerStopping` 第一步调 `SaveScheduler.enterShutdownMode()`, 之后 `ChunkMap.save` mixin 因 `isShutdownMode` 不再接管, vanilla `saveAllChunks(true)` 内 `.filter(this::save)` 走纯同步路径
- generation 计数器在 `ChunkAccess.setUnsaved(true)` 时实时递增, IO 完成回调按 generation 比对决定 CLEAN 或 REQUEUE_DIRTY
- worker 异常走 `task.onUnhandledError` 推动状态机到 FAILED, 用尽重试后 fallback vanilla 同步路径, 严禁吞异常
- worker thread 强制 `daemon=false`, JVM 退出前必须 join 才能确保已构建未提交 NBT 不被强杀丢失
- 主线程 capture 只读不改 vanilla 数据结构 (`PalettedContainer.copy / DataLayer.copy / long[].clone`), worker 线程读到的全是独立内存, 写穿主线程数据被 `ServerThreadAssert` 校验
- mustDrain chunks IO 完成 (CLEAN_LANDED / FAILED_TERMINAL) 时 atomic clear 并 dec gauge, `mustDrainPending` 持续 > 0 表示有 chunk 已离开 vanilla 状态机但 worker 未完成, 关服 join 必须等到 0

## 安装

依赖:
- Minecraft 1.20.1
- Minecraft Forge 47.3.22 或更新 (47.3 / 47.4 系列均可)
- Java 17 (Eclipse Temurin)

把构建产物 `build/libs/shinoyuki_betterautosave-0.7.1.jar` 放进服务端 `mods/` 目录, 启动后配置文件生成在 `config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml`。所有 Shinoyuki 系列优化 mod 共享 `config/Shinoyuki-Optimize/` 父目录, 每 mod 一个子文件夹便于集中管理。

## 配置项 (config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml)

| 字段 | 默认 | 说明 |
|---|---|---|
| general.enabled | true | 总开关, false 时所有 mod 逻辑跳过, 等价 vanilla |
| throttle.chunksPerTickBase | 4 | 主线程每 tick capture chunk 上限 (autosave 路径) |
| throttle.entityChunksPerTickBase | 2 | 实体路径独立预算 |
| throttle.adaptiveEnabled | true | TPS 自适应节流, 仅基准测试时关闭 |
| throttle.deadlineGuardSeconds | 30 | 周期内剩余秒数低于此值强制全速 |
| workers.chunkWorkerThreads | 2 | chunk worker 池大小, 每个线程独立跑 NBT 拼装与 IO 提交 |
| workers.entityWorkerThreads | 2 | entity worker 池大小 |
| workers.savedDataWorkerThreads | 1 | v0.7: SavedData (.dat) worker 池, 1 通常够; 装大型 SavedData mod 时调到 2 |
| safety.shutdownTimeoutSeconds | 60 | 关服等 worker join 总超时, 也是 `/betterautosave drain-unload` 超时 |
| safety.maxRetries | 3 | chunk / entity IO 失败重试次数, 用尽后 fallback 到 vanilla 同步路径 |
| safety.savedDataMaxFileSizeMB | 50 | v0.7: SavedData 文件超此阈值走 vanilla 同步, 防 worker queue 被几十 MB 单文件堵死 |
| compat.eventCompatMode | PARTIAL | `ChunkDataEvent.Save` 兼容档位, 详见下方 |
| diagnostics.diagnosticLogging | true | 周期日志输出 metrics |
| diagnostics.diagnosticLogIntervalTicks | 200 | 周期日志间隔 (20 tick = 1s) |

### eventCompatMode 三档行为

| 档位 | 主线程做 | worker 做 | event tag | 兼容性 | 性能 |
|---|---|---|---|---|---|
| PARTIAL (默认) | core tag (无 sections) | sections + IO | core tag (无 sections) | 99% mod 透明; 仅读 sections 的监听器收到 null | 最高 |
| FULL | 完整 tag (含 sections) | 仅 IO | 完整 tag | 100% vanilla 等价 | 减半 (sections 主线程编码) |
| DISABLED | core tag (无 sections) | sections + IO | (不派发) | 监听 mod 静默失效 | 最高 + 省 event 派发 |

PARTIAL 是默认与推荐档。若你确认无 mod 监听 `ChunkDataEvent.Save` 可切 DISABLED 省每 chunk 一次 event 派发开销。监听器读 `tag.get("sections")` 必崩才需 FULL。

## 运行时命令 (op level 2)

- `/betterautosave debug` — 完整诊断: mode, 配置, 计数器, 队列深度, p50/p99/max 三段延迟, ChunkMap.save 三计数 + mustDrainPending
- `/betterautosave metrics` — 单行指标摘要
- `/betterautosave flush` — 同步等所有 in-flight 落盘
- `/betterautosave status` — 单行 mode 输出
- `/betterautosave force-async` — 强制对当前维度所有 LevelChunk 走一次异步路径 (诊断用)
- `/betterautosave drain-unload` — 阻塞等所有 mustDrain (unload + eager save 接管的) chunks 落盘, `shutdownTimeoutSeconds` 超时

## 性能预期

按主线程开销三个维度:

- **autosave 主线程尖刺消除**: 80 mod / 60 人服 vanilla autosave 单次尖刺 200ms~2s 完全消失, 主线程仅做 capture (浅拷贝 + BE 序列化), 默认 4 chunks/tick * ~150us = 约 0.6ms/tick, 摊到整个 6000 ticks 周期
- **unload spike 消除**: 大量玩家 teleport / chunk 卸载集中场景, vanilla `processUnloads` 内同步 `save(chunk)` 累计 50-200ms 主线程 spike, 改走 worker 并行后主线程仅做单 chunk capture (~150us)
- **eager save 常态开销下降**: vanilla `processUnloads` 末尾每 tick 最多 20 个 `saveChunkIfNeeded` 同步走 NBT 编码 (10s cooldown 把 autosave 周期摊平的代价), 这部分也搬到 worker 后, active 玩家分散的服上每 tick 主线程 NBT 编码归零

实测对比方案 (用户基准):
- 用 spark profiler `--only-ticks-over 50` 抓 60s 主线程 trace
- `ChunkSerializer.write` / `ChunkMap.save` frame 应从主线程消失, 出现在 `BetterAutoSave-Chunk-Worker-N` 线程
- 主线程 trace 仅剩 capture 段 (`PalettedContainer.copy` / `DataLayer.copy` / `BlockEntity.saveWithoutMetadata`)

诊断指标观察 (`/betterautosave debug`):
- capture p99 < 100us
- worker p99 > 0us (worker 真实跑 assemble)
- ChunkMap.save Async 计数 > 0 (跑图后), Fallback 应 = 0
- MustDrain pending 静止 30s 后归零

实战参考 (生产 80mod 60p 服, Forge 47.4 / Java 21 ZGC / Ryzen 9950X / 30 GB heap):
- autosave 路径: fallback 0%, capture p99 0.5ms, worker p99 5ms, BAS chunk worker 占 1.46%
- unload + eager save 路径 (v0.5.0 / 32 view 满速跑图 + 远距 TP 6h+): failed=0 fallback=0 mustDrainPending leak=0 chunkMapSaveAsync 数百万级 capture p99 500us worker p99 500us
- v0.5.1 cooldown 优化: bypass 速率从 100k/s 降到 ~400/s, 主线程 mixin check 开销 -0.05 ms/tick
- v0.6 实体路径 (待生产数据回填): 主线程 entity.save 循环搬到 BAS dispatch + worker, BAS-Entity-Worker 池终于真实工作

v0.6 entity 路径诊断指标 (`/betterautosave debug` 的 Entity 段):
- entitiesSubmitted > 0 (autoSave 周期触发后), Failed = 0
- BetterAutoSave-Entity-Worker-N 线程出现在 spark profile 里有真实工作负载 (v0.5.x 之前是空闲 1.46% / 0 工作)

## 路线图

详细方案与风险评估见 [ROADMAP.md](ROADMAP.md)。

### 已落地 (v0.7.1)

- autosave 路径 NBT 编码异步化 + EventCompatMode 三档兼容
- unload + eager save 路径 mixin 接管 (`ChunkMap.save` HEAD 拦截)
- 实体路径接管 (`EntityStorage.storeEntities` HEAD 拦截, 主线程 entity.save 移出 vanilla 同步循环)
- **SavedData 路径接管 (v0.7, `DimensionDataStorage.save` HEAD 拦截, 主线程构 tag 后异步写盘 + 大文件 fallback)**
- **SaveListener API (`SaveListenerRegistry`) — chunk / entity / SavedData 三类公开订阅接口, 解锁 BetterBackup 等下游 mod**
- mustDrain 状态机 (chunk + entity 共用 mustDrainPending gauge) + 关服 shutdownMode 守卫
- `/betterautosave debug / metrics / flush / status / force-async / drain-unload` 命令套件 (全部异步, 主线程 0 阻塞)
- bypass cooldown 优化 (POI flush + setReturnValue, 让 vanilla saveChunkIfNeeded cooldown 正确更新)
- Histogram bucket 扩展到 60s + ">60s" 溢出标签
- adaptive TPS 节流 + deadline guard

### 候选 / 已废弃

| 版本 | 范围 | 状态 |
|---|---|---|
| **v0.9** | 工具化 (Prometheus exporter / hottest-chunks / mod-tick-trace) | **下一个 minor** |
| ~~v0.8~~ | ~~chunk load 路径异步化~~ | 已废弃 (2026-05 生态调研, [详见 ROADMAP](ROADMAP.md#v080--chunk-load-路径异步化-已废弃)) |

## 已知限制 / 兼容性

完整兼容性矩阵 (Starlight / Modernfix / FerriteCore / Radium / worldgen mod / DimThread 等) 见 [ROADMAP — BAS 兼容性矩阵](ROADMAP.md#bas-兼容性矩阵-代码核对)。

- 与 Smooth Chunk Save 不兼容: 后者 mixin 同样切入 `ChunkMap.processUnloads`, 二选一即可。BAS 与之相比的核心差异: 不延迟落盘 (无 300s 数据丢失窗口), 不取消 vanilla autosave 路径, 不吞异常
- 与 Lithium 移植 (Radium / Canary) / Starlight Forge 兼容 (代码核对验证, 见 ROADMAP); 与 archived 的 C2MEF 直接冲突 (都拦 `ChunkMap.save`, 二选一)
- v0.6 entity 路径单个 entity.save 抛异常时按 vanilla equivalence LOGGER.error 跳过 (该 entity 不持久化), 与 vanilla EntityStorage 行为一致 ("It will not persist")
- PARTIAL 模式下 `ChunkDataEvent.Save` 监听器读 `tag.get("sections")` 会拿到 null。99% 的 mod 不读, 极少数读 sections 做统计的 mod 需要切 FULL 档
- worker 线程通过 mixin Invoker 调 vanilla `ChunkSerializer` 私有 helper, Forge 升级后 helper 改名 / 改签名会编译期 ERROR, 不会运行时静默
- chunk biomes 字段 `PalettedContainerRO` 的 copy 通过 `instanceof PalettedContainer` 反射; 极少数情况 (mod 自定义 PalettedContainerRO 实现) 会持有原引用, 极小概率读到不一致 biome (rare write race)
- 其他 mod 也 mixin `ChunkMap.save` 时, 注入顺序可能影响接管成功率, ChunkMap.save Fallback 计数会上升; `Fallback > 0` 不影响数据安全, 仅性能损失

## 构建 / 开发

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # 或 Windows 等价
./gradlew build         # 编译 + reobf jar + 跑测试
./gradlew test          # 仅跑单元测试
./gradlew runServer     # 启 dev server (run/server)
./gradlew runClient     # 启 dev client (run/client)
```

构建产物: `build/libs/shinoyuki_betterautosave-0.7.1.jar`

## 快速回退

服上出现异常需要恢复, 三档可选, 都不会破坏世界数据:

1. **临时禁用**: 编辑 `config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml` 把 `general.enabled` 改 `false`, 服务端 `/reload` 或重启。mod 仍加载但所有逻辑跳过, 等价 vanilla 行为 (含 unload + eager save + SavedData 路径)
2. **完全卸载**: 把 `shinoyuki_betterautosave-0.7.1.jar` 从 `mods/` 移走重启即可。world data 由 vanilla autosave 持续保护, 卸载不会丢数据
3. **配置回滚**: 仅调 `chunksPerTickBase` (1-64) 与 `eventCompatMode` 找平衡, 不必整 mod 卸载。怀疑 PARTIAL 引起 mod 兼容性问题就切 FULL

## 跑图观察清单 (生产首跑)

### 启动阶段 (按时间顺序)

1. `[BetterAutoSave] pipeline starting for <ServerName>`
2. `[BetterAutoSave]   |- workers: chunk=N entity=M` 与 throttle / event compat / config 路径行
3. 每个 worker 一行 `[BetterAutoSave] worker started: BetterAutoSave-Chunk-Worker-K`
4. `[BetterAutoSave] pipeline installed`

### 第一次 autosave 触发后 (5 min 后)

5. `[BetterAutoSave] autosave intercepted @ minecraft:overworld` 加 `mode: PARTIAL` 与 `enqueued N dirty chunks` 两行
6. 几秒内: `[BetterAutoSave] async pipeline verified: first chunk dispatched [<x>, <z>] @ minecraft:overworld` (此行只打一次, 是异步路径生效的关键证据)
7. 之后每隔 `diagnosticLogIntervalTicks` (默认 200=10s) 一段 metrics 树, 仅在有变化时输出。worker p99 应 > 0us

### unload + eager save 验证 (在 5/6 之后)

8. 玩家 teleport 跨维度或拉视距, 触发 unload + eager save 路径
9. `/betterautosave debug` 查看 `ChunkMap.save (v0.4)` 段:
    - `Async > 0`: mixin 接管成功 ← 关键证据
    - `Bypass`: 非 LevelChunk 或 isUnsaved=false 的调用, 正常情况会 > 0 (vanilla idempotent check)
    - `Fallback` 应 = 0: 持续 > 0 说明守卫触发或异常 fallback, 排查日志 `ChunkMap.save async dispatch failed` ERROR
    - `MustDrain pending`: 跑图过程中可短暂 > 0, 静止 30s 后应归零
10. 关服前可运行 `/betterautosave drain-unload` 主动等所有 mustDrain 落盘, 输出 `drained N mustDrain chunk(s) in Xms`

### 异常排查

- 未见 5/6 行: mixin 未注入或路径短路, 立刻 `/betterautosave debug` 看 `submitted` 是否 > 0
- ChunkMap.save Fallback 持续增长: 检查日志 ERROR / mod 兼容冲突 (其他 mod 也 mixin `ChunkMap.save`)
- 大量 `Throttled chunk save failed` ERROR: vanilla helper 调用某处异常, 切回退档 1
- worker 异常 + 进入 degraded mode WARN: 自动 fallback vanilla, 但需排查根因
- worker p99 长期为 0us: worker 没真正干活, 异步路径未接通, 检查 `eventCompatMode` 是否为 PARTIAL/DISABLED

### 关服必须出现

- `[BetterAutoSave] server stopping, draining workers`
- 每个 worker 一行 `[BetterAutoSave] worker stopped: ... (queue=0)`
- `[BetterAutoSave] all workers joined cleanly in <ms>ms`

任一缺失或 `queue size > 0` 说明有 in-flight 任务被丢, 重启后跑 `/betterautosave debug` 对比 `failed` 计数。

## 许可

见 LICENSE 文件。
