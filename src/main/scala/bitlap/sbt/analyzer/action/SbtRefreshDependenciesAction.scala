package bitlap
package sbt
package analyzer
package action

import bitlap.sbt.analyzer.util.SbtReimportProject

import com.intellij.openapi.actionSystem.AnActionEvent

final class SbtRefreshDependenciesAction extends BaseRefreshDependenciesAction:

  override lazy val eventText: String = SbtDependencyAnalyzerBundle.message("analyzer.refresh.dependencies.text")

  override lazy val eventDescription: String =
    SbtDependencyAnalyzerBundle.message("analyzer.refresh.dependencies.description")

  override def actionPerformed(e: AnActionEvent): Unit = {
    val project = e.getProject
    if (project != null) bitlap.sbt.analyzer.task.SbtTaskRunner.getInstance(project).run(eventText) {
      SbtReimportProject.ReimportProjectPublisher.onReimportProject(project)
    }
  }

end SbtRefreshDependenciesAction
