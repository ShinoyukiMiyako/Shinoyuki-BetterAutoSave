# BetterAutoSave 多版本计划: Forge 1.20.1 + NeoForge 1.21.1

| 字段 | 值 |
|---|---|
| 状态 | 已批准 (2026-06-11), 基于三路侦察取证 |
| 目标 | 双线维护: Forge 1.20.1 (LTS 存量) + NeoForge 1.21.1 (整合包新锚点) |
| 难度定级 | 低到中 -- 1.21.1 在 vanilla 序列化大重构 (SerializableChunkData, 1.21.2+) 之前 |
| 估期 | 3-4 周 (侦察已完成) |

## 一. 侦察结论 (三路取证, 2026-06-11)

### 1.1 版本界碑 (javadoc 一手对证)

- **SerializableChunkData = 1.21.2+, 不在 1.21.1**: NeoForge 21.0/21.1 javadoc 中
  ChunkSerializer.write -> CompoundTag 仍是经典形态。BAS "拆 ChunkSerializer 私有
  helper 在 worker 端重拼"的核心打法在 1.21.1 上保留, 未来上 1.21.2+ 才需要按
  capture/serialize 拆分重设计 (届时 vanilla 的拆分反而与 BAS 架构同构, 可能更顺)
- 1.20.5 (24w04a) 引入 RegionStorageInfo: IOWorker 构造与 ChunkSerializer.read 签名
  加参, write/store 注入点存活
- SavedData 1.20.5 改版: SavedData.Factory 与 save() 带 HolderLookup.Provider;
  DimensionDataStorage.save() 变无参。1.21.5+ 的 SavedDataType/scheduleSave 不影响本次
- Region 格式: 1.20.5 加 LZ4 (type 4) 与 type 127; .mcc 外置在 24w05a 泛化。
  写 API 稳定, 需容忍新压缩字节
- autosave 触发 (tickServer % autosaveInterval) 与 NBT API 在区间内稳定

### 1.2 工具链 (NeoForge 1.21.1 现状)

- 构建: ModDevGradle (取代 ForgeGradle); **mixin 不再需要 refmap** (Neo 1.20.4 起
  Fabric Mixin 分支 + 运行时官方 mojmap), MixinGradle/reobf 整条管线删除; Java 21
- neoforge.mods.toml: type=required 取代 mandatory; @Mod 构造器注入 IEventBus
- 事件: 包名迁移 net.neoforged.*; TickEvent 重构为 Pre/Post 拆分类
- 配置: ForgeConfigSpec -> ModConfigSpec (近改名级)。已知坑: 配置文件监视器
  issue #1768 (日志刷屏/写循环) -- BAS 重度依赖热重载, 移植后必须专项验证
- jarJar 元数据格式不变; mixinextras Neo 内置

### 1.3 BAS 暴露面 (代码审计)

- 6 mixin + 6 accessor: 注入点在 1.21.1 全部存活, 工作量为逐个重对签名
  (RegionStorageInfo 加参 / 字段名核对), 非重设计
- 移植震中: ChunkSerializerInvoker 暴露的私有成员 (BLOCK_STATE_CODEC /
  makeBiomeCodec / packStructureData / saveTicks) 需在 1.21.1 mojmap 下逐个重验
  存在性与签名 -- 侦察确认类还在, 成员级差异留待实现期核对
- 可进 common 的版本无关代码 (约大半个代码库): core/scheduler, core/state,
  core/worker, diagnostic 全家, BetterAutoSaveCore, util (ServerThreadAssert 抽象为
  Supplier<Boolean> 后完全去耦); 9 个测试中 8 个纯 Java 可原样带走
- 必须留 loader: mixin/accessor 全部, core/snapshot (序列化三件套),
  core/io/AsyncIoBridge, core/dispatch, config, command, mod 主类

## 二. 仓库结构决策

**core + 每版本 loader 薄壳** (AdvancedBackups 同款先例):

```
betterautosave/
├── core/                  纯 Java, 无 MC/loader 依赖, 8 个测试随行
├── forge-1.20.1/          现有代码收敛于此 (mixin/snapshot/io/config/command/主类)
└── neoforge-1.21.1/       新壳: 自己的 mixin 集 + 适配层
```

