#!/usr/bin/env python3
"""Check registered toolbar actions and dynamic plugin restoration in isolated IDE processes.

Requires IDE_HOME, compiled tests and a packaged plugin. Runs with Gradle enabled and disabled;
all disposable configuration, logs and results stay under target/gradle-analyzer-compat.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ["IDE_HOME"]).resolve()
classes = Path(os.environ["TEST_CLASSES_DIR"]).resolve()
work = root / "target/gradle-analyzer-compat"
starter = work / "plugins/compat-tests/lib/starter.jar"
starter.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(starter, "w") as archive:
    for entry in classes.rglob("GradleAnalyzerCompatibilityStarter*.class"):
        archive.write(entry, entry.relative_to(classes))
    archive.writestr("META-INF/plugin.xml", '''<idea-plugin>
<id>org.bitlap.gradleAnalyzerCompatibilityTests</id><name>Analyzer compatibility tests</name>
<version>1</version><vendor>Bitlap</vendor><depends>com.intellij.modules.platform</depends>
<extensions defaultExtensionNs="com.intellij"><appStarter id="analyzer-compat-tests"
implementation="bitlap.sbt.analyzer.GradleAnalyzerCompatibilityStarter"/></extensions></idea-plugin>''')
for name, source in [("SbtDependencyAnalyzer", root / "target/plugin/SbtDependencyAnalyzer"),
                     ("Scala", sdk / "custom-plugins/Scala")]:
    destination = work / "plugins" / name
    if not source.is_dir():
        sys.exit(f"Missing plugin: {source}")
    if destination.is_symlink():
        destination.unlink()
    if not destination.exists():
        destination.symlink_to(source, target_is_directory=True)
info = json.loads((sdk / "product-info.json").read_text())
launch = next(item for item in info["launch"] if item["os"] == {"darwin": "macOS", "linux": "Linux", "win32": "Windows"}[sys.platform])
java = sdk / ("jbr/Contents/Home/bin/java" if sys.platform == "darwin" else "jbr/bin/java")
for mode in ("enabled", "disabled"):
    run = work / mode
    # A previous dynamic-unload run may have persisted plugin state: use a fresh isolated configuration.
    if run.exists():
        shutil.rmtree(run)
    (run / "config").mkdir(parents=True)
    if mode == "disabled":
        (run / "config/disabled_plugins.txt").write_text("com.intellij.gradle\n")
    args = [str(java), "-Xmx2048m", "-Djava.awt.headless=true", "-Didea.platform.prefix=Idea",
            "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader", f"-Didea.home.path={sdk}",
            f"-Didea.config.path={run / 'config'}", f"-Didea.system.path={run / 'system'}",
            f"-Didea.log.path={run / 'log'}", f"-Didea.plugins.path={work / 'plugins'}",
            f"-Danalyzer.compat.output={run / 'results.txt'}", "-Djb.consents.confirmation.enabled=false",
            f"-Danalyzer.compat.withoutGradle={str(mode == 'disabled').lower()}"]
    args.extend(value.replace("$APP_PACKAGE", str(sdk)).replace("$IDE_HOME", str(sdk)) for value in launch.get("additionalJvmArguments", []))
    args += ["-cp", os.pathsep.join(str(sdk / "lib" / name) for name in launch["bootClassPathJarNames"]),
             "com.intellij.idea.Main", "analyzer-compat-tests"]
    result = subprocess.run(args, cwd=root, timeout=180)
    if result.returncode:
        sys.exit(result.returncode)
    print((run / "results.txt").read_text())
