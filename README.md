# BetterAutoSave

Minecraft 1.20.1 Forge 服务端存档优化 mod, 通过节流分摊与异步 IO 代理消除 autosave 主线程尖刺。

## 工作原理 (v0.1)

vanilla 的 autosave 流程在每 6000 tick (5 分钟) 一次, 主线程一次性遍历所有 dirty chunk 调用 `ChunkSerializer.write`, 80 mod / 60 人服上单次尖刺常达 200 ms 至数秒。

v0.1 mixin 拦截 `ChunkMap.saveAllChunks(false)` 把所有 dirty chunk 入 `SaveScheduler` 优先级队列, 在随后 6000 ticks 内每 server tick 出队 N 个交 vanilla `save(ChunkAccess)` 主线程同步处理, 通过节流把"集中尖刺"摊成"持续小幅"。`AdaptiveThrottle` 监控 `MinecraftServer.getAverageTickTime()`, TPS<19.5 时半速, TPS<19 跳过本 tick, 离 deadline 不足 30s 时强制全速保证不溢出周期。

v0.1 不替换 NBT 构建, 主线程仍跑 `ChunkSerializer.write` 但不再批量集中。`worker pool` 与 `SnapshotPipeline` 数据结构已就位但 v0.1 未真正接管 NBT 构建, 留 v0.2 路线图。

## 数据安全保证

- 所有提交不弱于 vanilla: 关服 `saveAllChunks(true)` 走 vanilla 同步 flush, mod 仅在前置阶段 drainPending 等 in-flight 落盘
- generation 计数器在 `ChunkAccess.setUnsaved(true)` 时实时递增, IO 完成回调按 generation 比对决定 CLEAN 还是 REQUEUE_DIRTY
- worker 异常走 `task.onUnhandledError` 推动状态机到 FAILED, 用尽重试后下个 tick 走 vanilla 同步 fallback, 严禁吞异常
- worker thread 强制 daemon=false, JVM 退出前必须 join 才能确保已构建未提交 NBT 不被强杀丢失

## 安装

依赖:
- Minecraft 1.20.1
- Minecraft Forge 47.3.22 或更新 (47.3 系列)
- Java 17 (Eclipse Temurin)

把构建产物 `build/libs/betterautosave-0.1.0.jar` 放进服务端 `mods/` 目录, 启动后服务端配置文件生成在 `config/betterautosave-server.toml`。

## 配置项 (config/betterautosave-server.toml)

| 字段 | 默认 | 说明 |
|---|---|---|
| general.enabled | true | 总开关, false 时所有 mod 逻辑跳过, 等价 vanilla |
| throttle.chunksPerTickBase | 4 | 主线程每 tick 处理 chunk 上限 |
| throttle.entityChunksPerTickBase | 2 | 实体路径独立预算, v0.1 暂未使用 |
| throttle.adaptiveEnabled | true | TPS 自适应节流, 仅基准测试时关闭 |
| throttle.deadlineGuardSeconds | 30 | 周期内剩余秒数低于此值强制全速 |
| workers.chunkWorkerThreads | 2 | chunk worker 池大小 |
| workers.entityWorkerThreads | 2 | entity worker 池大小, v0.1 暂未真正使用 |
| safety.shutdownTimeoutSeconds | 60 | 关服等 worker join 总超时 |
| safety.maxRetries | 3 | IO 失败重试次数, 用尽后 fallback 到 vanilla 同步路径 |
| compat.eventCompatMode | PARTIAL | ChunkDataEvent.Save 兼容档位, v0.1 未生效, 留 v0.2 |
| diagnostics.diagnosticLogging | false | 周期日志输出 metrics |
| diagnostics.diagnosticLogIntervalTicks | 200 | 周期日志间隔 (20 tick = 1s) |

## 运行时命令 (op level 2)

- `/betterautosave debug` 完整诊断, 含 mode, 配置, 计数器, 队列深度, p50/p99/max 三段延迟
- `/betterautosave metrics` 单行指标摘要
- `/betterautosave flush` 同步等所有 in-flight 落盘
- `/betterautosave status` 单行 mode 输出

## 性能预期

实测目标 (待用户基准):
- vanilla autosave 主线程尖刺典型 30-200 ms (大型 mod 包可达 1-2 s)
- v0.1 单 tick 主线程消耗约 base * (per-chunk 序列化时间), 默认 4 chunks/tick * ~1ms = 约 4ms/tick, 摊到整个 6000 ticks 周期
- TPS 抖动减少, autosave 期间不再有可感知的"卡顿点"

