package bitlap.sbt.analyzer.util.packagesearch

import javax.swing.*

import bitlap.sbt.analyzer.SbtDependencyAnalyzerBundle
import bitlap.sbt.analyzer.parsing.DependencyDeclaration

import org.jetbrains.plugins.scala.lang.psi.api.expr.{ ScArgumentExprList, ScInfixExpr }

import com.intellij.buildsystem.model.unified.UnifiedCoordinates
import com.intellij.openapi.command.WriteCommandAction
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
  after: String,
  psiModification: Option[Long] = None
) {

  /** Applies the reviewed snapshot in one undoable write command after checking that the document is unchanged. */
  def apply(): Unit = {
    declaration.resolve.getOrElse(throw new IllegalStateException("Declaration changed; analyze again"))
    val expression =
      declaration.editExpression.getOrElse(throw new IllegalStateException("Dependency use changed; analyze again"))
    def checkCollection(): Unit = require(
      psiModification.forall(
        _ == com.intellij.psi.util.PsiModificationTracker.getInstance(project).getModificationCount
      ),
      "Build definitions changed while preview was open; retry the operation"
    )
    checkCollection()
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
          checkCollection()
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
   *  and removes the adjacent comma when needed. Shared collections use coordinate predicates at the module reference.
   *  Exclusion wraps the enclosing dependency expression. Unknown, invalid or read-only targets are rejected.
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
    declaration.resolve.getOrElse(throw new IllegalStateException("Declaration is no longer valid"))
    val expression =
      declaration.editExpression.getOrElse(throw new IllegalStateException("Dependency use is no longer valid"))
    val document = PsiDocumentManager.getInstance(project).getDocument(expression.getContainingFile)
    require(document != null && expression.isWritable, "Build file is read-only")
    val before = document.getText
    val ranges = scala.collection.mutable.ListBuffer.empty[(Int, Int, String)]
    if (declaration.collection.nonEmpty) {
      val identity = bitlap.sbt.analyzer.parsing.SbtDeclarationResolver
        .moduleIdentity(declaration.resolve.get._3)
        .getOrElse(throw new IllegalArgumentException("Cannot determine the shared dependency identity safely"))
      val reference = s"(${expression.getText})"
      val lineStart = before.lastIndexOf('\n', expression.getTextRange.getStartOffset - 1) + 1
      val indent    =
        before.substring(lineStart, expression.getTextRange.getStartOffset).takeWhile(c => c == ' ' || c == '\t')
      val predicate = identity.predicate.replace("\n", "\n" + indent + "  ")
      val body      =
        if (remove) predicate
        else
          s"if ($predicate) dependency.exclude(${quoted(selected.getGroupId)}, ${quoted(selected.getArtifactId)}) else dependency"
      val method      = if (remove) "filterNot" else "map"
      val replacement = s"$reference.$method { dependency =>\n$indent  $body\n$indent}"
      ranges += ((expression.getTextRange.getStartOffset, expression.getTextRange.getEndOffset, replacement))
    } else if (remove) {
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
    DependencyEditPlan(
      project,
      declaration,
      before,
      after,
      declaration.collection.map(_ =>
        com.intellij.psi.util.PsiModificationTracker.getInstance(project).getModificationCount
      )
    )
  }

  /** Embeds the IDE diff viewer with syntax and change highlighting; the caller owns its lifetime. */
  private[analyzer] def previewRequest(plan: DependencyEditPlan): com.intellij.diff.requests.SimpleDiffRequest = {
    val factory  = com.intellij.diff.DiffContentFactory.getInstance()
    val fileType = plan.declaration.editExpression.get.getContainingFile.getFileType
    val request  = new com.intellij.diff.requests.SimpleDiffRequest(
      plan.declaration.editLocation,
      factory.create(plan.project, plan.before, fileType),
      factory.create(plan.project, plan.after, fileType),
      SbtDependencyAnalyzerBundle.message("analyzer.edit.before"),
      SbtDependencyAnalyzerBundle.message("analyzer.edit.after")
    )
    request.putUserData(com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY, java.lang.Boolean.TRUE)
    request.putUserData(
      com.intellij.diff.util.DiffUserDataKeysEx.SCROLL_TO_CHANGE,
      com.intellij.diff.util.DiffUserDataKeysEx.ScrollToPolicy.FIRST_CHANGE
    )
    request
  }

  def previewComponent(plan: DependencyEditPlan, parent: com.intellij.openapi.Disposable): JComponent = {
    val request = previewRequest(plan)
    val viewer  = com.intellij.diff.DiffManager.getInstance().createRequestPanel(plan.project, parent, null)
    viewer.setRequest(request)
    viewer.getComponent.setPreferredSize(new java.awt.Dimension(1000, 600))
    viewer.getComponent
  }

  /** Shows the review dialog on the EDT and returns whether the user accepted; does not apply the plan. */
  def preview(plan: DependencyEditPlan, title: String): Boolean = {
    val dialog = new DialogWrapper(plan.project) {
      setTitle(title)
      setOKButtonText(SbtDependencyAnalyzerBundle.message("analyzer.edit.apply"))
      override def createCenterPanel(): JComponent = previewComponent(plan, getDisposable)
      init()
    }
    dialog.showAndGet()
  }
}
