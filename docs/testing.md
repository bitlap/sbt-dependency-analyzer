# 核心流程测试覆盖

常规单测、隔离 IDEA 测试和真实 sbt 夹具分别验证算法、IDE 状态及构建命令。CI 的 test job 执行三层检查，verification job 执行插件兼容性检查。

```sh
sbt test psiTest check runPluginVerifier
python3 tools/test-sbt-workflows.py
```

运行真实 sbt 夹具时需要 PATH 中存在 `sbt`，使用与项目相同的 JDK。夹具读取项目的 sbt 和 Scala 版本，所有文件、日志和生成的 DOT 均写入忽略提交的 `target/sbt-workflows/`；IDE 测试结果位于 `target/smoke/results/`。

| 核心流程 | 自动验证的行为 | 测试入口 |
| --- | --- | --- |
| DOT 读取 | sbt 输出、注释/转义/边链、坏文件拒绝、复杂子图回退 Graphviz | `AnalyzerRegressionSpec`、`CoreFlowSpec` |
| 图构建 | 直接/传递关系、菱形共享路径、淘汰标签、孤立节点、环与深度保护；12 组确定性随机 DAG 对比全部预期路径 | `AnalyzerRegressionSpec`、`CoreFlowSpec` |
| 缓存 | TTL、禁用缓存、文件替换/删除、容量、项目隔离、失效中的计算不能回填 | `AnalyzerRegressionSpec`、`CoreFlowSpec` |
| 项目与模块 | sbt ID / IDEA 名称 / 目录 / moduleName 分离；JVM、JS、Native 元数据消歧；未聚合模块、RootProject 构建 URI 与同名 ID 隔离 | `SbtModuleIdentitySpec`、PSI 回归、真实 sbt 夹具 |
| 声明解析 | `%` / `%%` / `%%%`、常量/别名、Scope、修饰器、共享序列、project/crossProject 设置归属、循环终止 | `DeclarationMatcherSpec`、`AnalyzerSmokeStarter` |
| 分析作用域 | Compile / Test / Provided 匹配；全部 8 种禁用组合控制 contributor | `DeclarationMatcherSpec`、`AnalyzerSmokeStarterUiFlows` |
| 菜单注册与上下文 | 列表、树、usages 三组注册；多候选可见性、索引置灰、上下文恢复、右键选中行、空白清选、DataSink | `AnalyzerSmokeStarter`、`AnalyzerSmokeStarterCore` |
| 声明修改 | 精确删除/排除、共享/只读拒绝、逗号和注释、预览前不写入、过期拒绝、实际 Undo/Redo | PSI 回归、`AnalyzerSmokeStarterCore` |
| 设置 | 校验失败不部分写入、trim、应用/重置、只在改变时发布事件 | `AnalyzerRegressionSpec`、`AnalyzerSmokeStarterCore` |
| 后台 sbt 任务 | 禁止 EDT 等待、成功/异常/取消传播、并发动作互斥、结束后恢复可执行 | `AnalyzerSmokeStarterCore` |
| SNAPSHOT 刷新 | 成功/失败/取消均只清理本动作设置，保留已有 session；根项目/连字符项目 ID、部分设置失败、相同文本的用户设置、删除后编号移动、Coursier TTL 分支 | `AnalyzerSmokeStarterCore`、真实 sbt 夹具 |
| 加载与重试 | 失败/取消结束 loading；后续成功恢复；过期版本和已关闭视图的结果被丢弃 | `AnalyzerSmokeStarterCore` |
| 筛选与 usages | 大小写不敏感搜索、Scope、警告、连续输入采用最后条件，筛选树保留祖先，usages 保留各作用域路径 | `AnalyzerSmokeStarterUiFlows` |
| JAR 大小 | 真实 JAR 文件长度、共享/循环路径去重和淘汰跳过；后台计算、单次调度、EDT 回调、缓存命中、坐标相等性和 hash 不随大小改变 | `AnalyzerSmokeStarterUiFlows` |
| 刷新事件与生命周期 | 设置/刷新事件清缓存、忽略其他项目、dispose 后监听断开、关闭视图不接受后台结果 | `AnalyzerSmokeStarterCore`、`AnalyzerSmokeStarterUiFlows` |
| What’s New | 首次/升级/重复/降级、失败可重试、成功才记版本、本地只读 HTML 渲染和释放 | `WhatsNewSpec`、IDE 回归 |
| 打包与兼容性 | 分发包逐字节包含全部生产类、排除测试辅助类，Plugin Verifier 验证目标 SDK | `PackagingRegressionSpec`、`runPluginVerifier` |

## 本轮验证结果

2026-10-26 本地验证通过：

- `sbt test`：55 项，断言运行时间 1.259 秒（不含构建准备）。
- `sbt psiTest`：44 项，检查体耗时合计 6.116 秒（不含构建准备及 IDE 启动）。
- 真实 sbt 夹具：通过，23.81 秒，涵盖 RootProject、同名 ID、未聚合模块、七份 DOT、外部 Runtime 依赖和 session 清理，详见 [#144 修复记录](issue-144.md)。
- `sbt check`、`git diff --check`、Python 语法及文档链接检查通过。
- Plugin Verifier：Compatible，保留 19 处弃用、31 处实验性和 16 处内部 API 提示。

## 夹具和边界

- IDE 回归运行真实 IntelliJ / Scala PSI、Swing 组件、命令栈、通知总线和后台队列。无界面 ApplicationStarter 持有 UI intent lock，等待异步 read action 时测试会临时释放该锁并派发事件；等待有明确超时。
- sbt 异常与取消通过替换 `RefreshSnapshotsTask` 的命令传输模拟，保留生产的设置加入、更新及 finally 清理流程。真实 sbt 夹具补充验证实际 scoped 命令与文件输出，但不替代 IDEA 内的 shell 通信全流程。
- 多候选动作测试提供真实 PSI 候选；后台视图测试通过内部属性和加载入口控制输入，执行实际筛选、树构建和过期保护。测试包含有意制造的失败，日志中的对应 WARN 不代表断言失败。
- JVM / JS / Native 的匹配与 crossProject 设置通过元数据和 PSI 夹具验证。实际跨平台插件下载、Marketplace 动态安装、键盘焦点/弹窗点击矩阵、长时间内存和真实大项目端到端压力测试仍按 [手工矩阵](../CONTRIBUTING.md#manual-sandbox-matrix) 执行。
- 当前覆盖是核心流程及已知边界的行为覆盖，不等同于全部语法、平台和时序组合的穷举，也未将其表述为行覆盖率 100%。
