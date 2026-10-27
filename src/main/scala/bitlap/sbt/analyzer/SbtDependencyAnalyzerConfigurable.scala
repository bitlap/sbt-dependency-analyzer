package bitlap.sbt.analyzer

import javax.swing.JComponent

import com.intellij.openapi.options.*
import com.intellij.openapi.project.Project

/** Applies project settings and publishes changes so open analyzer views invalidate their cached models. */
final class SbtDependencyAnalyzerConfigurable(project: Project) extends SearchableConfigurable.Parent.Abstract {

  private var currentPanel: SbtDependencyAnalyzerPanel = null

  private def panel: SbtDependencyAnalyzerPanel = {
    if (currentPanel == null) currentPanel = new SbtDependencyAnalyzerPanel(project)
    currentPanel
  }

  override def getId: String = SbtDependencyAnalyzerPlugin.PLUGIN_ID

  override def getDisplayName: String = SbtDependencyAnalyzerBundle.message("analyzer.settings.page.name")

  override def getHelpTopic: String = "default"

  override def createComponent(): JComponent = panel.mainPanel

  override def isModified: Boolean = panel.isModified

  override def apply(): Unit = panel.apply()

  override def reset(): Unit = panel.reset()

  override def disposeUIResources(): Unit = { currentPanel = null }

  override def buildConfigurables(): Array[Configurable] = Array()
}
