# BetterAutoSave

Minecraft 1.20.1 Forge 服务端存档优化 mod。主线程做最少必要的快照, worker 线程构 NBT 与提交 IO, 消除 chunk 保存路径的主线程尖刺并降低累计 CPU 开销。

## 工作原理

vanilla 的 chunk 保存有三条主线程同步路径都走 `ChunkSerializer.write` (内部跑 `PalettedContainer` 的 codec 编码), 大型 mod 包 80 mod / 60 人服上单次尖刺常达 200 ms 至数秒, 主线程完全停摆:

1. **Autosave**: 每 6000 tick (5 分钟) `saveAllChunks(false)` 遍历所有 dirty chunk
2. **Unload**: `ChunkMap.scheduleUnload` 末尾的 `save(chunk)` (玩家 teleport / chunk 卸载集中爆发)
3. **Eager save**: `processUnloads` 末尾每 tick 最多 20 个 `saveChunkIfNeeded` (10s cooldown, 把 autosave 周期摊平)

BAS 接管全部三条路径, 走同一套异步管线:

- `ChunkMapMixin` 拦截 `saveAllChunks(false)` (autosave), 把 dirty chunk 入 `SaveScheduler` 优先级队列, `MinecraftServerMixin.tickServer` TAIL hook 每 tick 节流出队
- `ChunkMapSaveMixin` 拦截 `ChunkMap.save(ChunkAccess)` HEAD, **同时覆盖 unload 与 eager save 两条路径** (vanilla 内 `scheduleUnload` 与 `saveChunkIfNeeded` 都走 `this.save`)
- 所有路径汇入 `SnapshotPipeline.captureAndDispatchChunk`, 主线程做 *最少必要* 的 capture: `PalettedContainer.copy` 浅拷贝 sections, `DataLayer.copy` 拷贝 light, `Heightmap` raw `long[]` clone, `BlockEntity` 主线程预序列化为 `ListTag`, `ticks / structures / postProcessing / upgradeData` 引用持有
- worker 线程 (`SerializationWorker` -> `ChunkSaveTask` -> `ChunkNbtAssembler.assemble`) 调 mixin Invoker 暴露的 vanilla 私有 helper (`makeBiomeCodec` / `packStructureData` / `saveTicks`) 与 `BLOCK_STATE_CODEC` 字段, 拼装最终 sections `ListTag`, 与主线程构好的 core tag 合并, 调 `IOWorker.store(pos, tag)` 提交 IO
- IO future 完成回调按 `ChunkSaveState.generation` 比对决定 CLEAN 还是 REQUEUE_DIRTY (chunk 在 worker 处理期间被再次修改, 下个周期重新走完整路径)

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

把构建产物 `build/libs/shinoyuki_betterautosave-0.5.1.jar` 放进服务端 `mods/` 目录, 启动后配置文件生成在 `config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml`。所有 Shinoyuki 系列优化 mod 共享 `config/Shinoyuki-Optimize/` 父目录, 每 mod 一个子文件夹便于集中管理。

## 配置项 (config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml)

| 字段 | 默认 | 说明 |
|---|---|---|
| general.enabled | true | 总开关, false 时所有 mod 逻辑跳过, 等价 vanilla |
| throttle.chunksPerTickBase | 4 | 主线程每 tick capture chunk 上限 (autosave 路径) |
| throttle.entityChunksPerTickBase | 2 | 实体路径独立预算, 实体异步化未实现前预留 |
| throttle.adaptiveEnabled | true | TPS 自适应节流, 仅基准测试时关闭 |
| throttle.deadlineGuardSeconds | 30 | 周期内剩余秒数低于此值强制全速 |
| workers.chunkWorkerThreads | 2 | chunk worker 池大小, 每个线程独立跑 NBT 拼装与 IO 提交 |
| workers.entityWorkerThreads | 2 | entity worker 池大小, 实体异步化未实现前预留 |
| safety.shutdownTimeoutSeconds | 60 | 关服等 worker join 总超时, 也是 `/betterautosave drain-unload` 超时 |
| safety.maxRetries | 3 | IO 失败重试次数, 用尽后 fallback 到 vanilla 同步路径 |
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

## 路线图

当前已实现 (v0.5.1):
- autosave 路径 NBT 编码异步化 + EventCompatMode 三档兼容
- unload + eager save 路径 mixin 接管 (`ChunkMap.save` HEAD 拦截)
- mustDrain 状态机 + 关服 shutdownMode 守卫
- `/betterautosave debug / metrics / flush / status / force-async / drain-unload` 命令套件 (全部异步, 主线程 0 阻塞)
- bypass cooldown 优化 (POI flush + setReturnValue, 让 vanilla saveChunkIfNeeded cooldown 正确更新)
- adaptive TPS 节流 + deadline guard

