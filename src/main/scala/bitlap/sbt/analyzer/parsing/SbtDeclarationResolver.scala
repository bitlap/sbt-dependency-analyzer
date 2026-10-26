package bitlap.sbt.analyzer.parsing

import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScStringLiteral
import org.jetbrains.plugins.scala.lang.psi.api.expr.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** Resolves coordinate operands and configuration wrappers from dependency PSI under read access. Follows supported
 *  references without collecting strings from unrelated arguments; arbitrary Scala expressions are not evaluated.
 *  Shared definitions remain navigable but may be marked non-editable.
 */
object SbtDeclarationResolver {

  /** Resolved coordinate with its source definition and use-site expression. The site carries wrappers and inherited
   *  configuration; editable records whether rewriting that site is supported.
   */
  final case class Entry(
    core: ScInfixExpr,
    site: ScExpression,
    group: String,
    artifact: String,
    version: String,
    scope: String,
    cross: String,
    editable: Boolean,
    collection: Option[(ScExpression, Int)] = None
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

  /** Resolves a reference to the initializer of a single-binding pattern definition; other targets yield None. */
  def definition(ref: ScReferenceExpression): Option[ScExpression] =
    Option(ref.resolve()).flatMap { resolved =>
      val source = resolved.getNavigationElement
      Option(PsiTreeUtil.getParentOfType(source, classOf[ScPatternDefinition], false))
        .filter(_.bindings.size == 1)
        // expr may deserialize a detached dummy.scala expression from a stub. Traverse the real AST for source pointers.
        .flatMap(pattern => Option(PsiTreeUtil.getChildOfType(pattern, classOf[ScExpression])))
    }

  /** Resolves simple string literals, references, parentheses and supported string concatenation. Cycles or reference
   *  paths exceeding 128 visited elements yield None.
   */
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

  /** Resolves literal scopes, standard configuration references, aliases and config/Configuration calls. */
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
            r.qualifier.flatMap(coordinate(_, next)).map(modified(_, r.refName, call.argumentExpressions.headOption))
          case _ => None
        }
      case i: ScInfixExpr if modifiers(i.operation.refName) =>
        coordinate(i.left, next).map(modified(_, i.operation.refName, Some(i.right)))
      case p: ScPostfixExpr if modifiers(p.operation.refName) => coordinate(p.operand, next)
      case i: ScInfixExpr if i.operation.refName == "%"       =>
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

  private def modified(entry: Entry, method: String, argument: Option[ScExpression]): Entry = {
    if (method != "cross") entry
    else
      argument.collect { case ref: ScReferenceExpression => ref.refName } match {
        case Some("disabled")                                        => entry.copy(cross = "%")
        case Some("binary" | "full" | "for3Use2_13" | "for2_13Use3") => entry.copy(cross = "%%")
        case _                                                       => entry
      }
  }

  private def invoked(expr: ScExpression): ScExpression = expr match {
    case g: ScGenericCall       => invoked(g.referencedExpr)
    case p: ScParenthesisedExpr => p.innerElement.map(invoked).getOrElse(p)
    case other                  => other
  }

  /** A stable ModuleID identity. Scope and cross version distinguish declarations with otherwise identical coordinates.
   */
  final case class ModuleIdentity(group: String, artifact: String, version: String, scope: String, cross: String) {

    def predicate: String =
      s"""(dependency.organization, dependency.name, dependency.revision,
         |  dependency.configurations.getOrElse("compile"), dependency.crossVersion) ==
         |  (${quote(group)}, ${quote(artifact)}, ${quote(version)}, ${quote(
          scope
        )}, ($cross).crossVersion)""".stripMargin
  }

  private def quote(value: String): String =
    "\"" + value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t") + "\""

