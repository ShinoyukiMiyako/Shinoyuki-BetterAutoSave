# BetterAutoSave 路线图

记录 v0.2 之后的演进路线、技术方案、实战数据与风险评估。

## v0.1 / v0.2 现状

| 版本 | 范围 | 主线程主要消除点 | 实战指标 |
|---|---|---|---|
| v0.1 | autosave 节流分摊 + IOWorker 代理 | 集中尖刺 → 分散小幅 | fallback 31-68% (跑图场景) |
| v0.2 | NBT 编码异步化 + EventCompatMode 三档 | `ChunkSerializer.write` 完全离开主线程 | fallback 0%, capture p99 0.5ms, worker p99 5ms |

v0.2 把 vanilla autosave 周期内的 NBT 编码主线程开销 (`PalettedContainer` codec / `BlockState` codec / `biomeCodec`) 完全搬到 worker 线程, 主线程仅保留浅拷贝、BlockEntity 序列化、core tag 构建三件不可避免的工作。

## v0.2 实战数据 (生产 80mod 60p 服, Forge 47.4 / Java 21 ZGC / Ryzen 9950X / 30 GB heap)

### v0.2 自身负担
```
runServer + 2 玩家 (1 挂机 + 1 建筑) 1h+ uptime
BetterAutoSave-Chunk-Worker (x2): 1.46% (873ms / 600s)
BetterAutoSave-Entity-Worker (x2): 1.46% (空闲, v0.2 未接管)
IO-Worker (vanilla, x14): 2.79% (1670ms / 600s)
主线程 BAS frame: 测量不到 (< 0.05%)
```

### MSPT spike 元凶分析 (spark `--only-ticks-over 50`, 10 分钟, max=206ms)

| 嫌疑 | spike-only frame | 性质 | BAS 路线图覆盖 |
|---|---|---|---|
| MTR `RailwayData.simulateTrains` | 0.52% | mod hot path, 列车 forEach 全表扫 | 否 (mod 配置层解决) |
| `ChunkSerializer.read` (chunk load 同步) | 0.29% | vanilla 加载新 chunk 反序列化 | v0.5 |
| `DistanceManager.runAllUpdates` (光照传播) | 1.01% | vanilla chunk 距离重排 + light propagation | v0.5+ |
| `processUnloads` → `saveChunkIfNeeded` | 0.55% | vanilla unload 同步保存 | v0.4 |
| Lightman's Currency `BankAPIImpl.GetAllBankAccounts` | 0.16% | mod hot path, 每 tick 全表扫 | 否 |
| ZGC STW | 0.034ms max / 0 stall | 完全无影响 | n/a |

v0.2 已 100% 解决其能解决的部分 (`saveAllChunks(false)` 路径主线程 NBT). 剩余 spike 元凶分两类: (1) mod hot path 需 mod 自身或社区 fork 解决; (2) vanilla `read` / unload 路径同步 IO, BAS v0.4 / v0.5 接管。

## v0.3.0 — 实体路径异步化 (Phase B)

**优先级**: 中
**技术风险**: 低 (与 v0.2 同构)
**期望收益**: 实体多场景 (大型农场 / 怪物刷怪) 主线程消除 entity NBT 编码开销

### 范围

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

### Commit 计划 (8 个原子提交)

1. `feat(mixin): 暴露 EntityStorage.worker 字段 + 私有 helper invoker`
2. `refactor(snapshot): 新增 EntitySnapshot v0.3 字段清单`
3. `feat(snapshot): 新增 EntityCaptureProcedure 主线程预序列化`
4. `feat(snapshot): 新增 EntityNbtAssembler worker 拼装`
5. `refactor(snapshot): EntitySaveTask 改走 assembler + entity ioBridge`
6. `refactor(dispatch): EntityDispatcher 接入 SnapshotPipeline`
7. `feat(scheduler): SaveScheduler.entity 路径接通异步`
8. `docs(readme): v0.3 实体路径说明`

### 风险

