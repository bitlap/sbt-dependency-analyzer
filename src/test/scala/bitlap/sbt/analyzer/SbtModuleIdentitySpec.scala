package bitlap.sbt.analyzer

import bitlap.sbt.analyzer.util.SbtModuleIdentity

import org.scalatest.funsuite.AnyFunSuite

/** Checks artifact-to-project selection with imported platform metadata and ambiguous module names. */
class SbtModuleIdentitySpec extends AnyFunSuite {

  test("published moduleName can differ from sbt ID directory and IDEA display name") {
    assert(
      SbtModuleIdentity
        .projectIdForArtifact("published-lib_3", Map("actual-id" -> "published-lib"), Map.empty)
        .contains("actual-id")
    )
  }

  test("platform metadata disambiguates arbitrary project IDs sharing moduleName") {
    val names     = Map("browser-app" -> "core", "server-app" -> "core", "cli-app" -> "core")
    val platforms = Map("browser-app" -> "js", "server-app" -> "jvm", "cli-app" -> "native")
    assert(SbtModuleIdentity.projectIdForArtifact("core_sjs1_3", names, platforms).contains("browser-app"))
    assert(SbtModuleIdentity.projectIdForArtifact("core_3", names, platforms).contains("server-app"))
    assert(SbtModuleIdentity.projectIdForArtifact("core_native0.5_2.13", names, platforms).contains("cli-app"))
  }

  test("ambiguous published names never choose an arbitrary map entry") {
    val names = Map("a" -> "core", "b" -> "core")
    assert(SbtModuleIdentity.projectIdForArtifact("core_3", names, Map.empty).isEmpty)
    assert(SbtModuleIdentity.projectIdForArtifact("core_3", names, Map("a" -> "jvm", "b" -> "jvm")).isEmpty)
  }

  test("a dependency from another platform cannot be mapped to the only project") {
    assert(
      SbtModuleIdentity.projectIdForArtifact("core_sjs1_3", Map("server" -> "core"), Map("server" -> "jvm")).isEmpty
    )
  }

  test("unrelated and similarly named modules do not match") {
    assert(SbtModuleIdentity.projectIdForArtifact("core-extra_3", Map("core" -> "core"), Map.empty).isEmpty)
  }

  test("a moduleName ending in a Scala-like suffix remains part of the published base name") {
    assert(SbtModuleIdentity.projectIdForArtifact("custom_3_3", Map("id" -> "custom_3"), Map.empty).contains("id"))
    assert(
      SbtModuleIdentity
        .projectIdForArtifact("custom_3_sjs1_3", Map("id" -> "custom_3"), Map("id" -> "js"))
        .contains("id")
    )
  }

  test("source set platforms cannot be overwritten by an aggregator default in either iteration order") {
    val evidence =
      Seq("jvm-id" -> "jvm", "js-id" -> "js", "js-id" -> "jvm", "native-id" -> "native", "native-id" -> "jvm")
    val expected = Map("jvm-id" -> "jvm", "js-id" -> "js", "native-id" -> "native")
    assert(SbtModuleIdentity.platformsByProject(evidence) == expected)
    assert(SbtModuleIdentity.platformsByProject(evidence.reverse) == expected)
    val ambiguous = SbtModuleIdentity.platformsByProject(Seq("id" -> "js", "id" -> "native"))
    assert(ambiguous.get("id").contains("ambiguous"))
    assert(SbtModuleIdentity.projectIdForArtifact("core_3", Map("id" -> "core"), ambiguous).isEmpty)
  }

  test("ambiguous project mapping retains artifact resolution and its dependency subtree") {
    import bitlap.sbt.analyzer.model.AnalyzerContext
    import bitlap.sbt.analyzer.util.DependencyUtils
    import com.intellij.openapi.externalSystem.model.project.dependencies.*
    val root     = new DependencyScopeNode(1, "compile", "compile", "compile")
    val artifact = new ArtifactDependencyNodeImpl(2, "g", "core_3", "1")
    artifact.setResolutionState(ResolutionState.RESOLVED)
    val child = new ArtifactDependencyNodeImpl(3, "other", "child", "1")
    artifact.getDependencies.add(child)
    val context = AnalyzerContext(
      "",
      "root",
      DependencyScopeEnum.Compile,
      "g",
      moduleNamePathsCache = Map("a" -> "/a", "b" -> "/b"),
      moduleIdArtifactIdsCache = Map("a" -> "core", "b" -> "core"),
      isTest = true
    )
    DependencyUtils.appendChildrenAndFixProjectNodes(root, Seq(artifact), context)
    val result = root.getDependencies.get(0)
    assert(result.isInstanceOf[ArtifactDependencyNode] && result.getResolutionState == ResolutionState.RESOLVED)
    assert(result.getDependencies.size == 1 && result.getDependencies.get(0) == child)
  }

  test("circe cross platform module nodes retain DOT resolution state after project conversion") {
    import bitlap.sbt.analyzer.model.AnalyzerContext
    import bitlap.sbt.analyzer.util.DependencyUtils
    import com.intellij.openapi.externalSystem.model.project.dependencies.*
    for (
      name  <- Seq("circe-generic", "circe-jawn", "circe-numbers");
      state <- Seq(ResolutionState.RESOLVED, ResolutionState.UNRESOLVED)
    ) {
      val root     = new DependencyScopeNode(1, "compile", "compile", "compile")
      val artifact = new ArtifactDependencyNodeImpl(2, "io.circe", name + "_2.13", "0.14.10")
      artifact.setResolutionState(state)
      val names     = Map("server" -> name, "browser" -> name, "native" -> name)
      val platforms = SbtModuleIdentity.platformsByProject(
        Seq("browser" -> "js", "native" -> "native", "server" -> "jvm", "browser" -> "jvm", "native" -> "jvm")
      )
      val context = AnalyzerContext(
        "",
        "benchmark",
        DependencyScopeEnum.Compile,
        "io.circe",
        moduleNamePathsCache = Map("server" -> "/jvm", "browser" -> "/js", "native" -> "/native"),
        moduleIdArtifactIdsCache = names,
        isTest = true,
        modulePlatforms = platforms
      )
      DependencyUtils.appendChildrenAndFixProjectNodes(root, Seq(artifact), context)
      val result = root.getDependencies.get(0).asInstanceOf[ProjectDependencyNode]
      assert(result.getProjectPath == "/jvm")
      assert(result.getResolutionState == state)
    }
  }

}
