# sbt-dependency-analyzer

<img src="./logo.svg" width="250" height="150" alt="Sbt Dependency Analyzer Logo" align="right" />

[![Build Status][badge:build]][gh:workflows-build]
[![License][badge:license]][gh:license]
[![GitHub releases][badge:release]][gh:releases]
[![Version][badge:version]][plugin-versions]
[![Downloads][badge:downloads]][plugin-homepage]

<p align="center"><b>Sbt Dependency Analyzer plugin for IntelliJ based IDEs/Android Studio.</b></p>


English | [中文](README-CN.md)

**⭐ Found this plugin useful? Please give it a Star on [GitHub](https://github.com/bitlap/sbt-dependency-analyzer) to show your support!**

## 🚀 Overview

The Sbt Dependency Analyzer plugin provides powerful visual tools to help you understand, manage, and troubleshoot your Sbt project's dependencies with ease. Gain clear insights into your library dependencies and their relationships, directly within your IDE.

## ✨ Key Features

*   **Dependency Tree Visualization**: View a hierarchical tree of all your project's dependencies.
*   **Conflict Identification**: Quickly spot and resolve version conflicts between libraries.
*   **Dependency Search**: Easily find specific dependencies across your project.
*   **Inter-Module Dependency Analysis**: Visualize how different modules in your project depend on each other.
*   **JAR Size Indicators**: See the size of dependency JARs to better manage your project's footprint.
*   **Navigate to Declaration**: Click on a *user-defined* dependency to jump directly to its declaration in `build.sbt`.
*   **Dependency Exclusion (Experimental)**:
    *   Select a *transitive* dependency to exclude it from a *user-defined* dependency.
    *   Select a *user-defined* dependency to remove it entirely.
    *   *Available since plugin version `0.5.0-242.21829.142`.*

## 🛠️ Installation & Setup

1.  **Install the Plugin**: Go to `Settings/Preferences` > `Plugins` > `Marketplace`, search for "Sbt Dependency Analyzer", and install it.
2.  **Automatic Setup**: Upon first analysis, the plugin will automatically generate a `project/sdap.sbt` file if needed. This file adds the required `addDependencyTreePlugin` statement. **Please do not modify or delete this file** once created.
3.  **Plugin Dependency**: This plugin leverages the `sbt-dependency-tree` functionality, which is bundled with recent sbt versions (though not be enabled by default, [sbt issue](https://github.com/sbt/sbt/pull/5880)).

## 📖 Usage

> **Default Keyboard Shortcut**: `Ctrl` + `Shift` + `L` (Windows/Linux) / `Command` + `Shift` + `L` (macOS)

Simply open your Sbt project in IntelliJ IDEA and use the shortcut to generate and view the dependency analysis.

![Dependency Analysis View](https://plugins.jetbrains.com/files/22427/screenshot_064531dc-a3fa-4a8e-9437-7e76defa1f48)
*The interactive dependency graph provides a clear overview of your project's structure.*

## ⚙️ Configuration <a id="settings"></a>

Fine-tune the plugin's behavior and potentially speed up analysis via `Settings/Preferences` > `Tools` > `Sbt Dependency Analyzer`:

<img src="./docs/settings.png" width="400" height="280" alt="Plugin Settings Panel" align="right" />

*   **File Cache Timeout**: Adjust how long (in seconds) the plugin uses cached dependency graph files (`.dot`) before re-running the `dependencyDot` command. (Default: `3600`).
*   **Organization**: Predefine your project's organization value here to avoid the plugin needing to query sbt for it.
*   **Disable Scopes**: Improve analysis speed by disabling dependency scopes (e.g., `Test`, `Provided`) you are not interested in.

These settings are stored per IntelliJ project in `.idea/bitlap.sbt.dependency.analyzer.xml`. Deleting this file will reset settings and clear the cache.

## ❗ Troubleshooting

### Issue: "Caused by: java.io.IOException: Could not create lock for ..."
This error can occur due to conflicts between the plugin's use of the sbt shell and IntelliJ IDEA's internal project reload/build mechanisms.
*   **Solution**: Use the **sbt shell within IntelliJ IDEA** for reloading (`sbt reload`) or building (`sbt compile`) your project, instead of the IDE's built-in buttons.
    <img src="./docs/sbtShellUseForReload.jpg" width="500" height="230" alt="Using SBT Shell for reload and compile" align="center" />

### Issue: Cannot analyze dependencies between modules
The plugin may fail to correctly parse inter-module dependencies if it cannot determine the project organization.
*   **Solution**: Ensure the plugin knows your project's organization by either:
    1.  Setting the **Organization** value in the plugin's [settings](#settings).
    2.  Defining the `organization` setting in your `build.sbt` globally using `ThisBuild / organization` or `inThisBuild(...)`.
> **Note**: Submodules not declared within the `dependsOn` clause of the root project will not be parsed.

## 🔍 Technical Details

1. The plugin executes several sbt commands (`organization`, `moduleName`, `dependencyDot`, `reload`, `update`) to gather dependency information. Significant optimizations are in place to minimize the number and impact of these commands.
2. The plugin has replicated the Kotlin code from the [intellij-community](https://github.com/JetBrains/intellij-community) project on the UI and compiles it using the [kotlin-plugin](https://github.com/bitlap/kotlin-plugin).

## 🤝 Contributing

Contributions are welcome! Please feel free to submit issues, feature requests, or pull requests on [GitHub](https://github.com/bitlap/sbt-dependency-analyzer).

## 🙏 Acknowledgments

**JetBrains Support**: This project is developed using JetBrains IntelliJ IDEA. We extend our gratitude to JetBrains for providing a free license, which significantly supports its development.

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
