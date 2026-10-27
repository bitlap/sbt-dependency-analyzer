#!/usr/bin/env python3
"""Exercise real sbt commands in generated multi-build fixtures under target/sbt-workflows.

Cover qualified RootProject references, duplicate IDs, scoped queries, Runtime DOT output
and preservation of user session settings. The fixture artifact is local; sbt/compiler
bootstrap may still require network access. This does not exercise IDE project import."""
import os
import re
from pathlib import Path
import shutil
import subprocess
import time
import zipfile

root = Path(__file__).resolve().parents[1]
work = root / "target" / "sbt-workflows"
work.mkdir(parents=True, exist_ok=True)
child = work / "external build"
scala_version = re.search(r'scala3Version\s*=\s*"([^\"]+)"', (root / "build.sbt").read_text()).group(1)
for build in (work, child):
    (build / "project").mkdir(parents=True, exist_ok=True)
    (build / "project" / "build.properties").write_text((root / "project" / "build.properties").read_text())
    (build / "project" / "plugins.sbt").write_text("addDependencyTreePlugin\n")

# A local Maven repository makes the external Runtime dependency independent of external artifact servers.
repository = work / "fixture-repository"
artifact = repository / "fixture/runtime/runtime-only/1.0.0"
artifact.mkdir(parents=True, exist_ok=True)
(artifact / "runtime-only-1.0.0.pom").write_text('''<project><modelVersion>4.0.0</modelVersion>
<groupId>fixture.runtime</groupId><artifactId>runtime-only</artifactId><version>1.0.0</version></project>''')
with zipfile.ZipFile(artifact / "runtime-only-1.0.0.jar", "w") as jar:
    jar.writestr("fixture.txt", "RootProject runtime dependency")
