package bitlap.sbt.analyzer.task

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.util.DependencyUtils

import org.jetbrains.plugins.scala.extensions.*
import org.jetbrains.sbt.SbtUtil

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

/** moduleName is the published artifact name, independent of sbt project ID, IDEA name and directory. */
final class ModuleNameTask extends SbtShellOutputAnalysisTask[Map[String, String]] {
  import SbtShellOutputAnalysisTask.*

  /** Query the imported project ID directly, including projects outside the root aggregate. */
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
