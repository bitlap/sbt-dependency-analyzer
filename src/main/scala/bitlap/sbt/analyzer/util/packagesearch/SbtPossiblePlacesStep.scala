package bitlap
package sbt
package analyzer
package util
package packagesearch

import javax.swing.{ Icon, JComponent }

import org.jetbrains.plugins.scala.extensions
import org.jetbrains.sbt.language.utils.DependencyOrRepositoryPlaceInfo

import com.intellij.ide.wizard.Step
import com.intellij.openapi.project.Project
import com.intellij.ui.scale.JBUIScale

// copy from https://github.com/JetBrains/intellij-scala/tree/idea242.x/scala/integration/packagesearch/src/org/jetbrains/plugins/scala/packagesearch/ui
/** Wizard step for selecting and committing a dependency insertion location. */
private class SbtPossiblePlacesStep(
  wizard: AddDependencyPreviewWizard,
  project: Project,
  fileLines: Seq[DependencyOrRepositoryPlaceInfo]
) extends Step {

  val panel = new SbtPossiblePlacesPanel(project, wizard, fileLines)

  override def _init(): Unit = {
    wizard.setSize(JBUIScale.scale(800), JBUIScale.scale(750))
    panel.myResultList.clearSelection()
    extensions.inWriteAction {
      panel.myCurEditor.getDocument.setText(
        SbtDependencyAnalyzerBundle.message(
          "analyzer.packagesearch.dependency.sbt.select.a.place.from.the.list.above.to.enable.this.preview"
        )
      )
    }
    panel.updateUI()
  }

  override def getComponent: JComponent = panel

  override def _commit(finishChosen: Boolean): Unit = {
    if (finishChosen) {
      wizard.resultFileLine = Option(panel.myResultList.getSelectedValue)
    }
  }

  override def getIcon: Icon = null

  override def getPreferredFocusedComponent: JComponent = panel
}
