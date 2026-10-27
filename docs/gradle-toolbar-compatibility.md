# sbt 工具窗口中的重复依赖分析按钮

Gradle 的依赖分析入口注册在共用的外部构建工具栏中，原有显示条件没有检查当前构建系统，因此 sbt 工具窗口也会显示它。

本插件在 Gradle 可用时加载可选描述文件，以声明式 Action 覆盖为该入口补充上下文过滤：

- sbt 工具窗口隐藏官方 Gradle 入口，保留本插件入口。
- Gradle、Maven 和无明确构建系统的上下文保留官方入口原有行为。
- 本插件入口只在有链接的 sbt 构建时显示，并排除明确的其他构建系统上下文；无构建系统上下文时仍支持快捷键入口。
- Gradle 被禁用或不可用时不注册覆盖，本插件仍可加载。

覆盖通过 `overrides="true"` 注册，由平台替换工具栏引用并在插件卸载时恢复官方 Action。原始 Action 可能仍是延迟加载的 `ActionStub`，因此只在需要委托时，通过其所属插件实例化。兼容代码使用平台内部的基础 Action 查询接口，需要随平台升级验证。

## 回归验证

执行：

```sh
sbt test psiTest gradleAnalyzerTest
```

`gradleAnalyzerTest` 编译测试、打包插件，并分别启动启用和禁用 Gradle 的隔离 IDEA 进程。检查实际注册的 Action、上下文可见性、文本和图标、平台 Registry 开关、工具栏引用，以及两轮动态卸载和重载后的恢复。另在同一个具有 sbt 链接设置的项目中切换构建系统上下文，检查本插件入口的显示条件。

运行器不依赖被测插件的类加载器，避免测试插件自身阻止动态卸载。配置、日志和结果只写入 `target/gradle-analyzer-compat/`，不改变日常使用的 IDE 配置。它不代替完整的 Gradle/sbt 项目导入或 Marketplace 安装测试。

2026-10-27 合并最新主分支后，73 项单测、81 项核心 IDEA 检查，以及 Gradle 启用 64 项/禁用 3 项兼容检查通过；真实 sbt 多构建夹具同时通过。CI 保留两组 IDEA 回归与真实 sbt 夹具，不因冲突解决而省略检查。

合并验证的 Plugin Verifier 结果为 `Compatible`：19 处弃用、45 处实验性、21 处内部 API 引用，以及 1 处 `OverrideOnly` 使用提示。后者来自兼容包装器委托官方 `actionPerformed`，是该实现现有的平台 API 使用边界；本次冲突解决未修改此委托逻辑。
