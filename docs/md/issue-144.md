# #144：RootProject 外部构建引用

关联问题：[RootProject references do not work](https://github.com/bitlap/sbt-dependency-analyzer/issues/144)。

## 原因和修复

`sbt` 项目 ID 只在所属构建内唯一。原实现把外部项目 ID 直接写入当前构建的命令，并按裸 ID 缓存名称；这会导致找不到项目，或在两个构建包含同名 ID 时使用错误模块。此前按 IDEA 根目录拒绝其他构建的条件也无法区分 RootProject 与独立链接构建。

现在从导入模型读取构建 URI 和项目 ID，以完整引用生成命令、查询 moduleName/organization、选择平台元数据，并作为名称及依赖缓存的键。例如：

```text
{file:/work/external/}project1Root / Test / dependencyDot
set ProjectRef(uri("file:/work/external/"), "project1Root") / update / skip := false
```

分析前读取活动 shell 的 `projects` 输出，仅查询其中已加载的项目。RootProject/ProjectRef 加载的构建可以继续使用同一个 shell；未加载的独立链接构建仍给出明确提示。设置变更和重新导入会使加载列表失效，失效前启动的列表查询不会重新填入缓存。

无需把 aggregate 当作 classpath 合并：查看外部项目自身依赖时，应在分析器选择对应模块。原问题示例中的 Runtime 依赖会出现在 Test 图中，不能据 Compile 图为空判断依赖丢失。

## 回归验证

- `SbtProjectReferenceSpec` 验证 URI 规范化、含空格路径、同名项目 ID、`projects` 标记解析、命令/设置两种引用语法、未加载项目区分，以及按完整标识构建和定位 DOT 模块。
- 隔离 IDEA 回归验证 RootProject 的 SNAPSHOT 刷新使用 ProjectRef、只清理新增临时设置，以及设置/刷新时加载列表失效。
- `python3 tools/test-sbt-workflows.py` 创建外部构建、与主构建同名的 `core-api`、未聚合模块、含空格目录和本地 Maven Runtime 依赖。实际执行 scoped 查询、update、七次 dependencyDot 和 session 清理；验证 Test 包含 Runtime 依赖而 Compile 不包含。
- 本轮 `sbt test` 55 项、`sbt psiTest` 44 项通过，格式检查和 Plugin Verifier 通过；真实 sbt 夹具成功运行耗时 23.81 秒。

这些检查验证实际 sbt 多构建命令、文件输出和插件侧标识传递；尚未将完整 IDEA RootProject 导入及鼠标操作列为已完成的手工验证。每个构建仍需要启用 dependency-tree 插件，测试夹具在主构建与外部构建均启用了它。
