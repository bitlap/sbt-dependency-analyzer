package bitlap
package sbt
package analyzer
package activity

import org.jetbrains.plugins.scala.project.Version

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Manually opens release notes for the installed plugin version, including during indexing. */
final class WhatsNewAction extends DumbAwareAction {

  getTemplatePresentation.setText(
    SbtDependencyAnalyzerBundle.message("analyzer.action.whatsNew.text", "Sbt Dependency Analyzer")
  )

  override def actionPerformed(e: AnActionEvent): Unit = {
    WhatsNew.browse(Version(SbtDependencyAnalyzerPlugin.details.getVersion), e.getProject)
  }
}
