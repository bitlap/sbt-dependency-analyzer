package bitlap.sbt.analyzer

import bitlap.sbt.analyzer.action.DeclarationMatcher
import bitlap.sbt.analyzer.util.DependencyUtils

import org.scalatest.funsuite.AnyFunSuite

class DeclarationMatcherSpec extends AnyFunSuite {

  private def matches(
    declared: String,
    target: String,
    cross: String,
    scope: String = "compile",
    targetScope: String = "compile"
  ) =
    DeclarationMatcher.matches("g", declared, "1", scope, "g", target, "1", targetScope, cross)

  test("percent means exact artifact identity, including numeric suffixes") {
    assert(!matches("java_3", "java", "%"))
    assert(!matches("a_2.13", "a_3", "%"))
    assert(matches("java_3", "java_3", "%"))
  }

  test("binary and full Scala cross versions are recognized without dropping platform identity") {
    Seq("a_2.13", "a_2.13.16", "a_3", "a_3.3.6").foreach(a => assert(matches("a", a, "%%")))
    assert(matches("a_3", "a_3_3", "%%"))
    assert(!matches("a_3", "a_3", "%%"))
    assert(!matches("a", "a_sjs1_3", "%%"))
    assert(!matches("a_sjs1", "a_native0.5_3", "%%"))
  }

  test("platform cross dependencies match Scala.js and Native suffixes") {
    Seq("lib_core_sjs1_3", "lib_core_native0.5_2.13", "lib_core_3").foreach(a => assert(matches("lib_core", a, "%%%")))
    assert(!matches("lib_core", "other_core_sjs1_3", "%%%"))
  }

  test("scope mappings preserve custom configuration names containing hyphens") {
    assert(matches("a", "a", "%", "integration-test->test", "integration-test"))
    assert(!matches("a", "a", "%", "integration-test->test", "integration"))
    assert(matches("a", "a", "%", "default", "Compile"))
    assert(matches("a", "a", "%", "compile;test->test", "Test"))
  }

  test("artifact suffix normalization preserves underscores and dots in the base name") {
    assert(DependencyUtils.getArtifactWithoutScalaVersion("my_lib.core_native0.5_2.13") == "my_lib.core")
    assert(DependencyUtils.getArtifactWithoutScalaVersion("my_lib.core_2.13.16") == "my_lib.core")
    assert(DependencyUtils.getArtifactWithoutScalaVersion("my_lib.core_42") == "my_lib.core_42")
  }

  test("exact requested version takes precedence over coordinate-only candidates") {
    assert(DeclarationMatcher.select(List("1", "2"))(_ == "2", _ => true) == List("2"))
  }

  test("evicted dynamic and ranged versions retain all matching declarations for explicit selection") {
    val declarations = List("1.+", "[1,2)")
    assert(DeclarationMatcher.select(declarations)(_ == "1.9", _ => true) == declarations)
  }

  test("version fallback never includes a different artifact or scope") {
    assert(DeclarationMatcher.select(List("other"))(_ => false, _ => false).isEmpty)
  }

}
