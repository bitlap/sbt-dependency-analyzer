package bitlap
package sbt
package analyzer
package task

import bitlap.sbt.analyzer.model.*
import bitlap.sbt.analyzer.parsing.*
import bitlap.sbt.analyzer.util.DependencyUtils.*

import org.jetbrains.plugins.scala.project.ModuleExt

import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.dependencies.DependencyScopeNode
import com.intellij.openapi.project.Project

/** Handles the `sbt dependencyDot` command. Upon completion, parses the resulting file content via a callback. */
final class DependencyDotTask extends SbtShellDependencyAnalysisTask:

  override val dependencyGraphType: DependencyGraphType = DependencyGraphType.Dot

  override def executeCommand(
    project: Project,
    moduleData: ModuleData,
    scope: DependencyScopeEnum,
    organization: String,
    moduleNamePaths: Map[String, String],
    ideaModuleIdSbtModules: Map[String, String]
  ): DependencyScopeNode =
    val module   = findModule(project, moduleData)
    val moduleId = moduleData.getId.split(" ")(0)

    taskCompleteCallback(project, moduleData, scope) { file =>
      val sbtModuleNameMap =
        if (ideaModuleIdSbtModules.isEmpty) Map(moduleId -> module.getName)
        else ideaModuleIdSbtModules
      DependencyGraphFactory
        .getInstance(dependencyGraphType)
        .buildDependencyTree(
          AnalyzerContext(
            file,
            moduleId,
            scope,
            organization,
            moduleNamePaths,
            sbtModuleNameMap
          ),
          createRootScopeNode(scope, project)
        )
    }
  end executeCommand

end DependencyDotTask
