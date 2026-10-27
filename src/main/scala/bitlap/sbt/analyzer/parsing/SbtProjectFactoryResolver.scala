package bitlap.sbt.analyzer.parsing

import java.nio.file.Path

import org.jetbrains.plugins.scala.lang.psi.api.base.ScInterpolatedStringLiteral
import org.jetbrains.plugins.scala.lang.psi.api.expr.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ ScFunctionDefinition, ScPatternDefinition }
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** Locates factory-created projects using their imported base directory, without evaluating computed project IDs or
 *  executing Scala. Only recognized project builders, literal paths and statically resolved helper functions are
 *  followed.
 */
object SbtProjectFactoryResolver {
  private case class Origin(directory: Path, cross: Boolean)
  private type Arguments = Map[PsiElement, String]

  private val builders = Set(
    "settings",
    "jvmSettings",
    "jsSettings",
    "nativeSettings",
    "dependsOn",
    "aggregate",
    "enablePlugins",
    "disablePlugins",
    "crossType"
  )

  private def resolved(ref: ScReferenceExpression): Option[PsiElement] =
    Option(ref.resolve()).map(_.getNavigationElement)

  private def string(expr: ScExpression, args: Arguments, seen: Set[PsiElement]): Option[String] = {
    if (seen(expr) || seen.size > 128) return None
    val next = seen + expr
    expr match {
      case p: ScParenthesisedExpr => p.innerElement.flatMap(string(_, args, next))
      case literal: ScInterpolatedStringLiteral if literal.referenceName == "s" =>
        val parts      = literal.getStringParts
        val injections = literal.getInjections.map(string(_, args, next))
        if (parts.size != injections.size + 1 || injections.exists(_.isEmpty)) None
        else Some(parts.head + injections.flatten.zip(parts.tail).map((value, suffix) => value + suffix).mkString)
      case r: ScReferenceExpression =>
        resolved(r)
          .flatMap(args.get)
          .orElse(SbtDeclarationResolver.definition(r).flatMap(string(_, args, next)))
      case i: ScInfixExpr if i.operation.refName == "+" =>
        for { a <- string(i.left, args, next); b <- string(i.right, args, next) } yield a + b
      case _ => SbtDeclarationResolver.string(expr)
    }
  }

  private def body(function: ScFunctionDefinition): Option[ScExpression] =
    Option(PsiTreeUtil.getChildOfType(function, classOf[ScExpression]))

  private def preservesProject(expr: ScExpression, seen: Set[PsiElement] = Set.empty): Boolean = {
    if (seen(expr) || seen.size > 128) return false
    val next = seen + expr
    expr match {
      case p: ScParenthesisedExpr   => p.innerElement.exists(preservesProject(_, next))
      case b: ScBlock               => b.exprs.lastOption.exists(preservesProject(_, next))
      case f: ScFunctionExpr        => f.result.exists(preservesProject(_, next))
      case _: ScUnderscoreSection   => true
      case r: ScReferenceExpression =>
        resolved(r).exists {
          case _: ScParameter                      => true
          case f: ScFunctionDefinition if !next(f) => body(f).exists(preservesProject(_, next + f))
          case _                                   => false
        }
      case c: ScMethodCall =>
        c.getEffectiveInvokedExpr match {
          case r: ScReferenceExpression if builders(r.refName) => r.qualifier.exists(preservesProject(_, next))
          case r: ScReferenceExpression                        =>
            resolved(r).collect { case f: ScFunctionDefinition => f }
              .filterNot(next)
              .flatMap(body)
              .exists(preservesProject(_, next))
          case _ => false
        }
      case _ => false
    }
  }

