# BetterAutoSave

Minecraft 1.20.1 Forge 服务端存档优化 mod, 主线程 capture 快照, worker 线程构 NBT 与提交 IO, 消除 autosave 主线程尖刺并降低累计 CPU 开销。

## 工作原理 (v0.2)

vanilla 的 autosave 流程每 6000 tick (5 分钟) 一次, 主线程一次性遍历所有 dirty chunk 调用 `ChunkSerializer.write`。`write` 内部跑 `PalettedContainer` 的 codec 编码 (block_states / biomes), 大型 mod 包 80 mod / 60 人服上单次尖刺常达 200 ms 至数秒, 主线程完全停摆。

v0.2 把 NBT 构建从主线程移到 worker 线程:

- `ChunkMapMixin` 拦截 `ChunkMap.saveAllChunks(false)`, 把所有 dirty chunk 入 `SaveScheduler` 优先级队列
- `MinecraftServerMixin.tickServer` TAIL hook 每 tick 调 `SaveScheduler.onServerTick` 节流出队
- `SaveDispatcher.onPriorityDrained` 调 `SnapshotPipeline.captureAndDispatchChunk`, 在主线程做 *最少必要* 的 capture (PalettedContainer.copy 浅拷贝 sections, DataLayer.copy 拷贝 light, Heightmap raw long[] clone, BlockEntity 主线程预序列化为 ListTag, ticks / structures / postProcessing / upgradeData 引用持有), 把得到的 `ChunkSnapshot` 投递到 worker queue
- worker 线程 (`SerializationWorker` -> `ChunkSaveTask` -> `ChunkNbtAssembler.assemble`) 调 mixin Invoker 暴露的 vanilla 私有 helper (`makeBiomeCodec` / `packStructureData` / `saveTicks`) 与 `BLOCK_STATE_CODEC` 字段, 拼装最终 sections ListTag, 与主线程构好的 core tag 合并, 调 `IOWorker.store(pos, tag)` 提交 IO
- IO future 完成回调按 `ChunkSaveState.generation` 比对决定 CLEAN 还是 REQUEUE_DIRTY (chunk 在 worker 处理期间被再次修改, 下个 autosave 周期重新走完整路径)

`AdaptiveThrottle` 监控 `MinecraftServer.getAverageTickTime()`, TPS<19.5 时主线程 capture 半速, TPS<19 跳过本 tick, 离 deadline 不足 30s 时强制全速保证不溢出周期。

## 数据安全保证

- 所有提交不弱于 vanilla: 关服 `saveAllChunks(true)` 走 vanilla 同步 flush, mod 仅在前置阶段 drainPending 等 in-flight 落盘
- generation 计数器在 `ChunkAccess.setUnsaved(true)` 时实时递增, IO 完成回调按 generation 比对决定 CLEAN 还是 REQUEUE_DIRTY
- worker 异常走 `task.onUnhandledError` 推动状态机到 FAILED, 用尽重试后 fallback 到 vanilla 同步路径, 严禁吞异常
- worker thread 强制 daemon=false, JVM 退出前必须 join 才能确保已构建未提交 NBT 不被强杀丢失
- 主线程 capture 只读不改 vanilla 数据结构 (PalettedContainer.copy / DataLayer.copy / long[].clone), worker 线程读到的全是独立内存, 写穿主线程数据被 ServerThreadAssert 校验

## 安装

依赖:
- Minecraft 1.20.1
- Minecraft Forge 47.3.22 或更新 (47.3 / 47.4 系列均可)
- Java 17 (Eclipse Temurin)

把构建产物 `build/libs/shinoyuki_betterautosave-0.2.0.jar` 放进服务端 `mods/` 目录, 启动后配置文件生成在 `config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml`。所有 Shinoyuki 系列优化 mod 共享 `config/Shinoyuki-Optimize/` 父目录, 每 mod 一个子文件夹便于集中管理。

## 配置项 (config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml)

