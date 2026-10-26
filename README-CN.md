# Sbt Dependency Analyzer

<img src="./logo.svg" width="200" alt="Sbt Dependency Analyzer 标志" align="right" />

[![Build Status][badge:build]][gh:workflows-build]
[![License][badge:license]][gh:license]
[![Downloads][badge:downloads]][plugin-homepage]

在 IntelliJ IDEA 中查看 sbt 依赖树、定位版本冲突，并跳转或修改依赖声明。

[English](README.md) | **中文** · [插件市场][plugin-homepage] · [反馈问题](https://github.com/bitlap/sbt-dependency-analyzer/issues)

## 功能

- **依赖与引用路径**：查看直接依赖、传递依赖和引用关系，按模块及作用域（Scope）筛选。
- **搜索与冲突定位**：搜索依赖，查看版本冲突及其引入路径。
- **模块依赖分析**：区分 sbt 项目 ID、发布名称与 IDEA 模块显示名，识别多模块及 JVM、Scala.js、Scala Native 项目的依赖。
- **声明导航与修改**：定位依赖声明，预览删除直接依赖或从父依赖中排除传递依赖。
- **JAR 大小**：按需查看依赖大小，合计时对相同坐标去重。

![依赖树、版本冲突与引用路径示例](docs/dependencyTreeConflicts.jpg)

## 快速开始

1. 在 `设置/偏好设置 → 插件 → Marketplace` 中安装 **Scala** 和 **Sbt Dependency Analyzer**。
2. 在 IDEA 中导入 sbt 项目，等待项目导入和索引完成。
3. 使用 **Ctrl + Shift + L**（Windows、Linux、macOS），或点击 sbt 工具窗口按钮、模块右键菜单中的 **分析 Sbt 依赖项**。
4. 在分析视图中选择模块与 Scope，搜索依赖或查看引用路径。

首次分析会按需生成 `project/sdap.sbt`，用于启用依赖图任务。请保留该自动生成文件，并按 IDE 提示重新加载 sbt。

## 常用操作

在依赖列表或树中选中依赖后，使用右键菜单：

| 操作 | 用途 |
| --- | --- |
| 查看依赖项（Go to） | 跳转到源声明；传递依赖跳转到引入它的直接声明，多个匹配时选择目标。 |
| 删除直接依赖 | 预览删除当前直接依赖的使用位置。 |
| 从 … 排除（Exclude） | 预览从选定的直接依赖中排除传递依赖。 |
| 刷新依赖项 | 使图缓存失效并重新分析。 |
| 刷新快照依赖项 | 在后台更新当前模块的 SNAPSHOT 依赖。 |

删除和排除属于实验性功能。应用前可查看修改前后对照，也可取消；应用操作作为一次可撤销命令执行。修改后重新加载 sbt，再刷新分析结果。

导航会解析常量、依赖别名和常见依赖表达式。共享 `ModuleID` 优先修改使用位置；共享有序依赖列表在当前模块的引用处修改，不能安全定位的计算型声明不自动修改。版本精确匹配优先，淘汰或动态版本会按坐标及 Scope 查找候选。

分析在后台执行，失败通知提供重试入口。取消会停止等待，已提交的 sbt 命令仍可能继续执行。模块及 Scope 选择支持 Enter，Scope 勾选还支持 Space。

## 设置 <a id="settings"></a>

打开 `设置/偏好设置 → 工具 → Sbt Dependency Analyzer`：

| 设置 | 说明 |
| --- | --- |
| 文件缓存超时 | 单位为秒，默认 `3600`；`0` 表示禁用依赖图缓存。 |
| Organization 覆盖值 | 留空时读取当前 sbt 模块设置；填写时使用指定组织名。 |
| 禁用的分析作用域 | 跳过不需要的 Compile、Test 或 Provided 分析。 |

设置按 IDEA 项目保存。“刷新依赖项”会立即使图缓存失效，关闭项目会释放内存缓存。

## 常见问题

### 修改后仍显示旧依赖

确认修改已保存并重新加载 sbt，然后执行“刷新依赖项”。仅刷新分析视图不会让 sbt 重新读取构建文件。

### 找不到源声明或修改菜单不可用

等待索引完成，并确认当前依赖属于选中的模块及 Scope。插件支持常见修饰写法、配置继承和共享有序依赖列表；共享列表的删除、排除会修改当前模块的引用处。无法静态定位的动态工厂或无序集合需要在构建文件中手动调整。

### 模块依赖没有正确识别

确认 sbt 导入已完成，以及模块的 `organization` 设置正确；必要时设置 [Organization 覆盖值](#settings)。模块名查询不要求项目加入根项目的 `aggregate` 或 `dependsOn`。

通过 sbt `RootProject` 或 `ProjectRef` 加载的外部构建可以在同一个 sbt shell 中分析。插件使用构建 URI 和项目 ID 定位，同名项目不会混淆。活动 shell 未加载的独立链接构建，请分别在不同 IDEA 项目中打开。

### 出现 “Could not create lock for …”

等待进行中的导入或构建结束，再在 IDEA 内的 sbt shell 中执行 `reload` 或 `compile`，避免同时操作同一构建。

## 开发与验证

构建、测试、沙盒启动及发布说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。性能测量、PSI 回归及测试边界见 [验证记录](docs/validation/README.md)。

欢迎提交 [Issue](https://github.com/bitlap/sbt-dependency-analyzer/issues) 或 Pull Request。感谢 [JetBrains](https://www.jetbrains.com/) 提供开发工具支持。

[badge:build]: https://github.com/bitlap/sbt-dependency-analyzer/actions/workflows/ScalaCI.yml/badge.svg
[badge:license]: https://img.shields.io/github/license/bitlap/sbt-dependency-analyzer.svg?style=flat-square
[badge:downloads]: https://img.shields.io/jetbrains/plugin/d/22427.svg?style=flat-square&colorB=5C6BC0
[gh:workflows-build]: https://github.com/bitlap/sbt-dependency-analyzer/actions/workflows/ScalaCI.yml
[gh:license]: https://github.com/bitlap/sbt-dependency-analyzer/blob/master/LICENSE
[plugin-homepage]: https://plugins.jetbrains.com/plugin/22427-sbt-dependency-analyzer
