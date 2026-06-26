# scripts/spark — Spark 性能报告解析

给 BAS 开发 / `@claude` bot 用的 spark profile 解析器。纯 Python 手写 protobuf 解析，无第三方依赖（stdlib 即可，CI 的 `python3` 直接跑）。

## 脚本

- `spark_report.py <file>` — 全量解析：metadata / GC / heap / vm_args / mod 归因 / 子系统 rollup / MSPT / 窗口统计。
- `spark_tree.py <file> ["Server thread"]` — inclusive 调用树：最热调用链（贪心跟最大子节点）+ save/chunk 相关帧的 inclusive 占比定位。
- `spark_parse.py <file>` — 精简版：只出线程热点 self-time 表。
- `spark.proto` / `spark_sampler.proto` — spark 官方 protobuf schema（参考用，脚本不依赖）。

## 数据从哪来

spark 服务端上传给的链接形如 `https://spark.lucko.me/<id>`；真数据在 bytebin（同 id，未压缩 protobuf，保留约 60 天）：

```bash
curl -o report.sparksampler https://bytebin.lucko.me/<id>
python3 scripts/spark/spark_report.py report.sparksampler
python3 scripts/spark/spark_tree.py report.sparksampler "Server thread"
```

## 关键坑（务必看 inclusive 树）

`spark_report.py` 的 **self-time 叶子归因**会把 BAS 注入逻辑的成本记到 `fastutil collections` / `JDK collections`（downstream）头上，使 BAS 在 self-time rollup 里显示成 0.0%。**判断 BAS 是否真是大头，必须用 `spark_tree.py` 看 inclusive 调用树**，把成本归回 `handler$...betterautosave$...` 注入帧。

这正是 issue #12 的发现过程：表面"saveAllChunks 卡顿"，self-time 里 BAS=0.0%，但 inclusive 树显示真凶是 `DimensionDataStorage.save -> BAS interceptSave -> CompoundTag.copy` 主线程深拷贝占 33%。
