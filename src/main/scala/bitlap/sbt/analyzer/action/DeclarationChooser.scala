package bitlap.sbt.analyzer.action

import javax.swing.{ JComboBox, JComponent, JLabel, JPanel }

import bitlap.sbt.analyzer.SbtDependencyAnalyzerBundle
import bitlap.sbt.analyzer.parsing.DependencyDeclaration

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper

/** Selects among ambiguous source declarations with a bounded, keyboard-accessible dialog. */
object DeclarationChooser {

  /** Returns the only candidate directly, shows a dialog for multiple candidates, or returns None on cancel/empty
   *  input. Call on the EDT because the ambiguous case creates a modal dialog.
   */
  def choose(
    project: Project,
    title: String,
    declarations: List[DependencyDeclaration]
  ): Option[DependencyDeclaration] = {
    if (declarations.isEmpty) return None
    if (declarations.size == 1) return declarations.headOption
    val choices = new JComboBox[String](declarations.map(_.choiceLabel).toArray)
    choices.setPrototypeDisplayValue(" " * 100)
    val dialog = new DialogWrapper(project) {
      setTitle(title)
      override def createCenterPanel(): JComponent = {
        val panel = new JPanel(new java.awt.BorderLayout(0, 8))
        panel.add(new JLabel(SbtDependencyAnalyzerBundle.message("analyzer.edit.choose")), java.awt.BorderLayout.NORTH)
        panel.add(choices, java.awt.BorderLayout.CENTER)
        panel
      }
      init()
    }
    if (dialog.showAndGet()) declarations.lift(choices.getSelectedIndex) else None
  }
}