  private def origin(pattern: ScPatternDefinition, buildRoot: Path): Option[Origin] = {
    def path(expr: ScExpression, args: Arguments, seen: Set[PsiElement]): Option[Path] = expr match {
      case p: ScParenthesisedExpr                             => p.innerElement.flatMap(path(_, args, seen))
      case c: ScMethodCall if c.argumentExpressions.size == 1 =>
        c.getEffectiveInvokedExpr match {
          case r: ScReferenceExpression if r.refName == "file" =>
            string(c.argumentExpressions.head, args, seen).flatMap(value =>
              scala.util.Try(buildRoot.resolve(value).toAbsolutePath.normalize()).toOption
            )
          case _ => None
        }
      case _ => None
    }
    def loop(expr: ScExpression, args: Arguments, seen: Set[PsiElement]): Option[Origin] = {
      if (seen(expr) || seen.size > 128) return None
      com.intellij.openapi.progress.ProgressManager.checkCanceled()
      val next = seen + expr
      expr match {
        case p: ScParenthesisedExpr   => p.innerElement.flatMap(loop(_, args, next))
        case b: ScBlock               => b.exprs.lastOption.flatMap(loop(_, args, next))
        case r: ScReferenceExpression => SbtDeclarationResolver.definition(r).flatMap(loop(_, args, next))
        case c: ScMethodCall          =>
          c.getEffectiveInvokedExpr match {
            case inner: ScMethodCall                                                   => loop(inner, args, next)
            case r: ScReferenceExpression if Set("Project", "CrossProject")(r.refName) =>
              val directory = c.argumentExpressions.collectFirst {
                case a: ScAssignment if a.referenceName.contains("base") => a.rightExpression
              }.flatten.orElse(c.argumentExpressions.lift(1))
              directory.flatMap(path(_, args, next)).map(Origin(_, r.refName == "CrossProject"))
            case r: ScReferenceExpression if r.refName == "in" =>
              for {
                receiver  <- r.qualifier; original <- loop(receiver, args, next)
                directory <- c.argumentExpressions.headOption.flatMap(path(_, args, next))
              } yield original.copy(directory = directory)
            case r: ScReferenceExpression if builders(r.refName) => r.qualifier.flatMap(loop(_, args, next))
            case r: ScReferenceExpression
                if r.refName == "configure" && c.argumentExpressions.forall(preservesProject(_)) =>
              r.qualifier.flatMap(loop(_, args, next))
            case r: ScReferenceExpression =>
              resolved(r).collect { case f: ScFunctionDefinition => f }.filterNot(next).flatMap { function =>
                val named = c.argumentExpressions.collect { case a: ScAssignment =>
                  a.referenceName -> a.rightExpression
                }
                val positional = c.argumentExpressions.filterNot(_.isInstanceOf[ScAssignment])
                val bindings   = function.parameters.zipWithIndex.flatMap { (parameter, index) =>
                  named.collectFirst { case (Some(name), value) if name == parameter.name => value }.flatten
                    .orElse(positional.lift(index))
                    .flatMap(string(_, args, next))
                    .map(parameter -> _)
                }.toMap
                body(function).flatMap(loop(_, args ++ bindings, next + function))
              }
            case _ => None
          }
        case _ => None
      }
    }
    pattern.expr.flatMap(loop(_, Map.empty, Set(pattern)))
  }

  /** Exact sbt IDs take precedence. Factory paths are a fallback only when a single declaration matches across files.
   */
  def select(patterns: Seq[ScPatternDefinition], id: String, base: Path, buildRoot: Path): Seq[ScPatternDefinition] = {
    val exact = patterns.filter(SbtProjectResolver.matches(_, id))
    if (exact.nonEmpty) exact
    else {
      val directory = base.toAbsolutePath.normalize()
      val matches   = patterns.filter(pattern =>
        origin(pattern, buildRoot).exists { value =>
          value.directory == directory || (value.cross && Set("jvm", "js", "native", ".jvm", ".js", ".native")
            .exists(platform => value.directory.resolve(platform) == directory))
        }
      )
      if (matches.size == 1) matches else Nil
    }
  }
}