  /** Reconstructs a literal ModuleID without copying references whose imports may belong to another build file. */
  def moduleIdentity(expression: ScExpression): Option[ModuleIdentity] = {
    def crossModifier(expr: ScExpression, seen: Set[PsiElement]): Option[String] = {
      if (seen(expr) || seen.size > 128) return None
      val next                                                                                           = seen + expr
      def modifier(name: String, argument: Option[ScExpression], receiver: ScExpression): Option[String] =
        if (name == "withConfigurations") None
        else if (name == "cross") argument.collect {
          case r: ScReferenceExpression
              if Set("disabled", "binary", "full", "for3Use2_13", "for2_13Use3")(r.refName) &&
                r.qualifier.exists(q =>
                  Set(
                    "CrossVersion",
                    "sbt.CrossVersion",
                    "sbt.librarymanagement.CrossVersion",
                    "_root_.sbt.librarymanagement.CrossVersion"
                  )(q.getText)
                ) =>
            s".cross(_root_.sbt.librarymanagement.CrossVersion.${r.refName})"
        }
        else crossModifier(receiver, next)
      expr match {
        case p: ScParenthesisedExpr   => p.innerElement.flatMap(crossModifier(_, next))
        case r: ScReferenceExpression =>
          definition(r).flatMap(crossModifier(_, next)).orElse {
            if (modifiers(r.refName)) r.qualifier.flatMap(modifier(r.refName, None, _)) else None
          }
        case c: ScMethodCall =>
          invoked(c.getEffectiveInvokedExpr) match {
            case r: ScReferenceExpression if modifiers(r.refName) =>
              r.qualifier.flatMap(modifier(r.refName, c.argumentExpressions.headOption, _))
            case _ => None
          }
        case i: ScInfixExpr if modifiers(i.operation.refName)   => modifier(i.operation.refName, Some(i.right), i.left)
        case p: ScPostfixExpr if modifiers(p.operation.refName) => modifier(p.operation.refName, None, p.operand)
        case i: ScInfixExpr if i.operation.refName == "%"       =>
          if (coordinate(i.left, next).nonEmpty) crossModifier(i.left, next) else Some("")
        case _ => None
      }
    }
    for {
      entry    <- coordinate(expression, Set.empty)
      modifier <- crossModifier(expression, Set.empty)
    } yield {
      val literal = s"${quote(entry.group)} ${entry.cross} ${quote(entry.artifact)} % ${quote(entry.version)}"
      ModuleIdentity(entry.group, entry.artifact, entry.version, entry.scope, s"($literal)$modifier")
    }
  }

  private case class CoordinateTransform(
    receiver: ScExpression,
    identity: ModuleIdentity,
    exclude: Option[(String, String)]
  )

  /** Recognizes only the exact coordinate predicate emitted by the editor, never arbitrary user lambdas. */
  private def coordinateTransform(call: ScMethodCall): Option[CoordinateTransform] = {
    def unwrapped(expr: ScExpression): ScExpression = expr match {
      case p: ScParenthesisedExpr                                    => p.innerElement.map(unwrapped).getOrElse(p)
      case b: ScBlock if b.statements.size == 1 && b.exprs.size == 1 => unwrapped(b.exprs.head)
      case other                                                     => other
    }
    def predicate(expr: ScExpression): Option[ModuleIdentity] = unwrapped(expr) match {
      case i: ScInfixExpr if i.operation.refName == "==" =>
        val fields =
          "(dependency.organization,dependency.name,dependency.revision,dependency.configurations.getOrElse(\"compile\"),dependency.crossVersion)"
        if (i.left.getText.replaceAll("\\s+", "") != fields) None
        else
          unwrapped(i.right) match {
            case tuple: ScTuple if tuple.exprs.size == 5 =>
              val values = tuple.exprs
              for {
                group <- string(values(0)); artifact <- string(values(1)); version <- string(values(2));
                scope <- string(values(3))
                cross <- unwrapped(values(4)) match {
                  case r: ScReferenceExpression if r.refName == "crossVersion" => r.qualifier.flatMap(moduleIdentity)
                  case _                                                       => None
                }
                if (group, artifact, version) == (cross.group, cross.artifact, cross.version)
              } yield cross.copy(scope = scope)
            case _ => None
          }
      case _ => None
    }
    invoked(call.getEffectiveInvokedExpr) match {
      case r: ScReferenceExpression if Set("filterNot", "map")(r.refName) && call.argumentExpressions.size == 1 =>
        for {
          receiver <- r.qualifier
          function <- unwrapped(call.argumentExpressions.head) match {
            case f: ScFunctionExpr if f.parameters.size == 1 && f.parameters.head.name == "dependency" => Some(f)
            case _                                                                                     => None
          }
          body   <- function.result.map(unwrapped)
          result <-
            if (r.refName == "filterNot") predicate(body).map(CoordinateTransform(receiver, _, None))
            else
              body match {
                case conditional: ScIf if conditional.elseExpression.exists(_.getText == "dependency") =>
                  for {
                    identity <- conditional.condition.flatMap(predicate)
                    excluded <- conditional.thenExpression.collect { case c: ScMethodCall => c }
                    method   <- invoked(excluded.getEffectiveInvokedExpr) match {
                      case ref: ScReferenceExpression
                          if ref.refName == "exclude" && ref.qualifier.exists(_.getText == "dependency") =>
                        Some(ref)
                      case _ => None
                    }
                    if excluded.argumentExpressions.size == 2
                    group    <- string(excluded.argumentExpressions.head)
                    artifact <- string(excluded.argumentExpressions(1))
                  } yield CoordinateTransform(receiver, identity, Some(group -> artifact))
                case _ => None
              }
        } yield result
      case _ => None
    }
  }

