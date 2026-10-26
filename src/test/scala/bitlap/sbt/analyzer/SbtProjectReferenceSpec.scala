package bitlap.sbt.analyzer

import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.model.AnalyzerContext
import bitlap.sbt.analyzer.parsing.{ DotDependencyGraphParser, DotFileCache }
import bitlap.sbt.analyzer.util.SbtProjectReference

import org.scalatest.funsuite.AnyFunSuite

import com.intellij.openapi.externalSystem.model.project.dependencies.{ DependencyScopeNode, ProjectDependencyNode }

class SbtProjectReferenceSpec extends AnyFunSuite {
  private val parent = SbtProjectReference("file:/analyzer-fixture/main/", "root")
  private val child  = SbtProjectReference("file:/analyzer-fixture/external%20build/", "root")

  test("build URI and project ID form the identity, even when IDs are equal") {
    assert(parent != child && parent.selector != child.selector)
    val names = Map(parent.selector -> "parent-published", child.selector -> "child-published")
    assert(names.size == 2 && names(child.selector) == "child-published")
    assert(SbtProjectReference("file:///analyzer-fixture/main/./", "root") == parent)
    assert(SbtProjectReference.fromSelector(child.selector).contains(child))
    assert(child.selector.contains("external%20build"))
  }

  test("set commands use ProjectRef for qualified projects and preserve existing local/root commands") {
    assert(SbtProjectReference.settingScope(child.selector) == child.settingReference + " / ")
    assert(child.settingReference.startsWith("ProjectRef(uri(\"file:"))
    assert(child.settingReference.contains("external%20build/\"), \"root\")"))
    assert(SbtProjectReference.settingScope("core-js") == "LocalProject(\"core-js\") / ")
    assert(SbtProjectReference.settingScope("") == "")
  }

  test("projects output distinguishes loaded builds and recognizes both current and root markers") {
    val output = """[info] welcome to sbt
      |[info] In file:/analyzer-fixture/main/
      |[info]     * root
      |[info]       shared
      |[info] In file:/analyzer-fixture/external%20build/
      |[info]     (*)root
      |[info]       shared
      |[success] Total time: 0 s
      |""".stripMargin
    val refs = SbtProjectReference.loadedProjects(output)
    assert(
      refs == Set(
        parent,
        child,
        SbtProjectReference(parent.buildUri.toString, "shared"),
        SbtProjectReference(child.buildUri.toString, "shared")
      )
    )
    assert(!refs.contains(SbtProjectReference("file:/analyzer-fixture/unlinked/", "root")))
  }

  test("remote build URIs retain their scheme and fragment") {
    val git = SbtProjectReference("git:https://example.org/build.git#v1", "core")
    assert(SbtProjectReference.fromSelector(git.selector).contains(git))
    assert(SbtProjectReference.loadedProjects(s"[info] In ${git.buildUri}\n[info]   (*)core") == Set(git))
  }

  test("invalid project selectors and command separators are rejected before sending shell commands") {
    for (id <- Seq("", "root; reload", "root / update", "a\"b", "a\nb"))
      intercept[IllegalArgumentException](SbtProjectReference("file:/build/", id))
    assert(SbtProjectReference.fromSelector("{relative/path}root").isEmpty)
    intercept[IllegalArgumentException](SbtProjectReference.settingScope("{broken"))
    assert(SbtProjectReference.loadedProjects("[info] In not-an-absolute-uri\n[info] root").isEmpty)
  }

  test("DOT analysis selects the external module and maps same-ID project dependencies by build") {
    val file = Files.createTempFile("root-project-144-", ".dot")
    try {
      Files.writeString(
        file,
        """digraph dependencies {
        |"fixture:child-published:1" -> "fixture:parent-published:1";
        |"fixture:parent-published:1" -> "external:library:1";
        |}""".stripMargin
      )
      val context = AnalyzerContext(
        file.toString,
        child.selector,
        DependencyScopeEnum.Compile,
        "fixture",
        moduleNamePathsCache = Map(parent.selector -> "/main", child.selector -> "/external"),
        moduleIdArtifactIdsCache = Map(parent.selector -> "parent-published", child.selector -> "child-published"),
        modulePlatforms = Map(parent.selector -> "jvm", child.selector -> "jvm"),
        isTest = true,
        dotCache = new DotFileCache()
      )
      val result = new DotDependencyGraphParser()
        .buildDependencyTree(context, new DependencyScopeNode(-1, "compile", "compile", "compile"))
      val dependency = result.getDependencies.get(0).asInstanceOf[ProjectDependencyNode]
      assert(dependency.getProjectPath == "/main")
      assert(dependency.getDependencies.asScala.map(_.getDisplayName).toList == List("external:library:1"))
    } finally Files.deleteIfExists(file)
  }
}