| 风险 | 缓解 |
|---|---|
| Entity AI / Goal / Brain 在主线程 tick 与序列化竞争 | 主线程预序列化时调 `Entity.saveWithoutId` 必须在 entity tick 完成后做; mixin 在 PersistentEntitySectionManager.autoSave HEAD 注入而非 entity tick 路径 |
| 持久化实体 (mob, item, vehicle) 与 LootTable / EntityType 注册时序 | 引用持有 RegistryAccess.Frozen, 与 v0.2 同处理 |
| 大批量实体 (刷怪塔) 主线程预序列化时间过长 | 可配置每 tick 实体上限 (复用 entityChunksPerTickBase) |

## v0.4.0 — chunk unload 路径异步化 (Phase C)

**优先级**: 高 (实战 spike 0.55% 占比, 用户最痛点)
**技术风险**: 高 (chunk 即将释放, worker 持有快照同步)
**期望收益**: 消除 `processUnloads` 同步 save 路径, 大量玩家 teleport 场景下减少 50-100ms 单 tick spike

### 范围

mixin 目标:
- `ChunkMap.processUnloads` 内部 `save(chunk)` 调用拦截
- chunk holder 即将释放前 join worker pending 任务

技术核心: **mustDrain 机制**

ChunkSaveState 已预留 `mustDrain` 字段 (v0.1 留下), v0.4 真正接通:
1. `ChunkMap.processUnloads` 检测到 chunk 准备 unload + isUnsaved=true
2. 调 SnapshotPipeline.captureAndDispatchChunk(chunk, level, state) 入异步队列
3. 同时 state.markMustDrain(), 表示该 chunk 必须落盘后才能释放
4. processUnloads 主线程通过 Phaser 等待该 chunk state 进入 CLEAN
5. 状态机 ioCompletedSuccessfully 时, 若 mustDrain=true 通知 Phaser
6. Phaser 唤醒, processUnloads 继续释放 chunk

关服路径: 所有 mustDrain chunks 必须 join 完, 之后再走 vanilla flush.

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| chunk 内存释放但 worker 还在拼 NBT | mustDrain 机制 + Phaser 阻塞主线程, 直到 worker 完成 IO |
| Phaser 死锁 (worker 异常未通知) | 超时机制: `processUnloads` 等待最多 5s 后 fallback vanilla 同步 save |
| 主线程阻塞反而比同步慢 | 测试目标: Phaser 等待时间 < vanilla 同步 save 时间; 节流 4 chunks/tick * worker p99 5ms = 20ms vs vanilla 同步可达 200ms |
| 服务器崩溃丢数据 | mustDrain 的 chunks 比 vanilla 更晚 unload 内存, 崩溃边界与 vanilla 等价 |
| kill -9 模拟崩服压测 | 实施前必做, 验证损失边界 |

### Commit 计划 (10 个原子提交)

1. `feat(mixin): ChunkMap.processUnloads HEAD 拦截 save 调用`
2. `feat(state): ChunkSaveState mustDrain 真实接通状态机`
3. `feat(sync): MustDrainPhaser 主线程阻塞器`
4. `refactor(dispatch): UnloadDispatcher 走异步路径 + Phaser 等待`
5. `feat(safety): Phaser 超时回退 vanilla 同步`
6. `feat(scheduler): unload 路径独立优先级 (deadline=0 最高)`
7. `feat(metrics): unloadDispatched / unloadFallback / phaserWait 计数`
8. `feat(command): /betterautosave drain-unload 强制等所有 mustDrain 落盘`
9. `test(stress): 大量 unload 压测 + kill -9 数据完整性验证`
10. `docs(readme): v0.4 unload 路径说明 + 风险声明`

## v0.5.0 — chunk load 路径异步化 (实验性)

**优先级**: 中
**技术风险**: 极高 (玩家会看到空白 chunk)
**期望收益**: 消除 `ChunkSerializer.read` 同步 IO, teleport / 跑图场景减少 100-200ms 单 tick spike

### 范围

mixin 目标:
- `ChunkSerializer.read` 主线程同步反序列化路径

