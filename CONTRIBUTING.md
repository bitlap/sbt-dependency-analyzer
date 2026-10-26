# Development and validation

Use JDK 21 and the sbt version in `project/build.properties`. On macOS:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
sbt clean test psiTest
sbt check
sbt runPluginVerifier
sbt 'runIDE noDebug'
```

The development sandbox uses a separate IntelliJ configuration. The first build downloads the configured SDK and Scala plugin. `sbt fmt` formats Scala/build files; Kotlin changes must also compile and pass the UI smoke checks.

## Supported platform

The default SDK is IntelliJ IDEA 2026.1.3 / `261.25134.95`, with Scala plugin resolved for that SDK. Plugin metadata limits installation to 261.x. Set `IDEA_BUILD` explicitly to evaluate another build, and run compilation, tests and Plugin Verifier before changing the supported range. Android Studio and 262.x are not currently claimed as verified targets.

## Reproducible parser benchmark

```sh
ANALYZER_BENCHMARK=true sbt 'testOnly *ParserBenchmarkSpec'
```

The suite compares a frozen pre-optimization graph expansion implementation with the current parser. Both use the same DOT input shapes; the new parser uses a linear reader for sbt output, while the frozen implementation uses Graphviz. Each case has one warmup and five measured cold parses; the slow 10,000-node legacy case has one cold sample and no warmup; it reports median, minimum and maximum in milliseconds. SDK startup, fixture generation and compilation are excluded. Timings are machine-dependent and are not IDE/sbt shell end-to-end measurements. Normal tests do not run this benchmark.

## Manual sandbox matrix

- Open an imported sbt project using the toolbar, module context menu and Ctrl+Shift+L (including macOS).
- Switch modules; search; toggle scopes, warnings, group IDs and sizes; use both tree/list views. Test Enter/Space/Esc in selectors.
- Change dependencies, refresh, regenerate the same DOT path, set cache timeout to 0/1/3600, and verify new results.
- Cancel and retry analysis; test shell errors, missing files and closing a loading editor.
- Preview direct removal and transitive exclusion. Verify scope/version targeting, multiple declarations, cancel, read-only files, concurrent edits and Undo. Reload sbt after edits before expecting updated resolution.
- Refresh snapshots with pre-existing shell session settings; ensure only action-created settings are removed, including update failure.
- Check multiple open projects and close/reopen them to verify cache isolation and disposal.

## Release signing

Signing is disabled for normal builds. For a release set `SIGN_PLUGIN=true`, `PLUGIN_SIGN_CERT_FILE`, `PLUGIN_SIGN_PRIVATE_KEY_FILE` and `PLUGIN_SIGN_KEY_PWD` in the release environment. Do not commit credentials or local certificate paths. CI runs tests, formatting and Plugin Verifier; a passing compile alone is not a compatibility check.

## Isolated UI / PSI smoke tests

Run `sbt psiTest` after changes to declaration matching, project identity or edit planning. CI runs both `test` and `psiTest`. To invoke the isolated runner directly after compilation/packaging:

```sh
IDE_HOME=/absolute/path/to/the/261.25134.95/sdk python3 tools/run-smoke.py
```

The test-only plugin starts a separate headless IntelliJ process with isolated settings, system directory and fixtures in `target/smoke`. It checks action registration, settings validation, cache invalidation without an editor, analyzer view creation/disposal, exact dependency edits and stale-preview protection. It renders the actual settings and edit-preview Swing components to `target/smoke/results/*.png`. These are component captures, not screenshots of manual interaction with a running sbt build. Generated results and images remain under `target/smoke/results/` and are not committed. The helper plugin is not shipped with production artifacts.

The distribution is reassembled after normal compilation/XML patching to work around an upstream incremental-packager bug with newly added files. `PackagingRegressionSpec` compares every production class and its bytes against the assembled JAR, including removal of stale classes. Plugin Verifier uses the exact installed Scala plugin offline, checks the process exit status and requires an explicit compatible report; API usage advisories are retained.

## Declaration and module identity regression coverage

`DeclarationMatcherSpec` checks exact versus cross-version artifact identity, configuration mappings and requested/resolved version selection. `SbtModuleIdentitySpec` checks published `moduleName` lookup using imported sbt IDs and platform metadata, including arbitrary project IDs and ambiguous coordinates. The PSI cases in `AnalyzerSmokeStarter` run against the actual Scala plugin: constants and aliases, typed/concatenated sequences, shared declarations, reference cycles, modifiers, scoped keys, explicit project IDs, backtick identifiers, cross-platform settings and syntactically valid edits.

Go to resolves declaration definitions and offers a chooser for ambiguous matches. Exclude edits the selected use site for a shared `ModuleID` alias; dependency lists/settings shared through references remain navigable but are not modified automatically. Unsupported computed dependency factories are skipped instead of inferred from strings. A resolved version first matches exact declarations, then coordinate/configuration candidates for evicted or dynamic requests, with explicit selection when several candidates remain.
