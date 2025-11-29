package bitlap.sbt.analyzer

import bitlap.sbt.analyzer.model.AnalyzerContext
import bitlap.sbt.analyzer.parsing.{ DependencyGraphFactory, DependencyGraphType }

import org.scalatest.flatspec.AnyFlatSpec

import com.intellij.openapi.externalSystem.model.project.dependencies.*

class DependencyGraphParserSpec extends AnyFlatSpec {

  "parse dot file" should "convert to object successfully " in {
    val start = System.currentTimeMillis()

    val root = new DependencyScopeNode(
      0,
      "compile",
      "compile",
      "compile"
    )
    root.setResolutionState(ResolutionState.RESOLVED)

    val ctx =
      AnalyzerContext(
        getClass.getClassLoader.getResource("test.dot").getFile,
        "star-authority-protocol",
        DependencyScopeEnum.Compile,
        "fc.xuanwu.star",
        moduleNamePathsCache = Map.empty,
        moduleIdArtifactIdsCache = Map.empty,
        isTest = true
      )

    val relations = DependencyGraphFactory
      .getInstance(DependencyGraphType.Dot)
      .buildDependencyTree(ctx, root)

    println(s"analyze dot cost:${System.currentTimeMillis() - start}ms")

    assert(relations.getDependencies.size() > 0)
  }
}