  private def position(expr: ScExpression): Option[Int] = expr.getText.trim.toIntOption.filter(_ >= 0)

  private def indexed(call: ScMethodCall): Option[(ScExpression, Int)] =
    if (call.argumentExpressions.size != 1) None
    else
      position(call.argumentExpressions.head).map { index =>
        val receiver = invoked(call.getEffectiveInvokedExpr) match {
          case r: ScReferenceExpression if r.refName == "apply" => r.qualifier.getOrElse(r)
          case other                                            => other
        }
        receiver -> index
      }

  private def transform(call: ScMethodCall): Option[(ScExpression, String, Int)] =
    invoked(call.getEffectiveInvokedExpr) match {
      case r: ScReferenceExpression if r.refName == "patch" && call.argumentExpressions.size == 3 =>
        val args = call.argumentExpressions
        for {
          receiver <- r.qualifier
          index    <- position(args.head)
          if args(1).getText == "_root_.scala.collection.immutable.Nil" && args(2).getText == "1"
        } yield (receiver, "patch", index)
      case r: ScReferenceExpression if r.refName == "updated" && call.argumentExpressions.size == 2 =>
        for { receiver <- r.qualifier; index <- position(call.argumentExpressions.head) } yield (
          receiver,
          "updated",
          index
        )
      case _ => None
    }

  /** Counts only statically ordered collections. Unknown factories, sets and incomplete expressions cannot produce a
   *  safe positional edit at a shared collection's use site.
   */
  private def sequenceSize(expr: ScExpression, memo: ResolutionMemo, seen: Set[PsiElement] = Set.empty): Option[Int] = {
    if (seen(expr) || seen.size > 128) return None
    if (memo.sizes.contains(expr)) return memo.sizes(expr)
    val next   = seen + expr
    val result =
      if (coordinate(expr, Set.empty).nonEmpty) Some(1)
      else
        expr match {
          case p: ScParenthesisedExpr                        => p.innerElement.flatMap(sequenceSize(_, memo, next))
          case t: ScTypedExpression                          => sequenceSize(t.expr, memo, next)
          case r: ScReferenceExpression                      => definition(r).flatMap(sequenceSize(_, memo, next))
          case i: ScInfixExpr if i.operation.refName == "++" =>
            for { a <- sequenceSize(i.left, memo, next); b <- sequenceSize(i.right, memo, next) } yield a + b
          case c: ScMethodCall =>
            invoked(c.getEffectiveInvokedExpr) match {
              case r: ScReferenceExpression if Set("Seq", "List", "Vector")(r.refName) =>
                val sizes = c.argumentExpressions.map(sequenceSize(_, memo, next))
                if (sizes.forall(_.nonEmpty)) Some(sizes.flatten.sum) else None
              case r: ScReferenceExpression if modifiers(r.refName) =>
                r.qualifier.flatMap(sequenceSize(_, memo, next)).filter(_ == 1)
              case _ =>
                coordinateTransform(c).flatMap { change =>
                  sequenceSize(change.receiver, memo, next).map { size =>
                    if (change.exclude.nonEmpty) size
                    else
                      size - dependenciesWithMemo(change.receiver, None, memo)
                        .count(e => moduleIdentity(e.site).contains(change.identity))
                  }
                }.orElse(transform(c).flatMap { (receiver, operation, index) =>
                  sequenceSize(receiver, memo, next).filter(index < _).flatMap { size =>
                    if (operation == "patch") Some(size - 1)
                    else sequenceSize(c.argumentExpressions(1), memo, next).filter(_ == 1).map(_ => size)
                  }
                }.orElse(indexed(c).flatMap { (receiver, index) =>
                  sequenceSize(receiver, memo, next).filter(index < _).map(_ => 1)
                }))
            }
          case b: ScBlockExpr => b.exprs.lastOption.flatMap(sequenceSize(_, memo, next))
          case _              => None
        }
    if (result.nonEmpty) memo.sizes.put(expr, result)
    result
  }

