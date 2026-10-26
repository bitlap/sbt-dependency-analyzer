package bitlap
package sbt
package analyzer
package task

import java.nio.file.Path

import scala.concurrent.*

import org.jetbrains.sbt.shell.SbtShellCommunication

import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.dependencies.DependencyScopeNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil

import model.*
import parsing.*
import util.DependencyUtils.*

/** Runs file-producing dependency tasks against the imported sbt project and configuration. Reuses a fresh output file
 *  within the configured TTL; otherwise invalidates the parsed cache, waits for shell completion and invokes the
 *  parser. Shell work must run outside the EDT.
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
    val moduleId           = sbtProjectSelector(project, moduleData)
    val promise            = Promise[Boolean]()
    val file               = moduleData.getLinkedExternalProjectPath + analysisFilePath(scope, dependencyGraphType)

    AnalyzerCacheService.getInstance(project).trackAnalysisFile(file)
    val ttl  = SettingsState.getSettings(project).fileCacheTimeout.toLong * 1000
    val path = Path.of(file)
    if (
      ttl > 0 && java.nio.file.Files.isRegularFile(path) &&
      System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(path).toMillis < ttl
    )
      return buildNodeFunc(file)
    AnalyzerCacheService.getInstance(project).dotFiles.invalidateCache(file)

    val result = SbtTaskRunner.submit(project) {
      shellCommunication
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
    }

    SbtTaskRunner.await(project, result)
    buildNodeFunc(file)
  }

end SbtShellDependencyAnalysisTask

object SbtShellDependencyAnalysisTask:
  private val ERROR_PREFIX                                   = "[error]"
  lazy val dependencyDotTask: SbtShellDependencyAnalysisTask = new DependencyDotTask

end SbtShellDependencyAnalysisTask
