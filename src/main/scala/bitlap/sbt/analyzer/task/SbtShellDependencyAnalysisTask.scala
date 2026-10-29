package bitlap
package sbt
package analyzer
package task

import java.nio.file.Path

import org.jetbrains.sbt.shell.communication.{
  SbtShellCommandEventProcessor,
  SbtShellCommandRequest,
  SbtShellCommandSubmitter,
  ShellEvent
}

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
    val moduleId = sbtProjectSelector(project, moduleData)
    val file     = moduleData.getLinkedExternalProjectPath + analysisFilePath(scope, dependencyGraphType)

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
      SbtShellCommandSubmitter
        .instance(project)
        .run(SbtShellDependencyAnalysisTask.commandRequest(moduleId, scope, dependencyGraphType))
        .map(_.foreach(error => throw error))
    }

    SbtTaskRunner.await(project, result)
    buildNodeFunc(file)
  }

end SbtShellDependencyAnalysisTask

object SbtShellDependencyAnalysisTask:

  /** The shell future completes at the command boundary; retain the first reported failure until then. */
  private[analyzer] def commandRequest(
    moduleId: String,
    scope: DependencyScopeEnum,
    graphType: DependencyGraphType
  ): SbtShellCommandRequest[Option[AnalyzerException]] = {
    def unknown = AnalyzerCommandUnknownException(
      graphType.cmd,
      moduleId,
      scope,
      SbtDependencyAnalyzerBundle.message("analyzer.task.error.title")
    )
    val processor = new SbtShellCommandEventProcessor[Option[AnalyzerException]] {
      override def initialResult: Option[AnalyzerException]                                                  = None
      override def process(current: Option[AnalyzerException], event: ShellEvent): Option[AnalyzerException] =
        current.orElse {
          event match {
            case ShellEvent.ErrorWaitForInput | ShellEvent.ProcessTerminated => Some(unknown)
            case ShellEvent.Output(line) if line.startsWith(ERROR_PREFIX)    =>
              if (line.contains(graphType.cmd))
                Some(AnalyzerCommandNotFoundException(SbtDependencyAnalyzerBundle.message("analyzer.task.error.title")))
              else Some(unknown)
            case _ => None
          }
        }
    }
    SbtShellCommandRequest(getScopedCommandKey(moduleId, scope, graphType.cmd), processor)
  }
  private val ERROR_PREFIX                                   = "[error]"
  lazy val dependencyDotTask: SbtShellDependencyAnalysisTask = new DependencyDotTask

end SbtShellDependencyAnalysisTask