| 字段 | 默认 | 说明 |
|---|---|---|
| general.enabled | true | 总开关, false 时所有 mod 逻辑跳过, 等价 vanilla |
| throttle.chunksPerTickBase | 4 | 主线程每 tick capture chunk 上限 |
| throttle.entityChunksPerTickBase | 2 | 实体路径独立预算, v0.2 暂未使用 |
| throttle.adaptiveEnabled | true | TPS 自适应节流, 仅基准测试时关闭 |
| throttle.deadlineGuardSeconds | 30 | 周期内剩余秒数低于此值强制全速 |
| workers.chunkWorkerThreads | 2 | chunk worker 池大小, 每个线程独立跑 NBT 拼装与 IO 提交 |
| workers.entityWorkerThreads | 2 | entity worker 池大小, v0.2 暂未真正使用 |
| safety.shutdownTimeoutSeconds | 60 | 关服等 worker join 总超时 |
| safety.maxRetries | 3 | IO 失败重试次数, 用尽后 fallback 到 vanilla 同步路径 |
| compat.eventCompatMode | PARTIAL | ChunkDataEvent.Save 兼容档位, 详见下方 |
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

- `/betterautosave debug` 完整诊断, 含 mode, 配置, 计数器, 队列深度, p50/p99/max 三段延迟
- `/betterautosave metrics` 单行指标摘要
- `/betterautosave flush` 同步等所有 in-flight 落盘
- `/betterautosave status` 单行 mode 输出

## 性能预期

v0.2 把 NBT 编码从主线程移到 worker, 收益分两个维度:

- **主线程尖刺消除**: 80mod / 60 人服 vanilla autosave 单次尖刺 200ms~2s 完全消失, 主线程仅做 capture (浅拷贝 + BE 序列化), 默认 4 chunks/tick * ~150us = 约 0.6ms/tick, 摊到整个 6000 ticks 周期
- **累计 CPU 不再压主线程**: vanilla `ChunkSerializer.write` 在 v0.1 仍 100% 主线程; v0.2 主线程 capture 只占总时间约 30% (剩余是 PalettedContainer.copy 与 BlockEntity 序列化), 70% sections NBT 编码移到 2 个 worker 线程并行

实测对比方案 (用户基准):
- v0.1 + spark profiler 抓 60s 主线程 trace, 记 `ChunkSerializer.write` 累计 CPU
- v0.2 同地图同时段同操作 trace, `ChunkSerializer.write` 应在 worker 线程出现, 主线程只剩 `PalettedContainer.copy` 与 `BlockEntity.saveWithoutMetadata`

诊断指标观察 (`/betterautosave debug`):
- v0.1: capture p99 = 500us, worker p99 = 0us (worker 仅做 IO 提交)
- v0.2: capture p99 < 100us (主线程更轻), worker p99 > 0us (worker 真实跑 assemble)

## 路线图

v0.1 -> v0.2 已落地:
- worker 端完整 NBT 构建 (走 mixin Invoker 暴露 vanilla `ChunkSerializer` 私有 helper, 不复制 vanilla 代码)
- ChunkDataEvent.Save 三档兼容生效 (PARTIAL / FULL / DISABLED)
- 生产 80mod 60p 服实测: fallback 0%, capture p99 0.5ms, worker p99 5ms

v0.3+ 详细路线、技术方案、风险评估与实战数据汇总见 [ROADMAP.md](ROADMAP.md):
- v0.3 实体路径异步化 (`EntityStorage.storeEntities`)
- v0.4 chunk unload 路径异步化 (mustDrain + Phaser, 实战 spike 元凶之一)
- v0.5 chunk load 路径异步化 (实验性, `ChunkSerializer.read`)
- v0.6+ Prometheus exporter / mod tick trace / 图形化诊断面板

## 已知限制 / 兼容性

