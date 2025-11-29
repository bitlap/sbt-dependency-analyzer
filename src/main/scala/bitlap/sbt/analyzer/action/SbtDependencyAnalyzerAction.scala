package bitlap.sbt.analyzer.action

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.*
import bitlap.sbt.analyzer.jbexternal.*
import bitlap.sbt.analyzer.util.SbtUtils

import org.jetbrains.sbt.project.SbtProjectSystem

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.externalSystem.dependency.analyzer.*
import com.intellij.openapi.externalSystem.model.*
import com.intellij.openapi.externalSystem.model.project.*
import com.intellij.openapi.externalSystem.model.project.dependencies.*
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.view.*
import com.intellij.openapi.module.Module

final class ViewDependencyAnalyzerAction extends AbstractSbtDependencyAnalyzerAction[ExternalSystemNode[?]]:

  getTemplatePresentation.setText(SbtDependencyAnalyzerBundle.message("analyzer.action.name"))
  getTemplatePresentation.setIcon(SbtDependencyAnalyzerIcons.ICON)

  override def getDependencyScope(anActionEvent: AnActionEvent, selectedData: ExternalSystemNode[?]): String =
    val node = selectedData.findDependencyNode(classOf[DependencyScopeNode])
    if (node == null) return null
    node.getScope
  end getDependencyScope

  override def getModule(anActionEvent: AnActionEvent, selectedData: ExternalSystemNode[?]): Module =
    val project = anActionEvent.getProject
    if (project == null) return null
    val node = selectedData.findNode(classOf[ModuleNode])
    if (node == null) return null
    val data = node.getData
    if (data != null) return findModule(project, data)

    val projectNode = selectedData.findNode(classOf[ProjectNode])
    if (projectNode == null) return null
    val projectData = projectNode.getData
    if (projectNode == null) return null
    findModule(project, projectData)
  end getModule

  override def getSelectedData(anActionEvent: AnActionEvent): ExternalSystemNode[?] =
    val module = anActionEvent.getData(ExternalSystemDataKeys.SELECTED_NODES)
    if (module == null) return null
    module.asScala.headOption.orNull
  end getSelectedData

  override def getSystemId(anActionEvent: AnActionEvent): ProjectSystemId = SbtProjectSystem.Id

  override def getDependencyData(
    anActionEvent: AnActionEvent,
    selectedData: ExternalSystemNode[?]
  ): DependencyAnalyzerDependency.Data =
    selectedData.getData match
      case pd: ProjectData => DAModule(pd.getInternalName)
      case md: ModuleData  => DAModule(md.getModuleName)
      case _               =>
        selectedData.getDependencyNode match
          case pdn: ProjectDependencyNode  => DAModule(pdn.getProjectName)
          case adn: ArtifactDependencyNode =>
            val size  = SbtUtils.getLibrarySize(selectedData.getProject, adn.getDisplayName)
            val total =
              SbtUtils.getLibraryTotalSize(selectedData.getProject, adn.getDependencies.asScala.toList)
            SbtDAArtifact(adn.getGroup, adn.getModule, adn.getVersion, size, size + total)

  end getDependencyData

end ViewDependencyAnalyzerAction

final class ProjectViewDependencyAnalyzerAction extends AbstractSbtDependencyAnalyzerAction[Module]:

  getTemplatePresentation.setText(SbtDependencyAnalyzerBundle.message("analyzer.action.name"))
  getTemplatePresentation.setIcon(SbtDependencyAnalyzerIcons.ICON)

  override def getDependencyScope(anActionEvent: AnActionEvent, data: Module): String = null

  override def getModule(anActionEvent: AnActionEvent, selectedData: Module): Module = selectedData

  override def getSelectedData(anActionEvent: AnActionEvent): Module =
    val module = anActionEvent.getData(PlatformCoreDataKeys.MODULE)
    if (module == null) return null
    if (ExternalSystemApiUtil.isExternalSystemAwareModule(SbtProjectSystem.Id, module)) {
      module
    } else null
  end getSelectedData

  override def getSystemId(anActionEvent: AnActionEvent): ProjectSystemId = SbtProjectSystem.Id

  override def getDependencyData(
    anActionEvent: AnActionEvent,
    selectedData: Module
  ): DependencyAnalyzerDependency.Data = DAModule(selectedData.getName)

  override def isEnabledAndVisible(e: AnActionEvent): Boolean = {
    super.isEnabledAndVisible(e)
    && (e.getData(LangDataKeys.MODULE_CONTEXT_ARRAY) != null || !e.isFromContextMenu)
  }

end ProjectViewDependencyAnalyzerAction

final class ToolbarDependencyAnalyzerAction extends BaseDependencyAnalyzerAction():

  getTemplatePresentation.setText(SbtDependencyAnalyzerBundle.message("analyzer.action.name"))
  getTemplatePresentation.setIcon(SbtDependencyAnalyzerIcons.ICON)

  private val viewAction = ViewDependencyAnalyzerAction()

  override def getSystemId(anActionEvent: AnActionEvent): ProjectSystemId = SbtProjectSystem.Id

  override def isEnabledAndVisible(anActionEvent: AnActionEvent): Boolean = true

  override def setSelectedState(dependencyAnalyzerView: DependencyAnalyzerView, anActionEvent: AnActionEvent): Unit =
    viewAction.setSelectedState(dependencyAnalyzerView, anActionEvent)

end ToolbarDependencyAnalyzerAction
