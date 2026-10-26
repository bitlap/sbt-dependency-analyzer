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

}
