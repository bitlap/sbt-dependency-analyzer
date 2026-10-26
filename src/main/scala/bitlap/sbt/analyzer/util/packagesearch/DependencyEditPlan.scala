package bitlap.sbt.analyzer.util.packagesearch

import javax.swing.*

import bitlap.sbt.analyzer.SbtDependencyAnalyzerBundle
import bitlap.sbt.analyzer.parsing.DependencyDeclaration

import org.jetbrains.plugins.scala.lang.psi.api.expr.{ ScArgumentExprList, ScInfixExpr }

import com.intellij.buildsystem.model.unified.UnifiedCoordinates
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.{ PsiDocumentManager, PsiWhiteSpace }

/** Immutable before/after document snapshot for a reviewed dependency edit. Applying the plan revalidates PSI and the
 *  entire document before an undoable write, preventing a stale preview from overwriting intervening source changes.
 */
final case class DependencyEditPlan(
  project: Project,
  declaration: DependencyDeclaration,
  before: String,
  after: String
) {

  /** Applies the reviewed snapshot in one undoable write command after checking that the document is unchanged. */
  def apply(): Unit = {
    val (_, _, expression) =
      declaration.resolve.getOrElse(throw new IllegalStateException("Declaration changed; analyze again"))
    val manager  = PsiDocumentManager.getInstance(project)
    val document = manager.getDocument(expression.getContainingFile)
    require(
      document != null && document.getText == before,
      "Build file changed while preview was open; retry the operation"
    )
    WriteCommandAction.runWriteCommandAction(
      project,
      new Runnable {
        override def run(): Unit = {
          require(document.getText == before, "Build file changed; retry the operation")
          document.setText(after)
          manager.commitDocument(document)
        }
      }
    )
  }
}

object DependencyEditPlan {
  private def quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** Builds an edit without modifying PSI or the document. Removal supports a += assignment or an argument-list element
   *  and removes the adjacent comma when needed. Exclusion wraps the enclosing dependency expression. Shared, invalid
   *  or read-only targets are rejected.
   *
   *  @param remove
   *    true to remove the declaration; false to exclude selected from that declaration
   */
  def prepare(
    project: Project,
    declaration: DependencyDeclaration,
    selected: UnifiedCoordinates,
    remove: Boolean
  ): DependencyEditPlan = {
    require(declaration.editable, "This declaration is shared; edit its definition directly")
    val (_, _, expression) =
      declaration.resolve.getOrElse(throw new IllegalStateException("Declaration is no longer valid"))
    val document = PsiDocumentManager.getInstance(project).getDocument(expression.getContainingFile)
    require(document != null && expression.isWritable, "Build file is read-only")
    val before = document.getText
    val ranges = scala.collection.mutable.ListBuffer.empty[(Int, Int, String)]
    if (remove) {
      val target = expression.getParent match {
        case assignment: ScInfixExpr
            if bitlap.sbt.analyzer.parsing.SbtDeclarationResolver.dependencyKey(assignment.left) &&
              assignment.operation.refName == "+=" =>
          assignment
        case _: ScArgumentExprList => expression
        case _                     =>
          throw new IllegalArgumentException(
            "Cannot safely remove this shared or computed declaration; edit the build file directly"
          )
      }
      ranges += ((target.getTextRange.getStartOffset, target.getTextRange.getEndOffset, ""))
      if (target.getParent.isInstanceOf[ScArgumentExprList]) {
        def significant(element: com.intellij.psi.PsiElement, forward: Boolean): com.intellij.psi.PsiElement = {
          var next = if (forward) element.getNextSibling else element.getPrevSibling
          while (next.isInstanceOf[PsiWhiteSpace] || next.isInstanceOf[com.intellij.psi.PsiComment])
            next = if (forward) next.getNextSibling else next.getPrevSibling
          next
        }
        val next     = significant(target, true)
        val previous = significant(target, false)
        val comma    =
          if (next != null && next.textMatches(",")) next
          else if (previous != null && previous.textMatches(",")) previous
          else null
        if (comma != null) ranges += ((comma.getTextRange.getStartOffset, comma.getTextRange.getEndOffset, ""))
      }
    } else {
      val replacement =
        s"(${expression.getText}).exclude(${quoted(selected.getGroupId)}, ${quoted(selected.getArtifactId)})"
      ranges += ((expression.getTextRange.getStartOffset, expression.getTextRange.getEndOffset, replacement))
    }
    val after = ranges.sortBy(-_._1).foldLeft(before) { case (text, (start, end, replacement)) =>
      text.take(start) + replacement + text.drop(end)
    }
    DependencyEditPlan(project, declaration, before, after)
  }

  /** Creates read-only before/after views of the entire file for the prepared edit. */
  def previewComponent(plan: DependencyEditPlan): JComponent = {
    def editor(title: String, text: String): JPanel = {
      val area = new JTextArea(text, 25, 48)
      area.setEditable(false)
      area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12))
      area.setCaretPosition(0)
      val panel = new JPanel(new java.awt.BorderLayout())
      panel.add(new JLabel(title), java.awt.BorderLayout.NORTH)
      panel.add(new JScrollPane(area), java.awt.BorderLayout.CENTER)
      panel
    }
    val split = new JSplitPane(
      JSplitPane.HORIZONTAL_SPLIT,
      editor(SbtDependencyAnalyzerBundle.message("analyzer.edit.before"), plan.before),
      editor(SbtDependencyAnalyzerBundle.message("analyzer.edit.after"), plan.after)
    )
    split.setResizeWeight(0.5)
    val panel = new JPanel(new java.awt.BorderLayout())
    panel.add(new JLabel(plan.declaration.location), java.awt.BorderLayout.NORTH)
    panel.add(split, java.awt.BorderLayout.CENTER)
    panel
  }

  /** Shows the review dialog on the EDT and returns whether the user accepted; does not apply the plan. */
  def preview(plan: DependencyEditPlan, title: String): Boolean = {
    val dialog = new DialogWrapper(plan.project) {
      setTitle(title)
      setOKButtonText(SbtDependencyAnalyzerBundle.message("analyzer.edit.apply"))
      override def createCenterPanel(): JComponent = previewComponent(plan)
      init()
    }
    dialog.showAndGet()
  }
}
