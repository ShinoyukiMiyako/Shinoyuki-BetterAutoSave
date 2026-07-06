# 安全策略 / Security Policy

## 支持的版本 / Supported Versions

BetterAutoSave 处于 1.0 前的活跃迭代阶段，仅对**最新发布版本**提供安全修复。请先升级到最新版再报告问题。

BetterAutoSave is in active pre-1.0 iteration. Security fixes are provided only for the **latest released version**. Please update to the latest release before reporting.

| 版本 / Version | 支持 / Supported |
| --- | --- |
| 最新发布版 / Latest release | 是 / Yes |
| 更早版本 / Older | 否 / No |

## 报告漏洞 / Reporting a Vulnerability

请**不要**在公开 issue 中披露安全漏洞。请通过 GitHub 私密漏洞报告提交：
仓库 `Security` 标签页 → `Report a vulnerability`
（https://github.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave/security/advisories/new）。

Please do **not** disclose security issues in public issues. Report privately via GitHub:
repository `Security` tab → `Report a vulnerability`
(https://github.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave/security/advisories/new).

报告请尽量包含：受影响版本与加载器（Forge 1.20.1 / NeoForge 1.21.1）、复现步骤、相关配置、日志或崩溃报告。
Please include where possible: affected version and loader (Forge 1.20.1 / NeoForge 1.21.1), reproduction steps, relevant config, and logs or crash reports.

## 范围 / Scope

本 mod 是服务端存档/加载优化，相关安全考量：

This mod is a server-side save/load optimizer. Relevant considerations:

- 存档路径的数据丢失或损坏（异步存盘/加载、restore 事务）。
  Data loss or corruption in the save path (async save/load, restore transactions).
- 可由配置或畸形世界数据触发的服务器崩溃 / 拒绝服务。
  Server crash / denial of service triggerable by config or malformed world data.
- Prometheus 指标 HTTP 导出器（`prometheus.enabled`，默认关闭）：启用后在 `bindAddress:port`（默认 `0.0.0.0:9450`）
  开放一个 HTTP 监听，导出存盘计数 / 队列深度 / 延迟直方图。这些不是敏感数据但会暴露世界活动模式；
  公网服务器请用防火墙限制访问，或把 `bindAddress` 设为 `127.0.0.1`。
  Prometheus metrics HTTP exporter (`prometheus.enabled`, default off): when enabled it opens an HTTP listener at
  `bindAddress:port` (default `0.0.0.0:9450`) exposing save counters / queue depth / latency histograms. These are not
  sensitive but reveal world activity patterns; on a public server restrict access via firewall or set `bindAddress`
  to `127.0.0.1`.

不在本 mod 范围内的问题（原版 Minecraft、Forge/NeoForge 加载器、其它 mod）请向对应上游报告。
Issues outside this mod (vanilla Minecraft, the Forge/NeoForge loader, other mods) should be reported to their respective upstreams.
