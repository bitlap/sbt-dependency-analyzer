# Scala Option 运行时兼容性

## 问题

点击依赖分析按钮时可能出现：

```text
java.lang.NoSuchMethodError: 'java.lang.Object scala.Option.orNull()'
```

编译器生成了 `scala/Option.orNull:()Ljava/lang/Object;` 调用，但 IDE 的 Scala 插件提供的标准库只有带类型证据参数的 `orNull` 方法，链接失败。普通 sbt 单测使用编译依赖的运行库，无法单独证明 IDE 运行时兼容。

## 修复

生产代码使用 `.getOrElse(null)` 代替 `.orNull`，维持原有空值处理语义，使用 IDE 标准库也提供的 `getOrElse(Function0)` 方法。覆盖按钮选择、声明跳转、配置文件查找、图解析、模块匹配和依赖修改路径。

## 验证

```sh
sbt check test psiTest
python3 tools/check-scala-option-abi.py \
  target/scala-3.9.0/classes \
  target/plugin/SbtDependencyAnalyzer/lib/sbt-dependency-analyzer.jar
```

字节码检查读取常量池中的方法引用，同时检查编译目录和实际插件 JAR，防止编译器重新引入不兼容调用或打包残留旧类。修复前它可以检出 9 个生产类中的同类引用。

隔离 IDEA 测试实际调用 `ViewDependencyAnalyzerAction.getSelectedData` 的缺失、空和非空选择分支，并执行工具栏 `setSelectedState` 的空选择路径。这些检查通过现有 `psiTest` 任务运行。

源码修改不会替换已安装插件。需要安装重新打包的插件，再按 IDE 提示重新加载或重启；仅重新导入 sbt 项目不会更新插件类。
