package bitlap
package sbt
package analyzer
package task

import scala.concurrent.*

import org.jetbrains.sbt.shell.SbtShellCommunication

import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.dependencies.DependencyScopeNode
import com.intellij.openapi.project.Project

import model.*
import parsing.*
import util.DependencyUtils.*

/** Handles SBT tasks that require the `addDependencyTreePlugin` to be enabled, which provides dependency tree and
 *  analysis capabilities.
 */
trait SbtShellDependencyAnalysisTask:

  val dependencyGraphType: DependencyGraphType

  def executeCommand(
    project: Project,
    moduleData: ModuleData,
    scope: DependencyScopeEnum,
    organization: String,
    moduleNamePaths: Map[String, String],
    sbtModules: Map[String, String]
  ): DependencyScopeNode

  protected final def taskCompleteCallback(
    project: Project,
    moduleData: ModuleData,
    scope: DependencyScopeEnum
  )(buildNodeFunc: String => DependencyScopeNode): DependencyScopeNode = {
    val shellCommunication = SbtShellCommunication.forProject(project)
    val moduleId           = moduleData.getId.split(" ")(0)
    val promise            = Promise[Boolean]()
    val file               = moduleData.getLinkedExternalProjectPath + analysisFilePath(scope, dependencyGraphType)
    val result             = shellCommunication
      .command(
        getScopedCommandKey(moduleId, scope, dependencyGraphType.cmd),
        new StringBuilder(),
        SbtShellCommunication.listenerAggregator {
          case SbtShellCommunication.TaskComplete =>
            if (!promise.isCompleted) {
              promise.success(true)
            }
          case SbtShellCommunication.ErrorWaitForInput =>
            if (!promise.isCompleted) {
              promise.failure(
                AnalyzerCommandUnknownException(
                  dependencyGraphType.cmd,
                  moduleId,
                  scope,
                  SbtDependencyAnalyzerBundle.message("analyzer.task.error.title")
                )
              )
            }
          case SbtShellCommunication.Output(line) =>
            if (
              line.startsWith(SbtShellDependencyAnalysisTask.ERROR_PREFIX) && line
                .contains(dependencyGraphType.cmd) && !promise.isCompleted
            ) {
              promise.failure(
                AnalyzerCommandNotFoundException(
                  SbtDependencyAnalyzerBundle.message("analyzer.task.error.title")
                )
              )
            } else if (line.startsWith(SbtShellDependencyAnalysisTask.ERROR_PREFIX) && !promise.isCompleted) {
              promise.failure(
                AnalyzerCommandUnknownException(
                  dependencyGraphType.cmd,
                  moduleId,
                  scope,
                  SbtDependencyAnalyzerBundle.message("analyzer.task.error.title")
                )
              )
            }
          case _ =>

        }
      )
      .flatMap(_ => promise.future)

    Await.result(result, Constants.TIMEOUT)
    buildNodeFunc(file)
  }

end SbtShellDependencyAnalysisTask

object SbtShellDependencyAnalysisTask:
  private val ERROR_PREFIX                                   = "[error]"
  lazy val dependencyDotTask: SbtShellDependencyAnalysisTask = new DependencyDotTask

end SbtShellDependencyAnalysisTask
