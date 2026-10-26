package bitlap.sbt.analyzer.parsing

import org.jetbrains.plugins.scala.lang.psi.api.expr.{ ScExpression, ScInfixExpr }

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.psi.{ PsiElement, SmartPointerManager, SmartPsiElementPointer }

/** A resolved coordinate expression and its enclosing dependency expression, retained through smart PSI pointers.
 *  Resolve again before navigation or edits: pointers can become invalid after source changes.
 *
 *  @param expression
 *    core group/artifact/version infix expression
 *  @param scope
 *    effective configuration inherited from the declaration or its use site
 *  @param container
 *    enclosing expression including scope and chained modifiers
 *  @param editable
 *    false when the use site cannot be rewritten safely
 *  @param cross
 *    original coordinate operator (%, %% or %%%), used for artifact matching
 *  @param collection
 *    ordered shared collection reference and the selected element position, for use-site edits
 */
final case class DependencyDeclaration(
  expression: SmartPsiElementPointer[ScInfixExpr],
  scope: String,
  container: SmartPsiElementPointer[ScExpression],
  editable: Boolean = true,
  cross: String = "%",
  collection: Option[(SmartPsiElementPointer[ScExpression], Int)] = None
) {

  /** Resolves both smart pointers and returns the core expression, effective scope and enclosing expression. Returns
   *  None if either pointer no longer identifies valid PSI.
   */
  def resolve: Option[(ScInfixExpr, String, ScExpression)] = for {
    core  <- Option(expression.getElement).filter(_.isValid)
    outer <- Option(container.getElement).filter(_.isValid)
  } yield (core, scope, outer)

  /** Shared ordered collections are edited at the selected module's reference, while navigation still uses the original
   *  coordinate definition. Invalid reference pointers disable editing rather than choosing another site.
   */
  def editExpression: Option[ScExpression] = collection match {
    case Some((reference, _)) => Option(reference.getElement).filter(_.isValid)
    case None                 => Option(container.getElement).filter(_.isValid)
  }

  def choiceLabel: String = {
    val locations = if (collection.nonEmpty && editLocation != location) s"$location → $editLocation" else location
    s"$locations [$scope] " + resolve.map(_._3.getText.replaceAll("\\s+", " ").take(120)).getOrElse("")
  }

  def location: String     = resolve.map(e => locationOf(e._3)).getOrElse("<invalid>")
  def editLocation: String = editExpression.map(locationOf).getOrElse("<invalid>")

  private def locationOf(expression: ScExpression): String = {
    val document =
      com.intellij.psi.PsiDocumentManager.getInstance(expression.getProject).getDocument(expression.getContainingFile)
    val line = if (document == null) 1 else document.getLineNumber(expression.getTextOffset) + 1
    s"${expression.getContainingFile.getVirtualFile.getPath}:$line"
  }
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
