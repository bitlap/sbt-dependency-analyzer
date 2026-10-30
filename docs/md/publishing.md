# 发布到 JetBrains Marketplace

使用 Shell 入口 `tools/upload-plugin.sh`：实际签名和上传分别交给 sbt-idea-plugin 的 `signPlugin`、`publishPlugin`。默认只检查源码发布信息；必须显式指定 `--upload` 才会上传。需要 Bash、Python 3.9+（仅用于离线 XML/ZIP 校验）、sbt 和项目要求的 JDK。

## 检查、构建和上传

```sh
# 默认离线检查；不读取令牌、不运行 sbt、不上传
./tools/upload-plugin.sh --dry-run

# 构建签名包并校验，不上传
./tools/upload-plugin.sh --build

# 签名、校验后上传到 Stable
./tools/upload-plugin.sh --upload

# 自定义发布渠道
./tools/upload-plugin.sh --upload --channel eap
```

脚本可从其他工作目录运行。可用 `--sbt /path/to/sbt` 指定可执行文件；路径可以包含空格。`--channel Stable` 等同于默认渠道。原 Python 上传入口已移除；`tools/validate-plugin-release.py` 只负责离线校验和日志脱敏，没有网络上传功能。原生 `publishPlugin` 不支持隐藏更新参数，因此不再提供 `--hidden`。

## 本地配置与 CI

可直接设置环境变量，或使用默认本地文件 `~/.config/sbt-dependency-analyzer/credentials.env`（也支持 `XDG_CONFIG_HOME`）：

```sh
JAVA_HOME='/path/to/jdk'
PLUGIN_SIGN_CERT_FILE='/path/to/chain.crt'
PLUGIN_SIGN_PRIVATE_KEY_FILE='/path/to/private.pem'
PLUGIN_SIGN_KEY_PWD='your-private-key-password'
IJ_PLUGIN_REPO_TOKEN='your-marketplace-token'
```

也可显式指定文件：

```sh
./tools/upload-plugin.sh --build --env-file /private/path/release.env
```

配置文件按 Shell 语法加载，只使用自己维护的可信文件；不要将令牌、口令或私钥提交到仓库。建议本地配置和私钥仅允许本人读写。CI 可通过 Secret 注入相同环境变量，不需要配置文件。私钥没有口令时可以省略 `PLUGIN_SIGN_KEY_PWD`。脚本会启用 `SIGN_PLUGIN=true`；设置 `JAVA_HOME` 时将显式传入 sbt 的 `-java-home`，覆盖全局 sbt 选项中的旧 JDK。普通 sbt 构建仍默认禁用签名。

令牌读取顺序与原生 sbt 发布任务一致：

1. 非空的 `~/.ij-plugin-repo-token` 第一行。
2. `IJ_PLUGIN_REPO_TOKEN`。
3. `JETBRAINS_MARKETPLACE_TOKEN`，作为原生环境变量的兼容别名。

脚本不会改写令牌文件。存在旧令牌文件时，需要更新该文件或移走它，不能仅设置环境变量来覆盖。签名需要有效私钥和匹配的证书链，见 JetBrains [签名说明](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)。

## 上传前检查

- `build.sbt` 的 `pluginVersion` 与源码 `plugin.xml` 的 `<version>` 必须一致。
- `<change-notes>` 的最新 `<h1>` 必须对应当前版本，并有实际说明内容；有历史版本时必须递增，支持 RC 序号及 RC 转正式版。以本地历史为基准，不查询 Marketplace 已发布版本。
- 签名后检查 `target/signed-SbtDependencyAnalyzer-<版本>.zip`：必须包含正确插件 ID、版本和与源码一致的更新说明，ZIP 无损坏，包中无测试专用启动器。
- 校验只读取签名 ZIP，不解包重组或重新压缩；上传任务固定使用这份已校验的签名包，不再次构建或签名。打印路径和 SHA-256 便于核对。

校验失败时停止，不自动修改版本号或更新说明；历史条目应保留，在顶部新增当前版本说明。签名失败或上传失败均返回非零状态，日志会过滤令牌和私钥口令。上传不自动重试，连接中断后先检查 Marketplace 后台，避免重复提交。上传成功表示服务端接受请求，审核及上架状态以厂商后台为准。

## 离线回归

```sh
bash -n tools/upload-plugin.sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/test-upload-plugin.py
```

测试使用临时插件包及假的 sbt 可执行文件，验证版本和更新说明、默认不发布、签名包固定、Stable/自定义渠道、含空格和引号的路径、令牌文件优先级、签名及上传失败、日志脱敏和不重试。测试不会向 Marketplace 发送请求。

## GitHub Release

Marketplace 上传与 GitHub Release 是两个独立操作。Release Drafter 在 master 更新时读取并校验项目版本和 change-notes，草稿标题和标签统一为 `v<pluginVersion>`，不再依据 PR 标签推算版本。已有正式发布的版本保持不变，避免后续合并生成同版本草稿。

正式发布可在 GitHub Actions 的 Release Drafter 中选择 master，手动运行并勾选 `publish`，或在 Releases 页面审核草稿、附上已验签的分发 ZIP 后发布。普通 push 只维护草稿；不会自动再次上传 Marketplace。发布资产应使用同一次 Marketplace 上传的签名包。