- mixin 不跨版本共享 (注入点天然版本绑定)
- api/ 包决策: **源码级共享, 每 loader 各编译一份** -- api 暴露的
  ChunkPos/ResourceKey/CompoundTag 在 1.20.1 与 1.21.1 间源码兼容, 不值得为中立
  类型抽象付出代价。下游 (BetterBackup) 本来就按版本配对依赖
- 不引入 Architectury (重 mixin 场景阻抗大); Stonecutter 暂不需要 (仅两版本),
  版本数 >=3 时再评估

## 三. 阶段计划

### Phase 0: 仓库拆分 (1 周, 高风险重构, 先在现有版本上验证)

1. chore(structure): Gradle 多模块骨架 (core / forge-1.20.1)
2. refactor(core): 版本无关包迁入 core, ServerThreadAssert 抽象去 MC 化
3. refactor(forge): 其余收敛 forge-1.20.1, 全部测试迁移归位
4. 验收门槛: 1.20.1 构建产物行为与拆分前一致, 166+ 测试全绿, 生产服换 jar 冒烟

### Phase 1: NeoForge 壳启动 (0.5 周)

5. feat(neoforge): ModDevGradle 工程 + neoforge.mods.toml + 空 mod 主类可启动
6. feat(neoforge): config (ModConfigSpec) + 事件/生命周期适配 (TickEvent Pre/Post,
   构造器 IEventBus); 专项验证配置热重载 (#1768)

### Phase 2: mixin 移植 (1 周)

7. feat(neoforge): 6 mixin + 6 accessor 逐个重对 (RegionStorageInfo 加参,
   mojmap 名核对, compatibilityLevel JAVA_21)
8. test(neoforge): dev server 启动 + 异步管线自验证日志逐项核对

### Phase 3: 序列化三件套 (1 周, 震中)

9. feat(neoforge): ChunkSerializerInvoker 成员级重验 + ChunkCaptureProcedure /
   ChunkNbtAssembler 适配 (HolderLookup.Provider 入 SavedData 路径)
10. feat(neoforge): entity 路径 + LZ4/type-127 容忍
11. test(neoforge): 存档 round-trip 对拍 (BAS 写出的 chunk 与 vanilla 写出的
    语义等价, vanilla 能读回)

### Phase 4: 收尾 (0.5 周)

12. feat(api): SaveListenerRegistry 等 api/ 在 neoforge 侧编译发布
13. chore(release): 双版本 CI (release.yml 矩阵化), 版本号方案
    (0.11.0-forge-1.20.1 / 0.11.0-neoforge-1.21.1 同源同发)
14. docs: README 双版本说明 + release notes

## 四. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| ChunkSerializer 私有成员在 1.21.1 有未侦察到的成员级变化 | 中 | Phase 3 首日先做成员级 diff, 发现重设计需求立即重估 |
| Neo 配置监视器 #1768 影响热重载 | 中 | Phase 1 专项验证; 不行则文档声明改配置需重启 |
| Phase 0 拆分引入行为回归 | 中 | 拆分后生产服冒烟 + 全测试; 拆分期间冻结其他 BAS 改动 |
| 1.21.2+ 的 SerializableChunkData 重构 | 已知延后 | 明确不在本计划; 上 1.21.2+ 时单独立项 (vanilla 拆分与 BAS 架构同构, 或可简化) |
| BetterBackup 跟进成本 | 低 | 零 mixin + 纯 Java 内核, loader 面仅 3 文件; BAS neo 版稳定后顺移 |

## 五. 与 BetterBackup 的协同

- BAS Phase 0 的 core 拆分顺带固化 api/ 为跨版本契约 (源码共享)
- BetterBackup 的 NeoForge 移植在 BAS neo 版出 alpha 后启动, 预估 <1 周
  (raw-bytes 架构对 chunk 格式透明; 注意 LZ4 chunk 的撕裂读校验分支 --
  V0_2_PLAN 已登记该地雷)
