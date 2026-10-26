package bitlap.sbt.analyzer.util;

import scala.annotation.tailrec

import org.jetbrains.plugins.scala.extensions.&
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.inNameContext
import org.jetbrains.plugins.scala.lang.psi.api.{ ScalaElementVisitor, ScalaPsiElement }
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScStringLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScReferencePattern
import org.jetbrains.plugins.scala.lang.psi.api.expr.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition

import com.intellij.psi.{ PsiElement, PsiFile }

// Adapted from https://github.com/JetBrains/intellij-scala/blob/idea242.x/sbt/sbt-impl/src/org/jetbrains/sbt/language/utils/SbtDependencyTraverser.scala
/** Traverses supported sbt dependency PSI for the legacy package-search helpers. Callbacks returning false stop descent
 *  into that element. This traversal does not evaluate arbitrary Scala control flow. Adapted from JetBrains
 *  intellij-scala SbtDependencyTraverser (idea242.x); analyzer declaration resolution uses SbtDeclarationResolver for
 *  structural coordinate matching.
 */
object SbtDependencyTraverser {

  def traverseStringLiteral(stringLiteral: ScStringLiteral)(callback: PsiElement => Boolean): Unit =
    callback(stringLiteral)

  def traverseInfixExpr(infixExpr: ScInfixExpr)(callback: PsiElement => Boolean): Unit = {
    if (infixExpr == null || !callback(infixExpr)) return

    def traverse(expr: ScExpression): Unit = {
      expr match {
        case subInfix: ScInfixExpr                  => traverseInfixExpr(subInfix)(callback)
        case call: ScMethodCall                     => traverseMethodCall(call)(callback)
        case refExpr: ScReferenceExpression         => traverseReferenceExpr(refExpr)(callback)
        case stringLiteral: ScStringLiteral         => traverseStringLiteral(stringLiteral)(callback)
        case blockExpr: ScBlockExpr                 => traverseBlockExpr(blockExpr)(callback)
        case parenthesisedExpr: ScParenthesisedExpr =>
          // +=("com.chuusai" %%% "shapeless" % shapelessVersion)
          traverseParenthesisedExpr(parenthesisedExpr)(callback)
        case _ =>
      }
    }

    infixExpr.operation.refName match {
      case "++" =>
        traverse(infixExpr.left)
        traverse(infixExpr.right)
      case "++=" | ":=" | "+=" =>
        traverse(infixExpr.right)
      case "%" | "%%" =>
        traverse(infixExpr.left)
        traverse(infixExpr.right)
      case _ =>
        traverse(infixExpr.left)
    }
  }

  def traverseReferenceExpr(refExpr: ScReferenceExpression)(callback: PsiElement => Boolean): Unit = {
    if (!callback(refExpr)) return

    refExpr.resolve() match {
      case (_: ScReferencePattern) & inNameContext(ScPatternDefinition.expr(expr)) =>
        expr match {
          case infix: ScInfixExpr =>
            traverseInfixExpr(infix)(callback)
          case re: ScReferenceExpression =>
            traverseReferenceExpr(re)(callback)
          case seq: ScMethodCall
              if seq.deepestInvokedExpr
                .textMatches(SbtDependencyUtils.SEQ) || seq.deepestInvokedExpr.textMatches(SbtDependencyUtils.LIST) =>
            traverseSeq(seq)(callback)
          case stringLiteral: ScStringLiteral =>
            traverseStringLiteral(stringLiteral)(callback)

          case scParenthesisedExpr: ScParenthesisedExpr =>
            traverseParenthesisedExpr(scParenthesisedExpr)(callback)
          case _ =>
        }
      case _ =>
        refExpr.acceptChildren(
          new ScalaElementVisitor {
            override def visitParenthesisedExpr(expr: ScParenthesisedExpr): Unit =
              traverseParenthesisedExpr(expr)(callback)
          }
        )
    }
  }

  def traverseMethodCall(call: ScMethodCall)(callback: PsiElement => Boolean): Unit = {
    if (!callback(call)) return

    call match {
      case seq
          if seq.deepestInvokedExpr
            .textMatches(SbtDependencyUtils.SEQ) | seq.deepestInvokedExpr.textMatches(SbtDependencyUtils.LIST) =>
        traverseSeq(seq)(callback)
      case settings =>
        settings.getEffectiveInvokedExpr match {
          case expr: ScReferenceExpression if SbtDependencyUtils.isSettings(expr.refName) =>
            traverseSettings(settings)(callback)
          case _ =>
        }
    }
  }

  def traversePatternDef(patternDef: ScPatternDefinition)(callback: PsiElement => Boolean): Unit = {
    if (!callback(patternDef)) return

    val maybeTypeName = patternDef
      .`type`()
      .toOption
      .map(_.canonicalText)

    if (
      maybeTypeName.contains(SbtDependencyUtils.SBT_PROJECT_TYPE) || maybeTypeName.contains(
        SbtDependencyUtils.SBT_CROSS_SETTING_TYPE
      )
    ) {
      retrieveSettings(patternDef, callback).foreach(traverseMethodCall(_)(callback))
    } else {
      patternDef.expr match {
        case Some(call: ScMethodCall)     => traverseMethodCall(call)(callback)
        case Some(infix: ScInfixExpr)     => traverseInfixExpr(infix)(callback)
        case Some(blockExpr: ScBlockExpr) => traverseBlockExpr(blockExpr)(callback)
        case _                            =>
      }
    }
  }

