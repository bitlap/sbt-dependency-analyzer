package bitlap.sbt.analyzer.action

import bitlap.sbt.analyzer.*
import bitlap.sbt.analyzer.task.*
import bitlap.sbt.analyzer.util.SbtReimportProject

import com.intellij.openapi.actionSystem.AnActionEvent

final class SbtRefreshSnapshotDependenciesAction extends BaseRefreshDependenciesAction:

  override lazy val eventText: String =
    SbtDependencyAnalyzerBundle.message("analyzer.refresh.snapshot.dependencies.text")

  override lazy val eventDescription: String =
    SbtDependencyAnalyzerBundle.message("analyzer.refresh.snapshot.dependencies.description")

  override def actionPerformed(e: AnActionEvent): Unit = {
    val project = e.getProject
    if (project == null) return
    var selected =
      e.getData(com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerView.Companion.getDEPENDENCY)
    while (selected != null && selected.getParent != null) selected = selected.getParent
    val moduleId = Option(selected)
      .flatMap(d =>
        d.getData match {
          case module: com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerDependency.Data.Module =>
            Option(module.getUserData(Module_Data)).map(sbtProjectSelector(project, _))
          case _ => None
        }
      )
      .getOrElse("")
    SbtTaskRunner.getInstance(project).run(eventText) {
      new RefreshSnapshotsTask().executeForModule(project, moduleId)
      SbtReimportProject.ReimportProjectPublisher.onReimportProject(project)
    }
  }

end SbtRefreshSnapshotDependenciesAction
