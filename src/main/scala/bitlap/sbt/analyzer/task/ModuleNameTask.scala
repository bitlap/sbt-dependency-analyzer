package bitlap.sbt.analyzer.task

import bitlap.sbt.analyzer.util.{ DependencyUtils, SbtModuleNameQuery, SbtProjectReference }

import org.jetbrains.plugins.scala.extensions.*
import org.jetbrains.sbt.SbtUtil

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

/** Queries effective publication names, reusing aggregated results by build URI and project ID. */
final class ModuleNameTask extends SbtShellOutputAnalysisTask[Map[String, String]] {

  def executeForProjects(
    project: Project,
    references: Seq[SbtProjectReference],
    current: Option[SbtProjectReference],
    preferred: Seq[SbtProjectReference] = Seq.empty,
    cached: Map[String, String] = Map.empty
  ): Map[String, String] =
    SbtModuleNameQuery.collect(references, current, preferred, cached)(getCommandOutput(project, _))

  override def executeCommand(project: Project): Map[String, String] = {
    val inventory  = new LoadedSbtProjectsTask().executeInventory(project)
    val references = inReadAction {
      ModuleManager
        .getInstance(project)
        .getModules
        .toSeq
        .filterNot(DependencyUtils.canIgnoreModule)
        .flatMap(SbtUtil.getSbtProjectUriAndId)
        .map(ref => SbtProjectReference(ref.uri, ref.id))
        .filter(inventory.projects.contains)
        .distinct
    }
    executeForProjects(project, references, inventory.current)
  }
}
