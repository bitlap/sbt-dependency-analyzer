import scala.sys.process.Process

import org.jetbrains.sbtidea.Keys._
import org.jetbrains.sbtidea.packaging.PackagingKeys.{ packageArtifact, packageMappings }
import org.jetbrains.sbtidea.packaging.artifact.DistBuilder
import org.jetbrains.sbtidea.verifier.FailureLevel
lazy val scala3Version   = "3.9.0"
lazy val graphvizVersion = "0.18.1"

lazy val scalatestVersion      = "3.2.20"
lazy val pluginVerifierVersion = "1.402"
lazy val ktVersion             = "2.3.0"
lazy val jbAnnotVersion        = "26.0.2"

// Override IDEA_BUILD to validate another IntelliJ platform explicitly.
lazy val intellijVersion = sys.env.getOrElse("IDEA_BUILD", "261.25134.95")
lazy val pluginVersion   = "0.11.0"

/** Runs real PSI and Swing checks in an isolated IDE process; results are written under target/smoke. */
lazy val psiTest = taskKey[Unit]("Run isolated Scala PSI and menu regression tests")

ThisBuild / version := pluginVersion

inThisBuild(
  List(
    homepage   := Some(url("https://github.com/bitlap/sbt-dependency-analyzer")),
    developers := List(
      Developer(
        id = "jxnu-liguobin",
        name = "梦境迷离",
        email = "dreamylost@outlook.com",
        url = url("https://blog.dreamylost.cn")
      ),
      Developer(
        id = "IceMimosa",
        name = "IceMimosa",
        email = "chk19940609@gmail.com",
        url = url("http://patamon.me")
      )
    )
  )
)

/** Plugin build, including reproducible packaging and checks against the configured local IntelliJ SDK. */
lazy val `sbt-dependency-analyzer` = (project in file("."))
  .enablePlugins(SbtIdeaPlugin)
  .settings(
    scalaVersion := scala3Version,
    organization := "org.bitlap",
    scalacOptions ++= Seq(
      "-deprecation",
//      "-Xfatal-warnings",
      "-Ybackend-parallelism:16" // https://github.com/scala/scala3/pull/15392
//       "-nowarn", // during migration
//      "-rewrite",
//      "-source:3.7-migration"
    ),
    version                        := (ThisBuild / version).value,
    ThisBuild / intellijPluginName := "Sbt Dependency Analyzer",
    ThisBuild / intellijBuild      := intellijVersion,
    ThisBuild / intellijPlatform   := (Global / intellijPlatform).??(IntelliJPlatform.IdeaCommunity).value,
    signPluginOptions              := signPluginOptions.value.copy(
      enabled = sys.env.get("SIGN_PLUGIN").contains("true"),
      certFile = sys.env.get("PLUGIN_SIGN_CERT_FILE").map(file),
      privateKeyFile = sys.env.get("PLUGIN_SIGN_PRIVATE_KEY_FILE").map(file),
      keyPassphrase = sys.env.get("PLUGIN_SIGN_KEY_PWD")
    ),
    pluginVerifierOptions := pluginVerifierOptions.value.copy(
      version = pluginVerifierVersion, // use a specific verifier version
      offline = true,                  // the runner seeds the exact installed Scala plugin
      failureLevels = Set(FailureLevel.COMPATIBILITY_PROBLEMS, FailureLevel.COMPATIBILITY_WARNINGS)
    ),
    // The upstream incremental packager treats first-seen files as unchanged and retains removed entries.
    // Reassemble only the generated distribution after its normal compile/XML-patching prerequisites.
    packageArtifact := {
      val output   = packageArtifact.value
      val mappings = packageMappings.value
      IO.delete(output)
      new DistBuilder(streams.value, target.value).produceArtifact(mappings)
      output
    },
    psiTest := {
      val compiled = (Test / compile).value
      val packaged = packageArtifact.value
      val sdk      = (ThisBuild / intellijBaseDirectory).value
      val exitCode =
        Process(
          Seq("python3", "tools/run-smoke.py"),
          baseDirectory.value,
          "IDE_HOME"         -> sdk.getAbsolutePath,
          "TEST_CLASSES_DIR" -> (Test / classDirectory).value.getAbsolutePath
        ).!
      if (exitCode != 0) sys.error("Scala PSI/menu regression tests failed; see target/smoke/results/smoke-results.txt")
    },
    // The upstream runner does not validate the report itself. Use the exact installed Scala dependency offline.
    runPluginVerifier := {
      val packaged = packageArtifact.value
      val sdk      = (ThisBuild / intellijBaseDirectory).value
      val exitCode = Process(
        Seq("python3", "tools/verify-plugin.py"),
        baseDirectory.value,
        "IDE_HOME"         -> sdk.getAbsolutePath,
        "PLUGIN_DIR"       -> packaged.getAbsolutePath,
        "VERIFIER_VERSION" -> pluginVerifierOptions.value.version
      ).!
      if (exitCode != 0) sys.error("Plugin verification failed; see target/verifier/offline-reports")
      target.value / "verifier" / "offline-reports"
    },
    ThisBuild / bundleScalaLibrary             := true,
    ThisBuild / autoRemoveOldCachedIntelliJSDK := false,
    Global / intellijAttachSources             := true,
    buildIntellijOptionsIndex                  := {},
    intellijPlugins ++= Seq("com.intellij.java", "org.intellij.scala").map(_.toPlugin),
    useNewVmOptions         := true,
    customIntellijVMOptions := customIntellijVMOptions.value.copy(
      xmx = Some(2048),
      xms = Some(256),
      extraOptions = customIntellijVMOptions.value.extraOptions ++ Seq(
        "--add-exports=java.management/sun.management=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED"
      )
    ),
    Compile / unmanagedResourceDirectories += baseDirectory.value / "src" / "main" / "resources",
    Test / unmanagedResourceDirectories += baseDirectory.value / "src" / "test" / "resources",
    Test / javaOptions += s"-Danalyzer.classes.dir=${(Compile / classDirectory).value.getAbsolutePath}",
    patchPluginXml := pluginXmlOptions.apply { (x: org.jetbrains.sbtidea.pluginXmlOptions) =>
      {
        x.version = pluginVersion
//        x.pluginDescription = IO.read(baseDirectory.value / "src" / "main" / "resources" / "patch" / "description.html")
//        x.changeNotes = IO.read(baseDirectory.value / "src" / "main" / "resources" / "patch" / "change-notes.html")
      }
    },
    publish / skip := true,
    commands ++= Commands.value,
    libraryDependencies ++= Seq(
      "guru.nidi"      % "graphviz-java-min-deps" % graphvizVersion,
      "org.scalatest" %% "scalatest"              % scalatestVersion % Test,
      "org.jetbrains"  % "annotations"            % jbAnnotVersion
    ),
    kotlinVersion := ktVersion,
    Compile / unmanagedSourceDirectories += baseDirectory.value / "src" / "main" / "kotlin",
    packageLibraryMappings ++= Seq(
      "org.jetbrains.kotlin"   % ".*"       % ".*" -> None,
      "org.jetbrains"          % ".*"       % ".*" -> None,
      "org.scala-lang"         % "scala-.*" % ".*" -> None,
      "org.scala-lang.modules" % "scala-.*" % ".*" -> None
    )
  )
