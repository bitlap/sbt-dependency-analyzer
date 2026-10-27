# 发布到 JetBrains Marketplace

`tools/upload-plugin.py` 使用 JetBrains Marketplace 的[官方上传 API](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html)提交插件更新。默认只校验和打包，必须显式指定 `--upload` 才上传。脚本需要 Python 3.9+；上传需要 curl，`--build` 需要 sbt 和项目构建所需的 JDK。

## 构建和校验

先按 [贡献指南](../CONTRIBUTING.md)运行发布前测试和兼容性检查，再构建并校验：

```sh
python3 tools/upload-plugin.py --build --dry-run
```

脚本运行 `sbt packageArtifact`，读取 `target/plugin/SbtDependencyAnalyzer`，生成 `target/releases/SbtDependencyAnalyzer-<版本>.zip`。sbt 不在 PATH 时可传 `--sbt /absolute/path/to/sbt`。

也可校验已有 ZIP；已有 ZIP 不会重新打包，保留签名内容：

```sh
python3 tools/upload-plugin.py target/releases/SbtDependencyAnalyzer-0.12.0.zip --dry-run
```

校验包含插件 ID、ZIP 结构、构建版本和包内版本、最新更新说明标题，以及测试启动器是否混入发布包。成功后打印文件路径、SHA-256、发布渠道和隐藏状态。校验不访问 Marketplace，也不需要令牌。签名配置见贡献指南。

## 上传

在 Marketplace 个人账户的 **My Tokens** 中创建永久令牌，账户须有插件更新权限。通过本机环境或 CI Secret 注入 `JETBRAINS_MARKETPLACE_TOKEN`，不要把令牌写入脚本、仓库或命令行参数。

确认上一步输出的 ZIP 后上传：

```sh
python3 tools/upload-plugin.py target/releases/SbtDependencyAnalyzer-0.12.0.zip --upload
```

默认提交到 Stable 渠道。自定义渠道和隐藏更新示例：

```sh
python3 tools/upload-plugin.py target/releases/SbtDependencyAnalyzer-0.12.0.zip --upload --channel eap --hidden
```

`--hidden` 对应官方 API 的 `isHidden=true`。脚本使用 XML 插件 ID `org.bitlap.sbtDependencyAnalyzer` 识别插件，令牌通过 curl 标准输入传入，不出现在子进程参数中。它不创建 Git 标签或 GitHub Release。

请求成功表示 Marketplace 已接受上传，仍需在厂商后台查看审核和发布状态。网络失败时先检查后台，再决定是否重试；脚本不会自动重传，避免重复提交。本文版本号仅为使用示例，执行时应选择当前构建的文件。

## 脚本测试

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/test-upload-plugin.py
```

测试使用临时插件包和模拟上传响应，覆盖默认不上传、包校验、Stable/自定义渠道、隐藏更新、令牌脱敏和失败处理，不向 Marketplace 发送上传请求。CI 同样执行这些离线测试。
