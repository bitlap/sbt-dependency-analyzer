package bitlap.sbt.analyzer.parsing

import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScStringLiteral
import org.jetbrains.plugins.scala.lang.psi.api.expr.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** Reads coordinate operands, never strings collected from unrelated method arguments. References are followed on the
 *  current path only, so cycles stop and repeated uses remain distinct.
 */
object SbtDeclarationResolver {

  final case class Entry(
    core: ScInfixExpr,
    site: ScExpression,
    group: String,
    artifact: String,
    version: String,
    scope: String,
    cross: String,
    editable: Boolean
  )

  private val modifiers = Set(
    "exclude",
    "excludeAll",
    "cross",
    "classifier",
    "artifacts",
    "from",
    "intransitive",
    "force",
    "changing",
    "withSources",
    "withJavadoc",
    "withConfigurations"
  )
  private val configurations = Set("Compile", "Test", "Runtime", "Provided", "Optional", "IntegrationTest")

  def definition(ref: ScReferenceExpression): Option[ScExpression] =
    Option(ref.resolve()).flatMap { resolved =>
      Option(PsiTreeUtil.getParentOfType(resolved, classOf[ScPatternDefinition], false))
        .filter(_.bindings.size == 1)
        .flatMap(_.expr)
    }

  def string(expr: ScExpression, seen: Set[PsiElement] = Set.empty): Option[String] = {
    if (seen.contains(expr) || seen.size > 128) return None
    val next = seen + expr
    expr match {
      case literal: ScStringLiteral if literal.isSimpleLiteral =>
        Option(literal.getValue).collect { case s: String => s }
      case p: ScParenthesisedExpr                       => p.innerElement.flatMap(string(_, next))
      case r: ScReferenceExpression                     => definition(r).flatMap(string(_, next))
      case i: ScInfixExpr if i.operation.refName == "+" =>
        for { a <- string(i.left, next); b <- string(i.right, next) } yield a + b
      case _ => None
    }
  }

  def configuration(expr: ScExpression, seen: Set[PsiElement] = Set.empty): Option[String] = {
    if (seen(expr) || seen.size > 128) return None
    string(expr).orElse(expr match {
      case r: ScReferenceExpression if configurations(r.refName) => Some(r.refName.toLowerCase)
      case r: ScReferenceExpression                              => definition(r).flatMap(configuration(_, seen + expr))
      case call: ScMethodCall                                    =>
        call.getEffectiveInvokedExpr match {
          case r: ScReferenceExpression if Set("config", "Configuration")(r.refName) =>
            call.argumentExpressions.headOption.flatMap(string(_))
          case _ => None
        }
      case _ => None
    })
  }

  private def coordinate(expr: ScExpression, seen: Set[PsiElement]): Option[Entry] = {
    if (seen.contains(expr) || seen.size > 128) return None
    val next = seen + expr
    expr match {
      case p: ScParenthesisedExpr   => p.innerElement.flatMap(coordinate(_, next))
      case r: ScReferenceExpression =>
        definition(r).flatMap(coordinate(_, next)).orElse {
          if (modifiers(r.refName)) r.qualifier.flatMap(coordinate(_, next)) else None
        }
      case call: ScMethodCall =>
        call.getEffectiveInvokedExpr match {
          case r: ScReferenceExpression if modifiers(r.refName) =>
            r.qualifier.flatMap(coordinate(_, next)).map { entry =>
              if (r.refName == "cross") {
                val mode = call.argumentExpressions.headOption.collect { case ref: ScReferenceExpression =>
                  ref.refName
                }
                mode match {
                  case Some("disabled")                                        => entry.copy(cross = "%")
                  case Some("binary" | "full" | "for3Use2_13" | "for2_13Use3") => entry.copy(cross = "%%")
                  case _                                                       => entry
                }
              } else entry
            }
          case _ => None
        }
      case i: ScInfixExpr if i.operation.refName == "%" =>
        coordinate(i.left, next)
          .flatMap(c => configuration(i.right).map(s => c.copy(scope = s)))
          .orElse(i.left match {
            case pair: ScInfixExpr if Set("%", "%%", "%%%")(pair.operation.refName) =>
              for { g <- string(pair.left); a <- string(pair.right); v <- string(i.right) } yield Entry(
                i,
                expr,
                g,
                a,
                v,
                "compile",
                pair.operation.refName,
                true
              )
            case _ => None
          })
      case _ => None
    }
  }

  private def invoked(expr: ScExpression): ScExpression = expr match {
    case g: ScGenericCall => invoked(g.referencedExpr)
    case other            => other
  }

  def dependencies(expression: ScExpression, projectId: Option[String] = None): Seq[Entry] = {
    def loop(expr: ScExpression, seen: Set[PsiElement], editable: Boolean): Seq[Entry] = {
      com.intellij.openapi.progress.ProgressManager.checkCanceled()
      if (seen.contains(expr) || seen.size > 128) return Nil
      val next = seen + expr
      coordinate(expr, Set.empty) match {
        case Some(entry) => Seq(entry.copy(site = expr, editable = editable))
        case None        =>
          expr match {
            case p: ScParenthesisedExpr   => p.innerElement.toSeq.flatMap(loop(_, next, editable))
            case t: ScTypedExpression     => loop(t.expr, next, editable)
            case r: ScReferenceExpression => definition(r).toSeq.flatMap(loop(_, next, false))
            case i: ScInfixExpr if Set("+=", "++=", ":=")(i.operation.refName) && dependencyKey(i.left) =>
              val scopes     = keyExpressions(i.left).flatMap(configuration(_))
              val applicable = projectId.forall(SbtProjectResolver.appliesTo(i.left, _, true))
              (if (applicable) loop(i.right, next, editable) else Nil).map(e =>
                if (scopes.nonEmpty && e.scope == "compile") e.copy(scope = scopes.last) else e
              )
            case i: ScInfixExpr if i.operation.refName == "++" =>
              loop(i.left, next, editable) ++ loop(i.right, next, editable)
            case call: ScMethodCall =>
              invoked(call.getEffectiveInvokedExpr) match {
                case r: ScReferenceExpression
                    if Set("Seq", "List", "Vector", "Set", "settings", "jvmSettings", "jsSettings", "nativeSettings")(
                      r.refName
                    ) =>
                  call.argumentExpressions.flatMap(loop(_, next, editable)).toSeq
                case _ => Nil
              }
            case block: ScBlockExpr => block.exprs.lastOption.toSeq.flatMap(loop(_, next, editable))
            case _                  => Nil
          }
      }
    }
    loop(expression, Set.empty, true).distinctBy(e => (e.site, e.core, e.scope))
  }

  def keyExpressions(expr: ScExpression): Seq[ScExpression] = expr match {
    case i: ScInfixExpr if Set("/", "in")(i.operation.refName) => keyExpressions(i.left) ++ keyExpressions(i.right)
    case p: ScParenthesisedExpr                                => p.innerElement.toSeq.flatMap(keyExpressions)
    case other                                                 => Seq(other)
  }

  def dependencyKey(expr: ScExpression): Boolean = expr match {
    case r: ScReferenceExpression                              => r.refName == "libraryDependencies"
    case i: ScInfixExpr if Set("/", "in")(i.operation.refName) => (
      if (i.operation.refName == "in") dependencyKey(i.left) else dependencyKey(i.right)
    )
    case p: ScParenthesisedExpr => p.innerElement.exists(dependencyKey)
    case _                      => false
  }
}
