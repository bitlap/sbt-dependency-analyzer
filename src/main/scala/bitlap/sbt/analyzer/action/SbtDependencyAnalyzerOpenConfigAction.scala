package bitlap
package sbt
package analyzer
package action

import bitlap.sbt.analyzer.util.SbtUtils

import org.jetbrains.sbt.project.SbtProjectSystem

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.externalSystem.dependency.analyzer.{
  DependencyAnalyzerDependency as Dependency,
  DependencyAnalyzerView,
  ExternalSystemDependencyAnalyzerOpenConfigAction
}
import com.intellij.openapi.externalSystem.service.settings.ExternalSystemConfigLocator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/** Opens the sbt build configuration for the selected dependency module. Falls back to the first linked sbt build when
 *  the selected module has no locatable configuration file.
 */
final class SbtDependencyAnalyzerOpenConfigAction
    extends ExternalSystemDependencyAnalyzerOpenConfigAction(SbtProjectSystem.Id):

  override def getConfigFile(e: AnActionEvent): VirtualFile = {
    if (e.getProject == null) return null
    val externalSystemConfigPath = getConfigFileOption(Option(getExternalProjectPath(e)))
    // if we cannot find module config, goto root config
    val configPath = externalSystemConfigPath
      .orElse(getConfigFileOption(SbtUtils.getExternalProjectPath(e.getProject).headOption))
      .getOrElse(null)
    if (configPath == null || configPath.isDirectory) null else configPath
  }

  private def getConfigFileOption(externalProjectPath: Option[String]): Option[VirtualFile] = {
    val fileSystem               = LocalFileSystem.getInstance()
    val externalProjectDirectory = externalProjectPath.map(fileSystem.refreshAndFindFileByPath)
    val locator = ExternalSystemConfigLocator.EP_NAME.findFirstSafe(_.getTargetExternalSystemId == SbtProjectSystem.Id)
    if (locator == null) {
      return None
    }

    val externalSystemConfigPath = externalProjectDirectory.toList.filterNot(_ == null).map(locator.adjust)

    externalSystemConfigPath.filter(f => f != null && !f.isDirectory).headOption
  }

  override def getExternalProjectPath(e: AnActionEvent): String =
    var current = e.getData(DependencyAnalyzerView.Companion.getDEPENDENCY)
    while (current != null) {
      current.getData match {
        case module: Dependency.Data.Module =>
          val data = module.getUserData(Module_Data)
          if (data != null) return data.getLinkedExternalProjectPath
        case _ => ()
      }
      current = current.getParent
    }
    null
  end getExternalProjectPath

end SbtDependencyAnalyzerOpenConfigAction