候选 (详细方案与风险评估见 [ROADMAP.md](ROADMAP.md)):
- v0.6 实体路径异步化 (`EntityStorage.storeEntities`, 与 chunk 路径同构, 复用 entity worker pool, 下一个 minor)
- v0.7 SavedData / DimensionDataStorage 异步化 (装大型 mod 如 MTR / ANTE 时收益高)
- v0.8 chunk load 路径异步化 (实验性, `ChunkSerializer.read`, 风险高)
- v0.9 工具化 (Prometheus exporter / hottest-chunks / mod-tick-trace)

## 已知限制 / 兼容性

- 与 Smooth Chunk Save 不兼容: 后者 mixin 同样切入 `ChunkMap.processUnloads`, 二选一即可。BAS 与之相比的核心差异: 不延迟落盘 (无 300s 数据丢失窗口), 不取消 vanilla autosave 路径, 不吞异常
- 与 Lithium / Starlight 等 chunk 优化 mod 未做兼容性测试, 谨慎共用
- 实体路径仍走 vanilla 同步, 大量实体场景 (大型农场 / 刷怪塔) 仍可能产生主线程尖刺
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

构建产物: `build/libs/shinoyuki_betterautosave-0.5.1.jar`

## 快速回退

服上出现异常需要恢复, 三档可选, 都不会破坏世界数据:

1. **临时禁用**: 编辑 `config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml` 把 `general.enabled` 改 `false`, 服务端 `/reload` 或重启。mod 仍加载但所有逻辑跳过, 等价 vanilla 行为 (含 unload + eager save 路径)
2. **完全卸载**: 把 `shinoyuki_betterautosave-0.5.1.jar` 从 `mods/` 移走重启即可。world data 由 vanilla autosave 持续保护, 卸载不会丢数据
3. **配置回滚**: 仅调 `chunksPerTickBase` (1-64) 与 `eventCompatMode` 找平衡, 不必整 mod 卸载。怀疑 PARTIAL 引起 mod 兼容性问题就切 FULL

## 跑图观察清单 (生产首跑)

服务端日志按时间顺序应出现:
1. 启动: `[BetterAutoSave] pipeline starting for <ServerName>`
2. 启动: `[BetterAutoSave]   |- workers: chunk=N entity=M` 与 throttle / event compat / config 路径行
3. 启动: 每个 worker 一行 `[BetterAutoSave] worker started: BetterAutoSave-Chunk-Worker-K`
4. 启动: `[BetterAutoSave] pipeline installed`
5. 第一次 autosave 触发 (5 min 后): `[BetterAutoSave] autosave intercepted @ minecraft:overworld` 加 `mode: PARTIAL` 与 `enqueued N dirty chunks` 两行
6. 几秒内: `[BetterAutoSave] async pipeline verified: first chunk dispatched [<x>, <z>] @ minecraft:overworld` (此行只打一次, 是异步路径生效的关键证据)
7. 之后每隔 `diagnosticLogIntervalTicks` (默认 200=10s) 一段 metrics 树, 仅在有变化时输出。worker p99 应 > 0us

unload + eager save 路径验证 (在 5/6 之后):

8. 玩家 teleport 跨维度或拉视距, 触发 unload + eager save 路径
9. `/betterautosave debug` 查看 `ChunkMap.save (v0.4)` 段:
    - `Async > 0`: mixin 接管成功 ← 关键证据
    - `Bypass`: 非 LevelChunk 或 isUnsaved=false 的调用, 正常情况会 > 0 (vanilla idempotent check)
    - `Fallback` 应 = 0: 持续 > 0 说明守卫触发或异常 fallback, 排查日志 `ChunkMap.save async dispatch failed` ERROR
    - `MustDrain pending`: 跑图过程中可短暂 > 0, 静止 30s 后应归零
10. 关服前可运行 `/betterautosave drain-unload` 主动等所有 mustDrain 落盘, 输出 `drained N mustDrain chunk(s) in Xms`

异常排查:
- 未见 5/6 行: mixin 未注入或路径短路, 立刻 `/betterautosave debug` 看 `submitted` 是否 > 0
- ChunkMap.save Fallback 持续增长: 检查日志 ERROR / mod 兼容冲突 (其他 mod 也 mixin `ChunkMap.save`)
- 大量 `Throttled chunk save failed` ERROR: vanilla helper 调用某处异常, 切回退档 1
- worker 异常 + 进入 degraded mode WARN: 自动 fallback vanilla, 但需排查根因
- worker p99 长期为 0us: worker 没真正干活, 异步路径未接通, 检查 `eventCompatMode` 是否为 PARTIAL/DISABLED

关服必须出现:
- `[BetterAutoSave] server stopping, draining workers`
- 每个 worker 一行 `[BetterAutoSave] worker stopped: ... (queue=0)`
- `[BetterAutoSave] all workers joined cleanly in <ms>ms`

任一缺失或 `queue size > 0` 说明有 in-flight 任务被丢, 重启后跑 `/betterautosave debug` 对比 `failed` 计数。

## 许可

见 LICENSE 文件。
