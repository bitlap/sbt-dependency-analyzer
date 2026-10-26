#!/usr/bin/env python3
"""Verify real sbt scoped queries, DOT output and session cleanup in an isolated dependency-free build."""
import os
import re
from pathlib import Path
import shutil
import subprocess
import time

root = Path(__file__).resolve().parents[1]
work = root / "target" / "sbt-workflows"
work.mkdir(parents=True, exist_ok=True)
(work / "project").mkdir(exist_ok=True)
(work / "project" / "build.properties").write_text((root / "project" / "build.properties").read_text())
(work / "project" / "plugins.sbt").write_text("addDependencyTreePlugin\n")
scala_version = re.search(r'scala3Version\s*=\s*"([^\"]+)"', (root / "build.sbt").read_text()).group(1)
(work / "build.sbt").write_text('''ThisBuild / scalaVersion := "__SCALA_VERSION__"
ThisBuild / version := "1.0.0"
ThisBuild / autoScalaLibrary := false
ThisBuild / crossPaths := false
lazy val verifyWorkflow = taskKey[Unit]("Verify analyzer sbt workflow")
lazy val root = project.in(file(".")).aggregate(api).settings(
  moduleName := "published-root",
  organization := "fixture.root",
  verifyWorkflow := {
    assert((LocalProject("core-api") / moduleName).value == "published-api")
    assert((LocalProject("core-api") / organization).value == "fixture.api")
    assert((LocalProject("detached-tools") / moduleName).value == "published-detached")
    assert((LocalProject("detached-tools") / organization).value == "fixture.detached")
    assert((LocalProject("core-api") / update / skip).value, "User's session setting was removed")
    println("ANALYZER_SBT_WORKFLOW_PASS")
  }
)
lazy val api = Project("core-api", file("different-directory")).settings(
  moduleName := "published-api", organization := "fixture.api"
)
lazy val detached = Project("detached-tools", file("unaggregated-directory")).settings(
  moduleName := "published-detached", organization := "fixture.detached"
)
'''.replace("__SCALA_VERSION__", scala_version))
commands = [
    'set LocalProject("core-api") / update / skip := true',
    "session list",
    'set LocalProject("core-api") / update / skip := false',
    'set LocalProject("core-api") / csrConfiguration := (LocalProject("core-api") / csrConfiguration).value.withTtl(Some(scala.concurrent.duration.Duration.Zero))',
    "core-api / update",
    "core-api / Compile / dependencyDot",
    "core-api / Test / dependencyDot",
    "core-api / Provided / dependencyDot",
    "detached-tools / Compile / dependencyDot",
    "session remove 3", "session remove 2", "session list", "verifyWorkflow",
]
for directory in (work / "different-directory", work / "unaggregated-directory"):
    for path in (directory / "target").glob("dependencies-*.dot"):
        path.unlink()
log = work / "sbt-workflows.log"
started = time.monotonic()
with log.open("w") as output:
    result = subprocess.run(
        [shutil.which("sbt") or "sbt", "-batch", "-no-colors", *commands],
        cwd=work, env={**os.environ, "SBT_SUPERSHELL": "false"},
        stdout=output, stderr=subprocess.STDOUT, timeout=180,
    )
text = log.read_text()
if result.returncode or "ANALYZER_SBT_WORKFLOW_PASS" not in text:
    raise SystemExit(f"sbt workflow failed; see {log}\n{text[-5000:]}")
for directory, scopes, artifact in [
    ("different-directory", ("compile", "test", "provided"), "fixture.api:published-api:1.0.0"),
    ("unaggregated-directory", ("compile",), "fixture.detached:published-detached:1.0.0"),
]:
    for scope in scopes:
        dot = work / directory / "target" / f"dependencies-{scope}.dot"
        if not dot.is_file() or artifact not in dot.read_text():
            raise SystemExit(f"Missing or incorrect DOT output: {dot}")
print(f"PASS real sbt scoped moduleName/organization, four DOT outputs and session cleanup ({time.monotonic() - started:.2f}s)")