v0.1 不减少 cumulative CPU 时间, 总耗时与 vanilla 相同。如需 cumulative 收益请等待 v0.2。

## v0.2 路线图

- worker 端完整 NBT 构建 (复制 vanilla `ChunkSerializer.write` 主体逻辑或通过 mixin Accessor 暴露 vanilla helper)
- ChunkDataEvent.Save 两阶段 tag 分发 (PARTIAL 模式真实生效)
- 实体保存路径异步化 (mixin `EntityStorage.storeEntities` 与 `PersistentEntitySectionManager.autoSave`)
- ChunkAccess.setUnsaved 路径上的 generation 比对真实推动 IO_PENDING 状态机

## 已知限制 / 兼容性

- 与 Smooth Chunk Save 不兼容, 后者 mixin 同样切入 `ChunkMap.processUnloads`, 二选一即可。BetterAutoSave 与之相比的核心差异: 不延迟落盘 (无 300s 数据丢失窗口), 不取消 vanilla autosave 路径, 不吞异常
- 与 Lithium, Starlight 等 chunk 优化 mod 未做兼容性测试, 谨慎共用
- v0.1 实体路径不接管, 实体保存仍走 vanilla 同步, 大量实体场景仍可能产生主线程尖刺

## 构建 / 开发

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew build         # 编译 + reobf jar + 跑测试
./gradlew test          # 仅跑单元测试 (30 个)
./gradlew runServer     # 启 dev server (run/server)
./gradlew runClient     # 启 dev client (run/client)
```

构建产物: `build/libs/betterautosave-0.1.0.jar`

## 快速回退

服上出现异常需要恢复, 三档可选, 都不会破坏世界数据:

1. 临时禁用: 编辑 `config/betterautosave-server.toml` 把 `general.enabled` 改 `false`, 服务端 `/reload` 或重启。mod 仍加载但所有逻辑跳过, 等价 vanilla 行为
2. 完全卸载: 把 `betterautosave-0.1.0.jar` 从 `mods/` 移走重启即可。world data 由 vanilla autosave 持续保护, 卸载不会丢数据
3. 配置回滚: 仅调 `chunksPerTickBase` (1-64) 与 `adaptiveEnabled` 找平衡, 不必整 mod 卸载

## 跑图观察清单 (生产首跑)

服务端日志按时间顺序应出现:
1. 启动: `BetterAutoSave installing pipeline for <ServerName>`
2. 启动: `BetterAutoSave pipeline started with N chunk workers and M entity workers`
3. 启动: 每个 worker 一行 `Worker BetterAutoSave-Chunk-Worker-K started`
4. 启动: `BetterAutoSave pipeline installed`
5. 第一次 autosave 触发 (5min 后): `BetterAutoSave intercepted autosave: enqueued N dirty chunks from minecraft:overworld`
6. 几秒内: `BetterAutoSave first throttled chunk dispatched: <pos> dim=minecraft:overworld - throttling path verified` (此行只打一次, 是节流路径生效的关键证据)
7. 之后每隔 `diagnosticLogIntervalTicks` (默认 200=10s) 一行 metrics 摘要, 仅在有变化时输出

如出现:
- 未见 5/6 行: 说明 mixin 未注入或路径短路, 立刻 `/betterautosave debug` 看 `submitted` 是否 > 0
- 大量 `Throttled chunk save failed` ERROR: 说明 vanilla save 内部某处对 invoker 路径不友好, 切回退档 1
- worker 异常 + 进入 degraded mode WARN: 自动 fallback vanilla, 但需排查根因

关服必须出现:
- `BetterAutoSave server stopping, draining workers`
- 每个 worker 一行 `Worker ... stopped, queue size 0`
- `BetterAutoSave all workers joined cleanly`

任一缺失或 `queue size > 0` 说明有 in-flight 任务被丢, 重启后跑 `/betterautosave debug` 对比 `failed` 计数。

## 工程纪律

参见 `CLAUDE.md`。摘要:
- 严禁幻觉 API, 所有 vanilla 类名/方法名/字段名以 1.20.1 真实反编译为准
- 严禁吞异常, 失败必须 ERROR 日志加状态机降级
- 严禁 emoji 与 TODO 占位, 注释只解释 why
- 提交规范 Conventional Commits 中文, 严禁 Co-Authored-By Claude 之类 AI 署名

## 许可

见 LICENSE 文件。
