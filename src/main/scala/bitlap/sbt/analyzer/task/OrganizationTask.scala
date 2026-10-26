package bitlap.sbt.analyzer.task

import com.intellij.openapi.project.Project

/** Handles the `sbt organization` command to retrieve the current project's organization, which typically serves as the
 *  groupId for artifacts in dependency management.
 */
final class OrganizationTask extends SbtShellOutputAnalysisTask[String]:

  import SbtShellOutputAnalysisTask.*

  def executeForModule(project: Project, moduleId: String): String = {
    val lines = getCommandOutputLines(project, s"$moduleId / organization")
    lines.lastOption.collect { case SHELL_OUTPUT_RESULT_REGEX(_, _, value) => value.trim }
      .filter(_.nonEmpty)
      .getOrElse(throw new IllegalStateException(s"No organization returned for $moduleId"))
  }

  override def executeCommand(project: Project): String =
    val outputLines = getCommandOutputLines(project, "organization")
    outputLines.lastOption.getOrElse("") match
      case SHELL_OUTPUT_RESULT_REGEX(_, _, org) =>
        org.trim
      case _ => null

  end executeCommand

end OrganizationTask
