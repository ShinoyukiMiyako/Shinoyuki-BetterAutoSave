# BetterAutoSave（更好的自动保存）

**简体中文** | [English](README.en.md)

![BetterAutoSave](https://raw.githubusercontent.com/xiaoxiao-cvs/Shinoyuki-BetterAutoSave/main/docs/images/BetterAutoSave.png)

> 让服务器自动存档时不再卡顿 ~  
> 此Mod部分代码由 Claude Opus 4.8 / Claude Fable 5 生成，若有任何问题请提交 issue

## 这个 mod 解决什么问题

原版 Minecraft 服务器每 5 分钟自动存一次档。存档的那一下，主线程要把所有改动过的区块打包、写进硬盘——这期间整个服务器是停住的。空服感觉不到，但 mod 多、人多的服上，这一卡经常有 200 毫秒到几秒，全服玩家一起卡。

除了定时存档，还有几个时刻同样会卡：

- 玩家传送、或大量区块被卸载时（区块离开内存前要存一次）
- 存档时某片区域实体特别多（大型农场 / 刷怪塔），逐个保存实体也会额外卡
- 村庄、袭击、以及部分 mod 的数据（比如 MTR 的列车数据）写盘时，单个文件大了能卡 50-200 毫秒

BAS 把这些存档过程全部搬到后台线程。主线程只做一件必须当场做的事——给要保存的数据拍个快照，剩下的打包和写硬盘都交给后台。卡顿基本消失。


## 适用环境

- Minecraft 1.20.1
- Forge 47.3.22 或更新（47.3 / 47.4 系列都行）
- Java 17 或更新
- 纯服务端 mod，客户端不用装

## 安装

把 `shinoyuki_betterautosave-0.11.0.jar` 丢进服务端的 `mods/` 文件夹，启动即可。第一次启动后，配置文件会生成在：

```
config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml
```

默认配置开箱即用，大多数服不用改。

## 会不会丢存档？

不会。BAS 的设计前提就是绝不能比原版更不安全：

- 关服时会先等所有还没写完的存档落盘，再让服务器退出。
- 关服时的最后一次保存走原版同步流程，和不装 BAS 完全一样。
- BAS 不会"攒着晚点再存"——区块该存的时候立刻进入后台处理，不存在"几分钟没存、崩服就丢"的窗口（有些同类 mod 有这个问题，见下方兼容性）。
- 后台写盘万一失败会自动重试；重试用尽就退回原版同步写法兜底，不会假装成功。

## 工作原理（想了解再看）

原版存档卡，是因为两件重活儿都压在主线程上：把世界数据打包成存档格式、把打包好的数据写进硬盘。

BAS 让主线程只做快照——把区块当前的方块、光照、方块实体等数据拷一份独立副本。这一步很快，因为只是拷贝，不做格式转换。拷完主线程立刻继续跑游戏，后台线程拿着这份副本去打包和写盘。

因为后台线程操作的是副本，和主线程互不干扰，主线程拍完快照就能放手，不用等写盘完成。区块、实体、世界数据三类存档都走这套流程。

服务器卡的时候 BAS 会自动减速（TPS 掉到一定程度就少拍点快照），但快到下一个存档周期时强制全速，保证不会积压。

## 配置

配置文件在 `config/Shinoyuki-Optimize/shinoyuki_betterautosave/common.toml`，常用项：

| 字段 | 默认 | 说明 |
|---|---|---|
| general.enabled | true | 总开关，关掉等于没装（退回原版行为） |
| throttle.chunksPerTickBase | 4 | 主线程每游戏刻最多给几个区块拍快照 |
| throttle.adaptiveEnabled | true | 服务器卡时自动减速，一般别关 |
| workers.chunkWorkerThreads | 2 | 区块后台线程数 |
| workers.entityWorkerThreads | 2 | 实体后台线程数 |
| workers.savedDataWorkerThreads | 1 | 世界数据后台线程数，装了大型数据 mod（如 MTR）可调到 2 |
| compat.eventCompatMode | PARTIAL | 兼容档位，见下方说明 |

其余项（重试次数、关服超时、监控开关等）配置文件里都有注释，按需调。

### 兼容档位 eventCompatMode

少数 mod 会监听"区块保存"事件。这个开关控制 BAS 给它们的数据完整度：

- **PARTIAL（默认，推荐）**：性能最好，99% 的 mod 感知不到差别。
- **FULL**：和原版 100% 一致，但性能减半。只有当某个 mod 明确因为读不到区块方块数据而报错时才需要切。
- **DISABLED**：完全不派发该事件，最省，前提是你确定没有 mod 监听它。

不确定就保持 PARTIAL。

## 服内命令（需要 OP 权限）

| 命令 | 作用 |
|---|---|
| `/betterautosave status` | 一行显示当前状态 |
| `/betterautosave metrics` | 一行指标摘要 |
| `/betterautosave debug` | 完整诊断：队列深度、各阶段耗时、计数器 |
| `/betterautosave flush` | 立刻把所有没写完的存档同步落盘 |
| `/betterautosave drain-unload` | 关服前手动等所有待落盘的区块写完 |
| `/betterautosave hottest-chunks [数量]` | 列出存档最慢的区块（默认 10，最多 50），定位卡点 |
| `/betterautosave force-async` | 强制当前维度所有区块走一次后台存档（诊断用） |

## 监控（v0.9，可选）

v0.9 加了两样诊断工具，让你不用外挂 profiler 就能看清存档性能。

**hottest-chunks 命令** 按存档耗时从高到低列出最慢的区块：

```
/betterautosave hottest-chunks 20
```

高耗时区块通常出现在方块实体密集的地方——大型自动化农场、mod 商店面板、复杂红石装置。找到具体位置就能针对性优化。

**Prometheus 指标接口**（默认关闭）开启后会在指定端口提供一个 `/metrics` 页面，接 Grafana 可以画存档性能的长期趋势图，比逐次敲 `/betterautosave debug` 看快照方便得多。在配置里启用：

```toml
[prometheus]
enabled = true
port = 9450
```

安全提示：端口默认监听 `0.0.0.0`。公网服（云服务器 / VPS）请用防火墙限制 9450 端口，或把 `bindAddress` 改成 `127.0.0.1` 只允许本机连接。指标数据不含玩家隐私，但会暴露服务器的活动规律。

## 和哪些 mod 冲突

- **Smooth Chunk Save**：二选一。两者都改了区块卸载时的存档路径。相比之下 BAS 不延迟落盘（没有数据丢失窗口）、不取消原版定时存档、不吞异常。
- **C2ME-Forge**：和 BAS 接管同一条存档路径，同时装会重复处理，二选一。它目前已停止更新，实际同时装的情况应该很少。
- **Lithium 移植版（Radium / Canary）、Starlight Forge**：兼容。
- 其他同样改了区块存档的 mod：可能让 BAS 的接管偶尔失败，这种情况 BAS 会自动退回原版处理，不影响数据安全，只是少了点性能收益。

完整兼容性矩阵见 [ROADMAP](docs/ROADMAP.md#bas-兼容性矩阵-代码核对)。

## 出问题怎么快速恢复

三档都不会破坏世界数据：

1. **临时关掉**：把配置里 `general.enabled` 改成 `false`，重启或 `/reload`。mod 还在，但所有逻辑跳过，等于原版。
2. **彻底卸载**：把 jar 从 `mods/` 移走重启。世界数据由原版存档继续保护，卸载不丢数据。
3. **只调参数**：怀疑是性能档位问题，先调 `chunksPerTickBase`（范围 1-64）或把 `eventCompatMode` 切成 `FULL`，不必整个卸载。

## 构建 / 开发

```bash
./gradlew build         # 编译 + 跑测试
./gradlew runServer     # 启动开发服务器
```

技术原理、生态调研、完整兼容性矩阵、版本路线图见 [ROADMAP.md](docs/ROADMAP.md)。

## 许可

见 LICENSE 文件。
