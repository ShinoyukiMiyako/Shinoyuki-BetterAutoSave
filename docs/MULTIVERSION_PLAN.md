# BetterAutoSave 多版本计划: Forge 1.20.1 + NeoForge 1.21.1

| 字段 | 值 |
|---|---|
| 状态 | 已完成 (2026-06-13): C1-C6 全部落地, bas139 真机验证 + 114 测试全绿, 全模块 build 绿 |
| 目标 | 双线维护: Forge 1.20.1 (LTS 存量) + NeoForge 1.21.1 (整合包新锚点) |
| 难度定级 | 实测低到中 -- 1.21.1 在 vanilla 序列化大重构 (SerializableChunkData, 1.21.2+) 之前, 破坏点少且集中 |
| 实际工期 | 约 1 工作日 (单会话, 反编译 1.21.1 真源驱动, 非签名推断) |

## 执行结果 (2026-06-13, 权威 -- 下方原始计划与侦察结论部分断言已被实现修正, 以本节为准)

NeoForge 1.21.1 端口已完整落地并三重验证 (编译/打包 + 114 测试 + bas139 真机)。分支 `feat/multiversion-phase0`, 阶段 C1-C6。

**实际仓库布局** (与下方 §二 设想不同):
- 模块名 `common/` + `forge/` + `neoforge/` (非 `core/forge-1.20.1/neoforge-1.21.1`)。
- 打包用 **source-merge** (jaredlll08/MultiLoader-Template 模式): forge/neoforge 各 `compileJava.source(common.allJava)` 把零-MC common 源重编进自身 reobf/jar; **未**用 AdvancedBackups 的 jar-by-path 二进制 fat-jar (那会 dev-vs-jar 分裂)。
- `common/` = 纯 java-library 零-MC: core/scheduler+state+worker+diagnostic + CapturedSnapshot。其余 (snapshot/io/dispatch/mixin/accessor/config/command/api/util/Mod/Core) 因直引 MC 必须每 loader 一份。
- 构建: 单 wrapper Gradle 8.8 同养 FG6 (forge, Java 17) + ModDevGradle 2.0.141 (neoforge, Java 21), 逐模块 toolchain 锁版本; NeoForge 21.1.233 / Parchment 2024.11.17。FG6 证书瞬态失败时加 `-Dnet.minecraftforge.gradle.check.certs=false`。

**对侦察结论的修正**:
- mixin 数 = **7** (非 §1.3 的 6): ChunkAccess / ChunkMap / ChunkMapSave / DimensionDataStorage / EntityStorage / MinecraftServer / SavedData。accessor = 6 (forge 5 + neoforge 新增 SimpleRegionStorageAccessor)。
- 可进 common 的代码远少于 §1.3 "约大半个代码库": 仅 scheduler/state/worker/diagnostic (snapshot/io/dispatch 与 7 mixin 双向纠缠, 永留 loader)。
- 测试 = **26 类 / 114 用例** (非 §1.3 "9 个测试中 8 个纯 Java"): common 68 (纯 Java, 已迁 common) + forge 114 + neoforge 114。neoforge 测试用 MDG unitTest fixture (testframework + `unitTest{enable;testedMod}`) 跑含 12 个需 MC bootstrap 的测试类 (各在 @BeforeAll 调 Bootstrap.bootStrap)。
- **TickEvent 无关** (修正 §1.2): 本 mod 不用 TickEvent, tick 由 MinecraftServerMixin `tickServer` @At TAIL 驱动。
- ServerThreadAssert 实拆为 WorkerThreadAssert (common) + ServerThreadAssert(MinecraftServer) (loader), 非 §1.3 的 "抽象为 Supplier<Boolean>"。

**反编译 1.21.1 真源核实 / 真机揪出的破坏点** (签名推断会漏):
- ChunkStatus 移包 `chunk.status.`; `getStatus`->`getPersistedStatus`; `getBlockEntityNbtForSaving` 加 HolderLookup.Provider; `DataResult.getOrThrow(boolean,Consumer)` 删除 -> `getOrThrow(Function)`; `SavedData.save` 加 HolderLookup.Provider; `NbtIo.writeCompressed(File)` 删除 -> Path 重载; ResourceLocation 构造器私有 -> `fromNamespaceAndPath`; `ChunkHolder.getLastAvailable` -> `GenerationChunkHolder.getLatestChunk`; `MinecraftServer.getAverageTickTime` 删除 -> `getCurrentSmoothedTickTime`。
- codec 经真源核实仍用裸 `NbtOps.INSTANCE` (非 RegistryOps); ChunkSerializer 私有成员 (BLOCK_STATE_CODEC / makeBiomeCodec / packStructureData / saveTicks) 1.21.1 存活, invoker 直用。
- **EntityStorage 重写** (意外震中): 1.21.1 EntityStorage 不再直接持 worker, region IO 下沉 SimpleRegionStorage; 经两跳 accessor 取内层 IOWorker, 皇冠 entity 接力管线零改动复用。
- **NeoForge 新落盘字段**: ChunkCaptureProcedure 手拼 core tag 补 attachments (AttachmentHolder) + LevelChunk auxlight, 否则用 NeoForge 数据附件的 mod 异步存档丢数据。

config watcher #1768: 本会话真机验证范围内 (config 加载/注入/热重载) 未观测到刷屏或写循环影响; 留作长期关注。1.21.2+ 的 SerializableChunkData 重构仍明确延后 (见 §四), 不影响 1.21.1。

## 一. 侦察结论 (三路取证, 2026-06-11 -- 部分断言已被实现修正, 准确状态见上「执行结果」)

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