  /** Visits supported Seq/List elements, references and parenthesized modifier receivers. Conditional branches and
   *  arbitrary collection-producing expressions are not evaluated.
   */
  def traverseSeq(seq: ScMethodCall)(callback: PsiElement => Boolean): Unit = {
    if (!callback(seq)) return

    seq.argumentExpressions.foreach {
      case infixExpr: ScInfixExpr =>
        traverseInfixExpr(infixExpr)(callback)
      case refExpr: ScReferenceExpression =>
        traverseReferenceExpr(refExpr)(callback)
      case methodCall: ScMethodCall if methodCall.getEffectiveInvokedExpr.isInstanceOf[ScReferenceExpression] =>
        val expr = methodCall.getEffectiveInvokedExpr
          .asInstanceOf[ScReferenceExpression]
        expr
          .acceptChildren( // fixed: ("com.chuusai" %%% "shapeless" % shapelessVersion).cross(CrossVersion.for3Use2_13)
            new ScalaElementVisitor {
              override def visitParenthesisedExpr(expr: ScParenthesisedExpr): Unit = {
                traverseParenthesisedExpr(expr)(callback)
              }
            }
          )
      case _ =>
    }
  }

  def traverseParenthesisedExpr(parenthesisedExpr: ScParenthesisedExpr)(callback: PsiElement => Boolean): Unit = {
    if (!callback(parenthesisedExpr)) return

    parenthesisedExpr.acceptChildren(new ScalaElementVisitor {
      override def visitInfixExpression(infix: ScInfixExpr): Unit = {
        traverseInfixExpr(infix)(callback)
      }

      override def visitParenthesisedExpr(expr: ScParenthesisedExpr): Unit =
        traverseParenthesisedExpr(expr)(callback)

      override def visitMethodCallExpression(call: ScMethodCall): Unit =
        call.acceptChildren(
          new ScalaElementVisitor {
            override def visitReferenceExpression(ref: ScReferenceExpression): Unit =
              traverseReferenceExpr(ref)(callback)
          }
        )
    })
  }

  def traverseBlockExpr(blockExpr: ScBlockExpr)(callback: PsiElement => Boolean): Unit = {
    if (!callback(blockExpr)) return

    blockExpr.acceptChildren(new ScalaElementVisitor {
      override def visitInfixExpression(infix: ScInfixExpr): Unit = {
        traverseInfixExpr(infix)(callback)
      }

      override def visitMethodCallExpression(call: ScMethodCall): Unit = {
        if (
          call.deepestInvokedExpr.textMatches(SbtDependencyUtils.SEQ) || call.deepestInvokedExpr
            .textMatches(SbtDependencyUtils.LIST)
        )
          traverseSeq(call)(callback)
      }

      override def visitReferenceExpression(ref: ScReferenceExpression): Unit = {
        traverseReferenceExpr(ref)(callback)
      }
    })
  }

  def traverseSettings(settings: ScMethodCall)(callback: PsiElement => Boolean): Unit = {
    if (!callback(settings)) return

    settings.args.exprs.foreach {
      case infix: ScInfixExpr
          if infix.left.textMatches(SbtDependencyUtils.LIBRARY_DEPENDENCIES) &&
            SbtDependencyUtils.isAddableLibraryDependencies(infix) =>
        traverseInfixExpr(infix)(callback)
      case refExpr: ScReferenceExpression => traverseReferenceExpr(refExpr)(callback)
      case _                              =>
    }
  }

  @tailrec
  def retrievePatternDef(psiElement: PsiElement): ScPatternDefinition = {
    psiElement match {
      case patternDef: ScPatternDefinition => patternDef
      case _: PsiFile                      => null
      case _                               => retrievePatternDef(psiElement.getParent)
    }
  }

  def retrieveSettings(patternDef: ScPatternDefinition, callback: PsiElement => Boolean): Seq[ScMethodCall] = {
    var res: Seq[ScMethodCall] = Seq.empty

    def traverse(pd: ScalaPsiElement): Unit = {
      pd.acceptChildren(new ScalaElementVisitor {
        // NATIVE_SETTINGS,JS_SETTINGS,JVM_SETTINGS,PLATFORM_SETTINGS
        override def visitReferenceExpression(ref: ScReferenceExpression): Unit = {
          ref.acceptChildren(new ScalaElementVisitor {
            override def visitMethodCallExpression(call: ScMethodCall): Unit = {
              traverse(call)
              super.visitMethodCallExpression(call)
            }
          })
          super.visitReferenceExpression(ref)
        }

        override def visitArgumentExprList(args: ScArgumentExprList): Unit = {
          args.acceptChildren(
            new ScalaElementVisitor {
              override def visitInfixExpression(infix: ScInfixExpr): Unit = {
                args.getParent match
                  case msc: ScMethodCall =>
                    if (msc.`type`().toOption.map(_.canonicalText).contains(SbtDependencyUtils.CROSS_PROJECT)) {
                      traverseInfixExpr(infix)(callback)
                    }
                super.visitInfixExpression(infix)
              }
            }
          )
          super.visitArgumentExprList(args)
        }

        override def visitMethodCallExpression(call: ScMethodCall): Unit = {
          call.getEffectiveInvokedExpr match {
            case sc: ScMethodCall
                if sc.`type`().toOption.map(_.canonicalText).contains(SbtDependencyUtils.CROSS_PROJECT_FUNCTION) =>
              // platformsSettings(JSPlatform, NativePlatform) \
              // (libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTimeVersion % Test)
              traverse(call)
            case expr: ScReferenceExpression if SbtDependencyUtils.isSettings(expr.refName) =>
              res ++= Seq(call)
            case _ =>
          }
          traverse(call.getEffectiveInvokedExpr)
          super.visitMethodCallExpression(call)
        }

      })
    }

    traverse(patternDef)

    res
  }
}