- 与 Smooth Chunk Save 不兼容, 后者 mixin 同样切入 `ChunkMap.processUnloads`, 二选一即可。BetterAutoSave 与之相比的核心差异: 不延迟落盘 (无 300s 数据丢失窗口), 不取消 vanilla autosave 路径, 不吞异常
- 与 Lithium, Starlight 等 chunk 优化 mod 未做兼容性测试, 谨慎共用
- v0.2 实体路径不接管, 实体保存仍走 vanilla 同步, 大量实体场景仍可能产生主线程尖刺
- PARTIAL 模式下 ChunkDataEvent.Save 监听器读 `tag.get("sections")` 会拿到 null。99% 的 mod 不读, 极少数读 sections 做统计的 mod 需要切 FULL 档
- worker 线程通过 mixin Invoker 调 vanilla `ChunkSerializer` 私有 helper, Forge 升级后 helper 改名/改签名会编译期 ERROR, 不会运行时静默
- chunk biomes 字段 PalettedContainerRO 的 copy 通过 instanceof PalettedContainer 反射; 极少数情况 (mod 自定义 PalettedContainerRO 实现) 会持有原引用, 极小概率读到不一致 biome (rare write race)

## 构建 / 开发

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew build         # 编译 + reobf jar + 跑测试
./gradlew test          # 仅跑单元测试
./gradlew runServer     # 启 dev server (run/server)
./gradlew runClient     # 启 dev client (run/client)
```

构建产物: `build/libs/shinoyuki_betterautosave-0.2.0.jar`

## 快速回退

服上出现异常需要恢复, 三档可选, 都不会破坏世界数据:

1. 临时禁用: 编辑 `config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml` 把 `general.enabled` 改 `false`, 服务端 `/reload` 或重启。mod 仍加载但所有逻辑跳过, 等价 vanilla 行为
2. 完全卸载: 把 `shinoyuki_betterautosave-0.2.0.jar` 从 `mods/` 移走重启即可。world data 由 vanilla autosave 持续保护, 卸载不会丢数据
3. 配置回滚: 仅调 `chunksPerTickBase` (1-64) 与 `eventCompatMode` 找平衡, 不必整 mod 卸载。怀疑 PARTIAL 引起 mod 兼容性问题就切 FULL

## 跑图观察清单 (v0.2 生产首跑)

服务端日志按时间顺序应出现:
1. 启动: `[BetterAutoSave] pipeline starting for <ServerName>`
2. 启动: `[BetterAutoSave]   |- workers: chunk=N entity=M` 与 throttle / event compat / config 路径行
3. 启动: 每个 worker 一行 `[BetterAutoSave] worker started: BetterAutoSave-Chunk-Worker-K`
4. 启动: `[BetterAutoSave] pipeline installed`
5. 第一次 autosave 触发 (5min 后): `[BetterAutoSave] autosave intercepted @ minecraft:overworld` 加 `mode: PARTIAL` 与 `enqueued N dirty chunks` 两行
6. 几秒内: `[BetterAutoSave] async pipeline verified: first chunk dispatched [<x>, <z>] @ minecraft:overworld` (此行只打一次, 是异步路径生效的关键证据)
7. 之后每隔 `diagnosticLogIntervalTicks` (默认 200=10s) 一段 metrics 树, 仅在有变化时输出。worker p99 应 > 0us

如出现:
- 未见 5/6 行: 说明 mixin 未注入或路径短路, 立刻 `/betterautosave debug` 看 `submitted` 是否 > 0
- 大量 `Throttled chunk save failed` ERROR: 说明 vanilla helper 调用某处异常, 切回退档 1
- worker 异常 + 进入 degraded mode WARN: 自动 fallback vanilla, 但需排查根因
- worker p99 长期为 0us: 说明 worker 没真正干活, 异步路径未接通, 检查 `eventCompatMode` 是否为 PARTIAL/DISABLED

关服必须出现:
- `[BetterAutoSave] server stopping, draining workers`
- 每个 worker 一行 `[BetterAutoSave] worker stopped: ... (queue=0)`
- `[BetterAutoSave] all workers joined cleanly in <ms>ms`

任一缺失或 `queue size > 0` 说明有 in-flight 任务被丢, 重启后跑 `/betterautosave debug` 对比 `failed` 计数。


## 许可

见 LICENSE 文件。
