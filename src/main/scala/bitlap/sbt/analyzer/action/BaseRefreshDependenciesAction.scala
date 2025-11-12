package bitlap.sbt.analyzer.action

import java.util

import com.intellij.openapi.actionSystem.{ ActionUpdateThread, AnActionEvent }
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.model.*
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.project.*

abstract class BaseRefreshDependenciesAction extends DumbAwareAction() {

  lazy val eventText: String
  lazy val eventDescription: String

  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT

  protected def getSystemIds(e: AnActionEvent): util.ArrayList[ProjectSystemId] = {
    val systemIds        = new util.ArrayList[ProjectSystemId]
    val externalSystemId = e.getData(ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID)
    if (externalSystemId == null)
      ExternalSystemManager.EP_NAME
        .forEachExtensionSafe((manager: ExternalSystemManager[?, ?, ?, ?, ?]) => systemIds.add(manager.getSystemId))
    else systemIds.add(externalSystemId)
    systemIds
  }

  override def update(e: AnActionEvent): Unit = {
    val project: Project = e.getProject
    if (project == null) {
      e.getPresentation.setEnabled(false)
      return
    }
    val systemIds: util.List[ProjectSystemId] = getSystemIds(e)
    if (systemIds.isEmpty) {
      e.getPresentation.setEnabled(false)
      return
    }
    e.getPresentation.setText(eventText)
    e.getPresentation.setDescription(eventDescription)
    val processingManager: ExternalSystemProcessingManager =
      ApplicationManager.getApplication.getService(classOf[ExternalSystemProcessingManager])
    e.getPresentation.setEnabled(
      !processingManager.hasTaskOfTypeInProgress(ExternalSystemTaskType.RESOLVE_PROJECT, project)
    )
  }

}
