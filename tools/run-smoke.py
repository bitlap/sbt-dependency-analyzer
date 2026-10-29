#!/usr/bin/env python3
"""Run test-only checks in an isolated IntelliJ process using IDE_HOME and compiled test classes.

Build a temporary starter plugin and render real Swing components in headless mode.
Configuration, logs and results stay under target/smoke; this is not a manual IDE import test."""
import json
import os
import re
import runpy
from pathlib import Path
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ["IDE_HOME"]).resolve()
work = root / "target" / "smoke"
plugin = work / "plugins" / "analyzer-smoke"
scala_version = re.search(r'scala3Version\s*=\s*"([^"]+)"', (root / "build.sbt").read_text()).group(1)
test_classes = Path(os.environ.get("TEST_CLASSES_DIR", root / f"target/scala-{scala_version}/test-classes"))
if not (test_classes / "bitlap/sbt/analyzer/AnalyzerSmokeStarter.class").is_file():
    sys.exit(f"Compile tests before running PSI checks: {test_classes}")
# Ordinary sbt tests may resolve a newer Scala library than the installed IDE plugin.
abi_check = runpy.run_path(str(root / "tools/check-scala-option-abi.py"))
abi_check["main"]([test_classes.parent / "classes", root / "target/plugin/SbtDependencyAnalyzer/lib/sbt-dependency-analyzer.jar"])
(plugin / "lib").mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(plugin / "lib" / "smoke.jar", "w") as jar:
    for entry in sorted(test_classes.rglob("*.class")):
        if not entry.name.startswith(("AnalyzerSmokeStarter", "ScalaOptionCompatibilityChecks")):
            continue
        jar.write(entry, str(entry.relative_to(test_classes)))
    jar.writestr("META-INF/plugin.xml", '''<idea-plugin><id>org.bitlap.analyzerSmoke</id><name>Analyzer smoke tests</name><version>1</version><vendor>Bitlap</vendor><depends>org.bitlap.sbtDependencyAnalyzer</depends><extensions defaultExtensionNs="com.intellij"><appStarter id="analyzer-smoke" implementation="bitlap.sbt.analyzer.AnalyzerSmokeStarter"/></extensions></idea-plugin>''')
for name, source in [("SbtDependencyAnalyzer", root / "target/plugin/SbtDependencyAnalyzer"), ("Scala", sdk / "custom-plugins/Scala")]:
    destination = work / "plugins" / name
    if not destination.exists(): destination.symlink_to(source, target_is_directory=True)
info = json.loads((sdk / "product-info.json").read_text())
launch = next(v for v in info["launch"] if v["os"] == {"darwin": "macOS", "linux": "Linux", "win32": "Windows"}[sys.platform])
java = sdk / "jbr/Contents/Home/bin/java" if sys.platform == "darwin" else sdk / "jbr/bin/java"
output = work / "results"
args = [str(java), "-Xmx2048m", "-Djava.awt.headless=true", "-Didea.platform.prefix=Idea",
        "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader", f"-Didea.home.path={sdk}",
        f"-Didea.config.path={work / 'config'}", f"-Didea.system.path={work / 'system'}", f"-Didea.log.path={work / 'log'}",
        f"-Didea.plugins.path={work / 'plugins'}", f"-Dplugin.path={plugin}",
        f"-Danalyzer.smoke.output={output}", "-Djb.consents.confirmation.enabled=false"]
args.extend(value.replace("$APP_PACKAGE", str(sdk)).replace("$IDE_HOME", str(sdk)) for value in launch.get("additionalJvmArguments", []))
args += ["-cp", os.pathsep.join(str(sdk / "lib" / name) for name in launch["bootClassPathJarNames"]), "com.intellij.idea.Main", "analyzer-smoke"]
result = subprocess.run(args, cwd=root, timeout=180, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
print(result.stdout, end="")
# Swing may print an Error and continue; functional assertions alone must not turn a broken UI run green.
startup_errors = ("no ComponentUI class for:", "Class initialization must not depend on services")
if any(message in result.stdout for message in startup_errors):
    print("IDE smoke checks rejected: UI delegate or service initialization error", file=sys.stderr)
    sys.exit(1)
sys.exit(result.returncode)
