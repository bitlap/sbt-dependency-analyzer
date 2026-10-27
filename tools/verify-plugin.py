#!/usr/bin/env python3
"""Run Plugin Verifier against the configured IDE and its locally installed Scala plugin.

IDE_HOME, PLUGIN_DIR and VERIFIER_VERSION can override defaults. Download the verifier
when missing, prepare a local dependency archive, and write reports under target/verifier.
This verifies binary compatibility, not full interactive behavior."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get("IDE_HOME", Path.home() / ".SbtDependencyAnalyzerPluginIC/sdk" / os.environ.get("IDEA_BUILD", "261.25134.95"))).resolve()
plugin = Path(os.environ.get("PLUGIN_DIR", root / "target/plugin/SbtDependencyAnalyzer")).resolve()
work = root / "target/verifier"
version = os.environ.get("VERIFIER_VERSION", "1.402")
jar = work / "downloads" / f"verifier-cli-{version}-all.jar"
jar.parent.mkdir(parents=True, exist_ok=True)
if not jar.exists():
    url = f"https://cache-redirector.jetbrains.com/packages.jetbrains.team/maven/p/intellij-plugin-verifier/intellij-plugin-verifier/org/jetbrains/intellij/plugins/verifier-cli/{version}/{jar.name}"
    temporary = jar.with_suffix(".part")
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
        shutil.copyfileobj(response, output)
    temporary.replace(jar)
scala = sdk / "custom-plugins/Scala"
assert scala.is_dir(), f"Scala plugin is not installed in {sdk}; build the plugin first"
repository = work / "local/loaded-plugins"
repository.mkdir(parents=True, exist_ok=True)
archive = repository / "Scala.zip"
scala_version = None
for library in (scala / "lib").glob("*.jar"):
    with zipfile.ZipFile(library) as source:
        if "META-INF/plugin.xml" in source.namelist():
            descriptor = ET.fromstring(source.read("META-INF/plugin.xml"))
            if descriptor.findtext("id") == "org.intellij.scala":
                scala_version = descriptor.findtext("version")
                break
cached = sdk.parent / "downloads" / f"scala-intellij-bin-{scala_version}.zip"
if archive.exists() or archive.is_symlink():
    archive.unlink()
if cached.is_file():
    # Copy rather than symlink for Windows and container portability.
    shutil.copyfile(cached, archive)
else:
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as target:
        for file in scala.rglob("*"):
            if file.is_file():
                target.write(file, str(file.relative_to(scala.parent)))
reports = work / "offline-reports"
if reports.exists():
    shutil.rmtree(reports)
java = sdk / "jbr/Contents/Home/bin/java" if sys.platform == "darwin" else sdk / "jbr/bin/java"
result = subprocess.run([str(java), f"-Dplugin.verifier.home.dir={work / 'local'}", "-jar", str(jar),
                         "-offline", "-verification-reports-dir", str(reports), "check-plugin", str(plugin), str(sdk)],
                        cwd=root, timeout=180)
verdicts = list(reports.rglob("verification-verdict.txt"))
if result.returncode != 0 or not verdicts:
    sys.exit(result.returncode or 1)
for verdict in verdicts:
    text = verdict.read_text()
    print(f"Verifier verdict: {text.strip()}")
    if not text.startswith("Compatible."):
        sys.exit(1)
    for name in ("compatibility-problems.txt", "compatibility-warnings.txt"):
        problems = verdict.parent / name
        if problems.exists() and problems.read_text().strip():
            sys.exit(1)