common = f'''ThisBuild / scalaVersion := "{scala_version}"
ThisBuild / version := "1.0.0"
ThisBuild / autoScalaLibrary := false
ThisBuild / crossPaths := false
'''
uri = child.as_uri() + "/"
(child / "build.sbt").write_text(common + f'''ThisBuild / externalResolvers := Seq("fixture" at "{repository.as_uri()}/", "central" at "https://repo.maven.apache.org/maven2")
lazy val project1Root = project.in(file(".")).settings(
  moduleName := "external-published", organization := "fixture.external",
  libraryDependencies += "fixture.runtime" % "runtime-only" % "1.0.0" % Runtime
)
lazy val duplicate = Project("core-api", file("duplicate-directory")).settings(
  moduleName := "external-duplicate", organization := "fixture.external"
)
''')
(work / "build.sbt").write_text(common + '''lazy val verifyWorkflow = taskKey[Unit]("Verify analyzer sbt workflow")
lazy val root = project.in(file(".")).aggregate(api, browser, server, RootProject(file("external build"))).settings(
  moduleName := "published-root", organization := "fixture.root",
  verifyWorkflow := {
    assert((LocalProject("core-api") / moduleName).value == "published-api")
    assert((LocalProject("core-api") / organization).value == "fixture.api")
    assert((LocalProject("detached-tools") / moduleName).value == "published-detached")
    assert((ProjectRef(uri("__EXTERNAL_URI__"), "project1Root") / moduleName).value == "external-published")
    assert((ProjectRef(uri("__EXTERNAL_URI__"), "project1Root") / organization).value == "fixture.external")
    assert((ProjectRef(uri("__EXTERNAL_URI__"), "core-api") / moduleName).value == "external-duplicate")
    assert((LocalProject("core-api") / update / skip).value, "Main user session setting was removed")
    assert((ProjectRef(uri("__EXTERNAL_URI__"), "project1Root") / update / skip).value, "External user session setting was removed")
    val selected = "fixture" %% "a" % "1"
    def matches(dependency: ModuleID): Boolean =
      (dependency.organization, dependency.name, dependency.revision, dependency.configurations.getOrElse("compile"), dependency.crossVersion) ==
        ("fixture", "a", "1", "compile", ("fixture" %% "a" % "1").crossVersion)
    val variants = Seq("fixture" % "a" % "1", "fixture" %% "a" % "2", "fixture" %% "a" % "1" % Test,
      ("fixture" %% "a" % "1").cross(CrossVersion.full), "fixture" %% "b" % "1")
    val shared = variants.patch(2, Seq(selected, selected), 0)
    for (input <- Seq(shared, shared.reverse)) {
      val excluded = (input).map { dependency => if (matches(dependency)) dependency.exclude("t", "child") else dependency }
      val removed = (input).filterNot { dependency => matches(dependency) }
      assert(input.forall(_.exclusions.isEmpty))
      assert(excluded.filter(matches).forall(_.exclusions.exists(rule => rule.organization == "t" && rule.name == "child")))
      assert(excluded.filterNot(matches) == input.filterNot(matches))
      assert(removed.size == variants.size && removed.toSet == variants.toSet)
    }
    println("ANALYZER_SBT_WORKFLOW_PASS")
  }
)
lazy val api = Project("core-api", file("different-directory")).settings(
  moduleName := "published-api", organization := "fixture.api"
)
lazy val browser = Project("coreJS", file("browser-directory")).settings(moduleName := "same-cross-name")
lazy val server = Project("coreJVM", file("server-directory")).settings(moduleName := "same-cross-name")
lazy val detached = Project("detached-tools", file("unaggregated-directory")).settings(
  moduleName := "published-detached", organization := "fixture.detached"
)
'''.replace("__EXTERNAL_URI__", uri))
reference = f'ProjectRef(uri("{uri}"), "project1Root")'
selector = f"{{{uri}}}project1Root"
commands = [
    "projects",
    'eval println("ANALYZER_MODULE_NAMES_BEGIN")',
    f"show {{{work.resolve().as_uri()}/}}root / moduleName",
    f"show {{{work.resolve().as_uri()}/}}detached-tools / moduleName",
    f"show {{{child.resolve().as_uri()}/}}core-api / moduleName",
    'eval println("ANALYZER_MODULE_NAMES_END")',
    'set LocalProject("core-api") / update / skip := true',
    f'set {reference} / update / skip := true',
    "session list",
    'set LocalProject("core-api") / update / skip := false',
    'set LocalProject("core-api") / csrConfiguration := (LocalProject("core-api") / csrConfiguration).value.withTtl(Some(scala.concurrent.duration.Duration.Zero))',
    f'set {reference} / update / skip := false',
    f'set {reference} / csrConfiguration := ({reference} / csrConfiguration).value.withTtl(Some(scala.concurrent.duration.Duration.Zero))',
    "core-api / update", f"{selector} / update",
    "core-api / Compile / dependencyDot", "core-api / Test / dependencyDot", "core-api / Provided / dependencyDot",
    "detached-tools / Compile / dependencyDot",
    f"{selector} / Compile / dependencyDot", f"{selector} / Test / dependencyDot",
    f"{{{uri}}}core-api / Compile / dependencyDot",
    "session remove 6", "session remove 5", "session remove 4", "session remove 3", "session list", "verifyWorkflow",
]
expected = [
    (work / "different-directory", ("compile", "test", "provided"), "fixture.api:published-api:1.0.0"),
    (work / "unaggregated-directory", ("compile",), "fixture.detached:published-detached:1.0.0"),
    (child, ("compile", "test"), "fixture.external:external-published:1.0.0"),
    (child / "duplicate-directory", ("compile",), "fixture.external:external-duplicate:1.0.0"),
]
for directory, _, _ in expected:
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
names_output = text.split("ANALYZER_MODULE_NAMES_BEGIN", 1)[1].split("ANALYZER_MODULE_NAMES_END", 1)[0]
for name in ("published-root", "published-api", "external-published", "published-detached", "external-duplicate"):
    assert name in names_output, f"Missing moduleName result: {name}"
assert names_output.count("same-cross-name") == 2, "Platform projects must retain separate equal publication names"
assert 'ProjectRef(uri("' in names_output, "External aggregate output must retain a build-qualified heading"
for directory, scopes, coordinate in expected:
    for scope in scopes:
        dot = directory / "target" / f"dependencies-{scope}.dot"
        if not dot.is_file() or coordinate not in dot.read_text():
            raise SystemExit(f"Missing or incorrect DOT output: {dot}")
runtime = "fixture.runtime:runtime-only:1.0.0"
assert runtime in (child / "target/dependencies-test.dot").read_text(), "External Runtime dependency missing from Test graph"
assert runtime not in (child / "target/dependencies-compile.dot").read_text(), "Runtime leaked into Compile graph"
print(f"PASS real sbt aggregated module names, RootProject/scoped queries, duplicate IDs, seven DOT outputs, Runtime scope and session cleanup ({time.monotonic() - started:.2f}s)")