技术核心: 两阶段 load
1. 主线程仅做 NBT 解码 (轻量, 因 NBT 已 IO 完成读到内存)
2. PalettedContainer 反序列化 (重头) 移到 worker
3. worker 完成后回调主线程 install 到 ChunkMap

但 vanilla 强依赖 chunk 立即可用 (玩家进入新 chunk 时 server 必须能 setBlock / spawn entity 等). 异步化方案需:
- 临时 placeholder chunk 占位 (vanilla 已有 ProtoChunk 雏形)
- 玩家请求该 chunk 数据时被阻塞或返回空 chunk view
- worker 完成后切换到真实 chunk

### 风险

| 风险 | 缓解 |
|---|---|
| 玩家进入 chunk 时看到空白方块 | 客户端发送暂缓数据包, 直到 worker 完成 |
| Entity 同步 spawn 在尚未加载的 chunk | 阻塞 entity spawn, 直到 chunk ready |
| 与其他 chunk loading mod (Lithium / Starlight / C2ME) 冲突 | 兼容性测试矩阵 |
| 实施复杂度高于 v0.2 / v0.3 / v0.4 | 先做实验性预研, 不承诺时间表 |

v0.5 进入前先做技术调研 PR, 确认可行性后才 implementation.

## v0.6+ — 工具化与诊断

**优先级**: 中低
**目的**: 让用户能自己定位 mod hot path / vanilla 瓶颈, 不依赖外部 spark profiler

### 候选功能

- **Prometheus exporter**: BAS metrics 暴露 HTTP `/metrics` 端点, 接入 Grafana 监控生产服
- **`/betterautosave hottest-chunks`**: 列出最近 N 次 autosave 中 capture / worker p99 最高的 chunks (定位单点慢 chunk, 通常是 BlockEntity 多 / structure 复杂)
- **`/betterautosave mod-tick-trace`**: 用 Forge `ServerTickEvent` 装饰器统计每个 mod 的 ServerTick listener 自身耗时, 一行表格列出 top 10 mod
- **图形化诊断面板**: 替代纯 INFO 日志, embed web UI (类似 spark-viewer 风格)

## 实战观察 (开发者笔记, 不入用户文档)

> 以下根据生产 spark 数据归纳, 在做下一版决策时参考。
> 这些观察可能在不同服 mod 组合下不成立。

- **MTR (Minecraft Transit Railway)** 是 80mod 服里最容易引起 mspt spike 的 mod, 它每 tick `LinkedHashMap.forEach` 遍历全部列车数据。无 BAS 可解决空间, 需 mod 配置或 PR 给上游。
- **Lightman's Currency** 每 tick `GetAllBankAccounts` 全表扫银行账户, 类似反模式。
- **`DistanceManager.runAllUpdates` 与 `ChunkTracker` 光照传播** 在 chunk load/unload 集中时相互放大 (光照网络重算), 是 vanilla 设计层面的限制, BAS 路线 v0.5+ 才能间接缓解。
- **ZGC** 在 30GB heap 配置下表现完美 (STW max 34μs, 0 allocation stall), 内存维度无需进一步优化。
- **多人(2+) 跑图 + 建筑** 场景 max mspt 79ms→206ms, 但 95%ile 仍 4ms, TPS 全程 20。spike 是孤立 outlier 而非常态。

## 优先级总结

| 版本 | 优先 | 风险 | 技术新颖度 | 实战收益 (spark spike-only 占比) |
|---|---|---|---|---|
| v0.3 实体异步 | 中 | 低 | 与 v0.2 同构 | 实体多场景中等 |
| v0.4 unload 异步 | 高 | 高 | 新机制 (Phaser) | 0.55% spike 直接消除 |
| v0.5 load 异步 | 中 | 极高 | 全新 (placeholder chunk) | 0.29% spike 消除 |
| v0.6 工具化 | 中低 | 低 | 工程类 | 间接收益 (定位 mod 问题) |

实施顺序建议: **v0.3 → v0.4 → v0.6 (中间穿插) → v0.5 (实验性)**.
