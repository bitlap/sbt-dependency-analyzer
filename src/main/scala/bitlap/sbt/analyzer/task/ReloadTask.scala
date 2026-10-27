package bitlap.sbt.analyzer.task

import com.intellij.openapi.project.Project

/** Reloads build definitions in the active sbt shell and propagates shell-reported errors. */
final class ReloadTask extends SbtShellOutputAnalysisTask[Unit]:

  override def executeCommand(project: Project): Unit = getCommandOutputLines(project, "reload")

end ReloadTask
