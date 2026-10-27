package bitlap.sbt.analyzer.parsing

import scala.jdk.CollectionConverters.*

import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** Matches supported project declarations by sbt project ID and selects their applicable settings. IDE display names
 *  and directory names are not project identities; arbitrary runtime project construction is not evaluated.
 */
object SbtProjectResolver {

  /** Statically recognized sbt IDs for a declaration, including conventional cross-project platform projections. */
  final case class Identity(ids: Set[String], cross: Boolean)

  /** Resolves supported project constructors, aliases and receiver chains with cycle and depth guards. Returns None for
   *  declarations whose ID cannot be determined statically.
   */
  def identity(pattern: ScPatternDefinition, seen: Set[PsiElement] = Set.empty): Option[Identity] = {
    if (seen(pattern) || seen.size > 128) return None
    val names = pattern.bindings.map(_.name.stripPrefix("`").stripSuffix("`")).toSet
    def loop(expr: ScExpression): Option[Identity] = expr match {
      case p: ScParenthesisedExpr                                                     => p.innerElement.flatMap(loop)
      case i: ScInfixExpr if Set("in", "dependsOn", "aggregate")(i.operation.refName) => loop(i.left)
      case r: ScReferenceExpression if r.refName == "project"                         => Some(Identity(names, false))
      case r: ScReferenceExpression if Set("jvm", "js", "native")(r.refName)          =>
        val suffix = Map("jvm" -> "JVM", "js" -> "JS", "native" -> "Native")(r.refName)
        r.qualifier.flatMap(loop).map(i => i.copy(ids = i.ids.filter(_.endsWith(suffix))))
      case r: ScReferenceExpression =>
        r.qualifier.flatMap(loop).orElse {
          SbtDeclarationResolver.definition(r).flatMap { target =>
            Option(PsiTreeUtil.getParentOfType(target, classOf[ScPatternDefinition], false))
              .flatMap(identity(_, seen + pattern))
          }
        }
      case c: ScMethodCall =>
        c.getEffectiveInvokedExpr match {
          case r: ScReferenceExpression if r.refName == "Project" =>
            c.argumentExpressions.collectFirst {
              case a: ScAssignment if a.referenceName.contains("id") => a.rightExpression
            }.flatten
              .orElse(c.argumentExpressions.headOption)
              .flatMap(SbtDeclarationResolver.string(_))
              .map(id => Identity(Set(id), false))
          case r: ScReferenceExpression if r.refName == "crossProject" =>
            Some(Identity(names.flatMap(n => Set(n, n + "JVM", n + "JS", n + "Native")), true))
          case r: ScReferenceExpression => r.qualifier.flatMap(loop)
          case inner: ScMethodCall      => loop(inner)
          case _                        => None
        }
      case _ => None
    }
    pattern.expr.flatMap(loop)
  }

  /** Returns top-level pattern definitions with a statically recognized sbt project identity. */
  def projects(file: ScalaFile): Seq[ScPatternDefinition] =
    file.getChildren.toSeq.collect { case p: ScPatternDefinition if identity(p).nonEmpty => p }

  /** Checks the imported sbt ID against recognized declaration IDs, without consulting display names. */
  def matches(pattern: ScPatternDefinition, sbtId: String): Boolean =
    identity(pattern).exists(_.ids(sbtId))

  /** Accepts unowned keys only for the local project file, ThisBuild keys for all projects, and explicit owners whose
   *  declarations match sbtId. Configuration operands do not count as project owners.
   */
  def appliesTo(key: ScExpression, sbtId: String, localFile: Boolean): Boolean = {
    val owners = SbtDeclarationResolver.keyExpressions(key).filter {
      case r: ScReferenceExpression =>
        r.refName != "libraryDependencies" && SbtDeclarationResolver.configuration(r).isEmpty
      case _ => false
    }
    owners.isEmpty && localFile || owners.exists {
      case r: ScReferenceExpression if r.refName == "ThisBuild" => true
      case r: ScReferenceExpression                             =>
        SbtDeclarationResolver
          .definition(r)
          .flatMap(target => Option(PsiTreeUtil.getParentOfType(target, classOf[ScPatternDefinition], false)))
          .exists(matches(_, sbtId))
      case _ => false
    }
  }

  /** Collects common and matching platform settings along the declaration receiver chain. Does not descend into
   *  aggregate/dependsOn arguments, whose settings belong to other projects.
   */
  def settings(pattern: ScPatternDefinition, platform: String): Seq[ScExpression] = {
    def loop(expr: ScExpression, seen: Set[PsiElement]): Seq[ScExpression] = {
      if (seen(expr) || seen.size > 128) return Nil
      val next = seen + expr
      expr match {
        case p: ScParenthesisedExpr   => p.innerElement.toSeq.flatMap(loop(_, next))
        case i: ScInfixExpr           => loop(i.left, next)
        case r: ScReferenceExpression =>
          r.qualifier.toSeq.flatMap(loop(_, next)) ++
            (if (r.qualifier.isEmpty) SbtDeclarationResolver.definition(r).toSeq.flatMap(loop(_, next)) else Nil)
        case c: ScMethodCall =>
          c.getEffectiveInvokedExpr match {
            case r: ScReferenceExpression =>
              val receiver = r.qualifier.toSeq.flatMap(loop(_, next))
              val accepted = r.refName == "settings" || r.refName == platform + "Settings"
              receiver ++ (if (accepted) c.argumentExpressions else Nil)
            case platforms: ScMethodCall =>
              platforms.getEffectiveInvokedExpr match {
                case r: ScReferenceExpression if r.refName == "platformsSettings" =>
                  val enabled = platforms.argumentExpressions.exists(_.getText == platform.capitalize + "Platform") ||
                    (platform == "js" && platforms.argumentExpressions.exists(_.getText == "JSPlatform")) ||
                    (platform == "jvm" && platforms.argumentExpressions.exists(_.getText == "JVMPlatform"))
                  r.qualifier.toSeq.flatMap(loop(_, next)) ++ (if (enabled) c.argumentExpressions else Nil)
                case _ => Nil
              }
            case _ => Nil
          }
        case _ => Nil
      }
    }
    pattern.expr.toSeq.flatMap(loop(_, Set(pattern))).distinct
  }
}
