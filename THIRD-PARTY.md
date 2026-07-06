# 第三方组件与署名 / Third-Party Notices

BetterAutoSave 本体以 AGPL-3.0-or-later 授权（见 [LICENSE](LICENSE)）。发布的 mod jar 中内嵌打包的第三方组件如下。
BetterAutoSave itself is licensed under AGPL-3.0-or-later (see [LICENSE](LICENSE)). Third-party components bundled inside
the released mod jars are listed below.

本清单只列**实际打包进发布 jar** 的组件。Minecraft、Forge/NeoForge 加载器、SpongePowered Mixin、SLF4J 等由
Minecraft 或加载器运行时提供，不由本 mod 打包，故不在此列。
This list covers only components **actually bundled into the released jars**. Minecraft, the Forge/NeoForge loader,
SpongePowered Mixin, SLF4J, etc. are provided by Minecraft or the loader at runtime, are not bundled by this mod, and are
not listed here.

---

## MixinExtras

- 打包位置 / Bundled in: Forge fat jar (`shinoyuki_betterautosave-<version>-all.jar`)，经 jarJar 内嵌
  `io.github.llamalad7:mixinextras-forge`。
  the Forge fat jar (`shinoyuki_betterautosave-<version>-all.jar`), embedded via jarJar as
  `io.github.llamalad7:mixinextras-forge`.
- 不在 NeoForge jar 中 / Not in the NeoForge jar: NeoForge 加载器自带 MixinExtras，无须打包。
  The NeoForge loader ships MixinExtras, so it is not bundled.
- 许可证 / License: MIT
- 项目主页 / Project: https://github.com/LlamaLad7/MixinExtras
- Copyright (c) LlamaLad7

```
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
