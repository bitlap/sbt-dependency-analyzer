package bitlap.sbt.analyzer.parsing

import bitlap.sbt.analyzer.model.*

import com.intellij.openapi.externalSystem.model.project.dependencies.*

/** Parses one generated dependency graph and appends its dependency tree to the supplied scope root. */
trait DependencyGraphParser {

  val dependencyGraphType: DependencyGraphType

  /** Populates and returns root from the file and identity metadata in context; malformed graphs may throw. */
  def buildDependencyTree(
    context: AnalyzerContext,
    root: DependencyScopeNode
  ): DependencyScopeNode

}
