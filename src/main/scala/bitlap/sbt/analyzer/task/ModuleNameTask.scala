package bitlap.sbt.analyzer.task

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.util.DependencyUtils

import org.jetbrains.plugins.scala.extensions.*
import org.jetbrains.sbt.SbtUtil

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

/** Queries the effective sbt moduleName for imported projects loaded in the active shell. moduleName is the base
 *  publication name, independent of project ID, IDE display name and directory; published artifact names may
 *  additionally include cross-version suffixes.
 */
final class ModuleNameTask extends SbtShellOutputAnalysisTask[Map[String, String]] {
  import SbtShellOutputAnalysisTask.*

  /** Queries one project selector directly, including unaggregated projects and build-qualified external references.
   *  Throws if the final info line does not contain a non-empty value.
   */
  def executeForModule(project: Project, moduleId: String): String = {
    val lines = getCommandOutputLines(project, s"$moduleId / moduleName")
    lines.lastOption.collect { case SHELL_OUTPUT_RESULT_REGEX(_, _, value) => value.trim }
      .filter(_.nonEmpty)
      .getOrElse(throw new IllegalStateException(s"No moduleName returned for $moduleId"))
  }

  override def executeCommand(project: Project): Map[String, String] = {
    val loaded = new LoadedSbtProjectsTask().executeCommand(project)
    val ids    = inReadAction {
      ModuleManager
        .getInstance(project)
        .getModules
        .toSeq
        .filterNot(DependencyUtils.canIgnoreModule)
        .flatMap(SbtUtil.getSbtProjectUriAndId)
        .map(ref => bitlap.sbt.analyzer.util.SbtProjectReference(ref.uri, ref.id))
        .filter(loaded.contains)
        .map(_.selector)
        .distinct
    }
    ids.map(id => id -> executeForModule(project, id)).toMap
  }
}
