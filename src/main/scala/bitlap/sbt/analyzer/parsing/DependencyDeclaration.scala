package bitlap.sbt.analyzer.parsing

import org.jetbrains.plugins.scala.lang.psi.api.expr.{ ScExpression, ScInfixExpr }

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.psi.{ PsiElement, SmartPointerManager, SmartPsiElementPointer }

final case class DependencyDeclaration(
  expression: SmartPsiElementPointer[ScInfixExpr],
  scope: String,
  container: SmartPsiElementPointer[ScExpression],
  editable: Boolean = true,
  cross: String = "%"
) {

  def resolve: Option[(ScInfixExpr, String, ScExpression)] = for {
    core  <- Option(expression.getElement).filter(_.isValid)
    outer <- Option(container.getElement).filter(_.isValid)
  } yield (core, scope, outer)

  def choiceLabel: String =
    s"$location [$scope] " + resolve.map(_._3.getText.replaceAll("\\s+", " ").take(120)).getOrElse("")

  def location: String = resolve.map { (_, _, outer) =>
    val document =
      com.intellij.psi.PsiDocumentManager.getInstance(outer.getProject).getDocument(outer.getContainingFile)
    val line = if (document == null) 1 else document.getLineNumber(outer.getTextOffset) + 1
    s"${outer.getContainingFile.getVirtualFile.getPath}:$line"
  }.getOrElse("<invalid>")
}

object DependencyDeclaration {
  val Key: DataKey[DependencyDeclaration] = DataKey.create("SbtDependencyAnalyzer.Declaration")

  def apply(tuple: (ScInfixExpr, String, ScExpression)): DependencyDeclaration = {
    val manager = SmartPointerManager.getInstance(tuple._1.getProject)
    DependencyDeclaration(
      manager.createSmartPsiElementPointer(tuple._1),
      tuple._2,
      manager.createSmartPsiElementPointer(tuple._3)
    )
  }
}
