package bitlap.sbt.analyzer.task

import bitlap.sbt.analyzer.util.SbtProjectReference

import com.intellij.openapi.project.Project

/** Discover the builds actually loaded by the active shell, rather than guessing from IDEA module directories. */
final class LoadedSbtProjectsTask extends SbtShellOutputAnalysisTask[Set[SbtProjectReference]] {

  override def executeCommand(project: Project): Set[SbtProjectReference] = {
    val loaded = SbtProjectReference.loadedProjects(getCommandOutput(project, "projects"))
    require(loaded.nonEmpty, "The active sbt shell did not report any loaded projects")
    loaded
  }
}
