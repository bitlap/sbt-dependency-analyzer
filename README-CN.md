# IntelliJ IDEA 版 Sbt 依赖分析器

<img src="./logo.svg" width="250" height="150" alt="Sbt Dependency Analyzer Logo" align="right" />

[![Build Status][badge:build]][gh:workflows-build]
[![License][badge:license]][gh:license]
[![GitHub releases][badge:release]][gh:releases]
[![Version][badge:version]][plugin-versions]
[![Downloads][badge:downloads]][plugin-homepage]

<p align="center"><b>基于Intellij的IDE/Android Studio的Sbt 依赖分析器插件</b></p>


[English](README.md) | 中文

**⭐ 觉得这个插件有用？请在 [GitHub](https://github.com/bitlap/sbt-dependency-analyzer) 上给它一个 Star 以示支持！**

## 🚀 概述

Sbt 依赖分析器插件提供了强大的可视化工具，帮助您轻松理解、管理和排查 Sbt 项目的依赖关系。直接在 IDE 中清晰洞察您的库依赖及其关系。

## ✨ 主要特性

*   **依赖树可视化**：以层次结构树的形式查看项目的所有依赖。
*   **冲突识别**：快速发现并解决库之间的版本冲突。
*   **依赖搜索**：轻松在项目中查找特定依赖。
*   **模块间依赖分析**：可视化项目中不同模块之间的依赖关系。
*   **JAR 大小指示器**：查看依赖 JAR 的大小，以便更好地管理项目的资源占用。
*   **跳转到声明**：点击 *用户定义的* 依赖项可直接跳转到其在 `build.sbt` 中的声明位置。
*   **依赖排除（实验性功能）**：
    *   选择一个 *传递* 依赖项以从某个 *用户定义* 的依赖中排除它。
    *   选择一个 *用户定义* 的依赖项以移除该依赖本身。
    *   *自插件版本 `0.5.0-242.21829.142` 起可用。*

## 🛠️ 安装与设置

1.  **安装插件**：前往 `设置/偏好设置` > `插件` > `市场`，搜索 "Sbt Dependency Analyzer" 并安装。
2.  **自动设置**：首次分析时，如果需要，插件会自动生成一个 `project/sdap.sbt` 文件。此文件添加了所需的 `addDependencyTreePlugin` 语句。**创建后请勿修改或删除此文件**。
3.  **插件依赖**：本插件利用了 `sbt-dependency-tree` 的功能，该功能已捆绑在最近的 sbt 版本中（但默认未启用，[sbt 问题](https://github.com/sbt/sbt/pull/5880)）。

## 📖 使用方法

> **默认键盘快捷键**：`Ctrl` + `Shift` + `L` (Windows/Linux) / `Command` + `Shift` + `L` (macOS)

只需在 IntelliJ IDEA 中打开您的 Sbt 项目并使用快捷键即可生成和查看依赖分析。

![Dependency Analysis View](https://plugins.jetbrains.com/files/22427/screenshot_064531dc-a3fa-4a8e-9437-7e76defa1f48)

*交互式依赖关系图清晰地展示了项目的结构。*

## ⚙️ 配置 <a id="settings"></a>

通过 `设置/偏好设置` > `工具` > `Sbt Dependency Analyzer` 微调插件行为并可能加速分析：

*   **文件缓存超时**：调整插件在重新运行 `dependencyDot` 命令之前，使用缓存的依赖图文件 (`.dot`) 的时长（秒）。(默认：`3600`)。
*   **组织（Organization）**：在此处预定义您项目的组织（organization）值，以避免插件需要向 sbt 查询。
*   **禁用作用域（Scope）**：通过禁用您不感兴趣的依赖作用域（例如 `Test`, `Provided`）来提高分析速度。

这些设置按 IntelliJ 项目存储在 `.idea/bitlap.sbt.dependency.analyzer.xml` 中。删除此文件将重置设置并清除缓存。

## ❗ 故障排除

### 问题："Caused by: java.io.IOException: Could not create lock for ..."
此错误可能由于插件使用 sbt shell 与 IntelliJ IDEA 内部的项目重新加载/构建机制之间的冲突而引起。
*   **解决方案**：使用 **IntelliJ IDEA 内的 sbt shell** 来重新加载 (`sbt reload`) 或构建 (`sbt compile`) 您的项目，而不是使用 IDE 的内置按钮。


### 问题：无法分析模块间的依赖关系
如果插件无法确定项目组织（organization），它可能无法正确解析模块间的依赖关系。
*   **解决方案**：通过以下任一方式确保插件知道您的项目组织：
    1.  在插件的[设置](#settings)中设置 **组织（Organization）** 值。
    2.  在您的 `build.sbt` 中使用 `ThisBuild / organization` 或 `inThisBuild(...)` 全局定义 `organization` 设置。
> **注意**：未在根项目的 `dependsOn` 子句中声明的子模块将不会被解析。

## 🔍 技术细节

1. 插件执行多个 sbt 命令 (`organization`, `moduleName`, `dependencyDot`, `reload`, `update`) 来收集依赖信息。已实施重大优化以尽量减少这些命令的数量和影响。
2. 插件复制了 [intellij-community](https://github.com/JetBrains/intellij-community) 项目中的 Kotlin UI 代码，并使用 [kotlin-plugin](https://github.com/bitlap/kotlin-plugin) 进行编译。

## 🤝 贡献

欢迎贡献！请随时在 [GitHub](https://github.com/bitlap/sbt-dependency-analyzer) 上提交问题、功能请求或拉取请求。

## 🙏 致谢

**JetBrains 支持**：此项目使用 JetBrains IntelliJ IDEA 开发。我们感谢 JetBrains 提供免费许可证，极大地支持了其开发。

<a href="www.jetbrains.com">
<img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg?_gl=1*8f2ovk*_ga*NTY2NTA4Mzg1LjE2NzU3MzgzMTI.*_ga_9J976DJZ68*MTcwMzIwOTE4NS4xODUuMS4xNzAzMjA5NDYzLjI4LjAuMA..&_ga=2.177269094.2105719560.1703209186-566508385.1675738312" alt="IntelliJ IDEA logo.">
</a>

<br />


[badge:build]: https://github.com/bitlap/sbt-dependency-analyzer/actions/workflows/ScalaCI.yml/badge.svg
[plugin-logo]: https://github.com/bitlap/sbt-dependency-analyzer/blob/master/logo.svg
[badge:license]: https://img.shields.io/github/license/bitlap/sbt-dependency-analyzer.svg?style=flat-square
[badge:release]: https://img.shields.io/github/release/bitlap/sbt-dependency-analyzer.svg?sort=semver&style=flat-square&colorB=0097A7
[badge:version]: https://img.shields.io/jetbrains/plugin/v/22427.svg?style=flat-square&colorB=2196F3
[badge:downloads]: https://img.shields.io/jetbrains/plugin/d/22427.svg?style=flat-square&colorB=5C6BC0

[gh:sbt-dependency-analyzer]: https://github.com/bitlap/sbt-dependency-analyzer
[gh:releases]: https://github.com/bitlap/sbt-dependency-analyzer/releases
[gh:workflows-build]: https://github.com/bitlap/sbt-dependency-analyzer/actions/workflows/ScalaCI.yml
[gh:license]: https://github.com/bitlap/sbt-dependency-analyzer/blob/master/LICENSE
[plugin-homepage]: https://plugins.jetbrains.com/plugin/22427-sbt-dependency-analyzer
[plugin-versions]: https://plugins.jetbrains.com/plugin/22427-sbt-dependency-analyzer/versions
