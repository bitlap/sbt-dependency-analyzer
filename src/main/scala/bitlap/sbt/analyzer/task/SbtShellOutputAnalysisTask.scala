package bitlap
package sbt
package analyzer
package task

import scala.concurrent.*

import org.jetbrains.sbt.shell.SbtShellCommunication

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/** Base for shell queries whose result is console output. Waits off the EDT with cancellation and timeout checks,
 *  rejects error lines, and optionally retains info lines. Values reflect the active shell, including its temporary
 *  session settings.
 */
trait SbtShellOutputAnalysisTask[T]:
  private val LOG = Logger.getInstance(getClass)

  protected final def getCommandOutput(project: Project, command: String): String = {
    val output = SbtTaskRunner.await(project, SbtShellCommunication.forProject(project).command(command))
    val errors = output.linesIterator.filter(_.startsWith("[error]")).toList
    if (errors.nonEmpty) throw new IllegalStateException(errors.mkString("\n"))
    output
  }

  protected final def getCommandOutputLines(project: Project, command: String): List[String] =
    getCommandOutput(project, command).linesIterator.filter(_.startsWith("[info]")).toList

  def executeCommand(project: Project): T

end SbtShellOutputAnalysisTask

object SbtShellOutputAnalysisTask:

  // moduleName
  final val SHELL_OUTPUT_RESULT_REGEX    = "(\\[info\\])(\\s|\\t)*(.*)".r
  final val MODULE_NAME_INPUT_REGEX      = "(\\[info\\])(\\s|\\t)*(.*)(\\s|\\t)*/(\\s|\\t)*moduleName".r
  final val ROOT_MODULE_NAME_INPUT_REGEX = "(\\[info\\])(\\s|\\t)*moduleName".r

  lazy val sbtModuleNamesTask: SbtShellOutputAnalysisTask[Map[String, String]] = new ModuleNameTask

  lazy val organizationTask: SbtShellOutputAnalysisTask[String] = new OrganizationTask

  lazy val reloadTask: SbtShellOutputAnalysisTask[Unit] = new ReloadTask

  lazy val refreshSnapshotsTask: SbtShellOutputAnalysisTask[Unit] = new RefreshSnapshotsTask

end SbtShellOutputAnalysisTask
