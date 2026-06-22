## NeoForge 1.21.1 构建 — 双加载器同源

BetterAutoSave 现以同一份 0.11.0 代码提供 NeoForge 1.21.1 构建。功能、配置、命令、兼容档位与 Forge 1.20.1 版完全一致：异步存档的算法核心（调度、接力状态机、worker、诊断）是零 Minecraft 依赖的纯 Java，两个加载器 source-merge 共享同一份源码，皇冠存档接力协议只此一份，不随加载器分叉

### 适用

- NeoForge 21.1 系列，Java 21 或更新
- 纯服务端 mod，客户端不用装
- jar：`shinoyuki_betterautosave-neoforge-0.11.0.jar`

### 移植要点

- 序列化与磁盘 IO 胶水按 1.21.1 原版反编译源逐点核实：区块状态读取改名、方块实体与 SavedData 的注册表参数、NBT 压缩写入改 Path 重载、DataResult 取值形态、区块持有者取链改名
- 7 个 mixin 注入点在 1.21.1 全部存活；实体存储的区域 IO 在 1.21.1 下沉进 SimpleRegionStorage，经两跳 accessor（EntityStorage -> SimpleRegionStorage -> 内层 worker）适配后异步实体管线原样复用
- 区块异步存档补齐 NeoForge 的数据附件（data attachment）与 LevelChunk 自定义光照字段，与原版落盘格式对齐——用到数据附件的 mod 不会在异步存档时丢数据

### 验证

- 真实 NeoForge 1.21.1 服务端：mixin 全部装载，周期与运营 `/save-all` 存档无主线程卡顿，区块与实体异步落盘，关服 worker 干净排空，重启加载零读档损坏
- 单元测试 114 项全绿（含需 Minecraft 运行期引导的用例）

### 安装

把 `shinoyuki_betterautosave-neoforge-0.11.0.jar` 放进服务端 `mods/`，配置文件路径、命令、兼容档位与 Forge 版一致
