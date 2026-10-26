package bitlap.sbt.analyzer.task

import bitlap.sbt.analyzer.util.SbtProjectReference

import com.intellij.openapi.project.Project

/** Reads the active shell projects inventory, including external builds, and rejects an empty parsed result. Imported
 *  IDE modules alone do not establish that a build is accessible from this shell.
 */
final class LoadedSbtProjectsTask extends SbtShellOutputAnalysisTask[Set[SbtProjectReference]] {

  override def executeCommand(project: Project): Set[SbtProjectReference] = {
    val loaded = SbtProjectReference.loadedProjects(getCommandOutput(project, "projects"))
    require(loaded.nonEmpty, "The active sbt shell did not report any loaded projects")
    loaded
  }
}