  /** Collects supported coordinate declarations, retaining use-site scope and editability. Ordered collection aliases
   *  retain a safe use-site position; unknown collections remain navigation-only. projectId limits explicit project-key
   *  owners. Requires read access and checks IDE cancellation during traversal.
   */
  private class ResolutionMemo {
    val sizes   = scala.collection.mutable.Map.empty[ScExpression, Option[Int]]
    val entries = scala.collection.mutable.Map.empty[(ScExpression, Option[String]), Seq[Entry]]
  }

  def dependencies(expression: ScExpression, projectId: Option[String] = None): Seq[Entry] =
    dependenciesWithMemo(expression, projectId, new ResolutionMemo)

  private def dependenciesWithMemo(
    expression: ScExpression,
    projectId: Option[String],
    memo: ResolutionMemo
  ): Seq[Entry] = {
    if (memo.entries.contains((expression, projectId))) return memo.entries((expression, projectId))
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
            case r: ScReferenceExpression =>
              definition(r).toSeq.flatMap { target =>
                val entries = loop(target, next, false)
                if (sequenceSize(target, memo).contains(entries.size))
                  entries.zipWithIndex.map { (entry, index) =>
                    entry.copy(editable = moduleIdentity(entry.site).nonEmpty, collection = Some(r -> index))
                  }
                else entries.map(_.copy(editable = false, collection = None))
              }
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
                case r: ScReferenceExpression if modifiers(r.refName) =>
                  val values = r.qualifier.toSeq.flatMap(loop(_, next, editable))
                  if (values.size != 1) Nil
                  else
                    values.map(value =>
                      modified(value, r.refName, call.argumentExpressions.headOption).copy(site = call)
                    )
                case _ =>
                  coordinateTransform(call) match {
                    case Some(change) =>
                      val values = loop(change.receiver, next, false)
                      if (!sequenceSize(change.receiver, memo).contains(values.size)) Nil
                      else {
                        val retained =
                          if (change.exclude.nonEmpty) values
                          else values.filterNot(e => moduleIdentity(e.site).contains(change.identity))
                        retained.zipWithIndex.map { (entry, index) =>
                          entry.copy(editable = moduleIdentity(entry.site).nonEmpty, collection = Some(call -> index))
                        }
                      }
                    case None =>
                      transform(call) match {
                        case Some((receiver, operation, index)) =>
                          val values   = loop(receiver, next, false)
                          val complete = sequenceSize(receiver, memo).contains(values.size) && index < values.size
                          if (!complete) Nil
                          else {
                            val changed =
                              if (operation == "patch") values.patch(index, Nil, 1)
                              else {
                                val replacement = loop(call.argumentExpressions(1), next, false)
                                if (replacement.size == 1) values.updated(index, replacement.head) else Nil
                              }
                            changed.zipWithIndex.map { (value, offset) =>
                              value.copy(editable = true, collection = Some(call -> offset))
                            }
                          }
                        case None =>
                          indexed(call).toSeq.flatMap { (receiver, index) =>
                            val values = loop(receiver, next, false)
                            if (sequenceSize(receiver, memo).contains(values.size))
                              values.lift(index).map(_.copy(site = call, editable = editable, collection = None))
                            else None
                          }
                      }
                  }
              }
            case block: ScBlockExpr => block.exprs.lastOption.toSeq.flatMap(loop(_, next, editable))
            case _                  => Nil
          }
      }
    }
    val result = loop(expression, Set.empty, true).distinctBy(e => (e.site, e.core, e.scope, e.collection))
    memo.entries.put((expression, projectId), result)
    result
  }

  /** Flattens slash-scoped key operands in source order; other expressions remain a single operand. */
  def keyExpressions(expr: ScExpression): Seq[ScExpression] = expr match {
    case i: ScInfixExpr if Set("/", "in")(i.operation.refName) => keyExpressions(i.left) ++ keyExpressions(i.right)
    case p: ScParenthesisedExpr                                => p.innerElement.toSeq.flatMap(keyExpressions)
    case other                                                 => Seq(other)
  }

  /** Recognizes libraryDependencies through slash scopes and the supported legacy in syntax. */
  def dependencyKey(expr: ScExpression): Boolean = expr match {
    case r: ScReferenceExpression                              => r.refName == "libraryDependencies"
    case i: ScInfixExpr if Set("/", "in")(i.operation.refName) => (
      if (i.operation.refName == "in") dependencyKey(i.left) else dependencyKey(i.right)
    )
    case p: ScParenthesisedExpr => p.innerElement.exists(dependencyKey)
    case _                      => false
  }
}
