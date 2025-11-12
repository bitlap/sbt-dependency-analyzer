package bitlap
package sbt
package analyzer
package util
package packagesearch

import java.util
import java.util.Collections.emptyList

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.model.AnalyzerCommandNotFoundException
import bitlap.sbt.analyzer.util.SbtDependencyUtils.GetMode.*

import org.jetbrains.plugins.scala.extensions.*
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScStringLiteral
import org.jetbrains.plugins.scala.lang.psi.api.expr.*
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaCode.*
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.project.{ ProjectContext, ProjectPsiFileExt, ScalaFeatures }
import org.jetbrains.sbt.SbtUtil
import org.jetbrains.sbt.language.utils.{ DependencyOrRepositoryPlaceInfo, SbtArtifactInfo, SbtDependencyCommon }
import org.jetbrains.sbt.language.utils.SbtDependencyCommon.defaultLibScope
import org.jetbrains.sbt.resolvers.{ SbtMavenResolver, SbtResolverUtils }

import com.intellij.buildsystem.model.DeclaredDependency
import com.intellij.buildsystem.model.unified.{ UnifiedCoordinates, UnifiedDependency, UnifiedDependencyRepository }
import com.intellij.externalSystem.ExternalDependencyModificator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.{ ControlFlowException, Logger }
import com.intellij.openapi.module as OpenapiModule
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

// copy from https://github.com/JetBrains/intellij-scala/blob/idea242.x/scala/integration/packagesearch/src/org/jetbrains/plugins/scala/packagesearch/SbtDependencyModifier.scala
// we have changed some
object SbtDependencyModifier extends ExternalDependencyModificator {

  private val logger = Logger.getInstance(this.getClass)

  override def supports(module: OpenapiModule.Module): Boolean = SbtUtil.isSbtModule(module)

  override def addDependency(module: OpenapiModule.Module, newDependency: UnifiedDependency): Unit = {
    implicit val project: Project = module.getProject
    val sbtFileOpt                = SbtDependencyUtils.getSbtFileOpt(module)
    if (sbtFileOpt == null) return
    val dependencyPlaces = inReadAction(for {
      sbtFile <- sbtFileOpt
      psiSbtFile    = PsiManager.getInstance(project).findFile(sbtFile).asInstanceOf[ScalaFile]
      sbtFileModule = psiSbtFile.module.orNull
      topLevelPlace =
        if (
          sbtFileModule != null && (sbtFileModule == module || sbtFileModule.getName == s"""${module.getName}-build""")
        )
          Seq(SbtDependencyUtils.getTopLevelPlaceToAdd(psiSbtFile))
        else Seq.empty

      depPlaces = (SbtDependencyUtils
        .getLibraryDependenciesOrPlaces(sbtFileOpt, project, module, GetPlace)
        .map(psiAndString => SbtDependencyUtils.toDependencyPlaceInfo(psiAndString._1, Seq()))
        ++ topLevelPlace).map {
        case Some(inside: DependencyOrRepositoryPlaceInfo) => inside
        case _                                             => null
      }.filter(_ != null).sortWith(_.toString < _.toString)
    } yield depPlaces).getOrElse(Seq.empty)
    val newDependencyCoordinates = newDependency.getCoordinates
    val newArtifactInfo          = SbtArtifactInfo(
      newDependencyCoordinates.getGroupId,
      newDependencyCoordinates.getArtifactId,
      newDependencyCoordinates.getVersion,
      newDependency.getScope
    )

    ApplicationManager.getApplication.invokeLater { () =>
      val wizard = new AddDependencyPreviewWizard(project, newArtifactInfo, dependencyPlaces)
      wizard.search() match {
        case Some(fileLine) =>
          SbtDependencyUtils.addDependency(fileLine.element, newArtifactInfo)(using project)
        case _ =>
      }
    }
  }

  override def updateDependency(
    module: OpenapiModule.Module,
    currentDependency: UnifiedDependency,
    newDependency: UnifiedDependency
  ): Unit = {
    implicit val project: Project = module.getProject
    val targetedLibDepTuple       =
      SbtDependencyUtils.findLibraryDependency(
        project,
        module,
        currentDependency,
        configurationRequired = false,
        versionRequired = false
      )
    if (targetedLibDepTuple == null) return
    val oldLibDep = SbtDependencyUtils.processLibraryDependencyFromExprAndString(targetedLibDepTuple, preserve = true)
    val newCoordinates = newDependency.getCoordinates

    if (
      SbtDependencyUtils.cleanUpDependencyPart(
        oldLibDep(2).asInstanceOf[ScStringLiteral].getText
      ) != newCoordinates.getVersion
    ) {
      inWriteCommandAction {
        val literal = oldLibDep(2).asInstanceOf[ScStringLiteral]
        literal
          .replace(
            ScalaPsiElementFactory.createElementFromText(s""""${newCoordinates.getVersion}"""", literal)
          )
      }
      return
    }
    var oldConfiguration = ""
    if (targetedLibDepTuple._2 != "")
      oldConfiguration = SbtDependencyUtils.cleanUpDependencyPart(targetedLibDepTuple._2)

    if (oldLibDep.length > 3)
      oldConfiguration = SbtDependencyUtils.cleanUpDependencyPart(oldLibDep(3).asInstanceOf[String])
    val newConfiguration = if (newDependency.getScope != defaultLibScope) newDependency.getScope else ""
    if (oldConfiguration.toLowerCase != newConfiguration.toLowerCase) {
      if (targetedLibDepTuple._2 != "") {
        if (newConfiguration == "") {
          inWriteCommandAction(targetedLibDepTuple._3.replace(code"${targetedLibDepTuple._3.left.getText}"))
        } else {
          inWriteCommandAction(targetedLibDepTuple._3.right.replace(code"${newConfiguration}"))
        }

      } else {
        if (oldLibDep.length > 3) {
          if (newConfiguration == "") {
            inWriteCommandAction(targetedLibDepTuple._1.replace(code"${targetedLibDepTuple._1.left}"))
          } else {
            inWriteCommandAction(targetedLibDepTuple._1.right.replace(code"""${newConfiguration}"""))
          }
        } else {
          if (newConfiguration != "") {
            inWriteCommandAction(
              targetedLibDepTuple._1.replace(code"""${targetedLibDepTuple._1.getText} % $newConfiguration""")
            )
          }
        }
      }
    }
  }

  /** * Support syntaxes: e.g.
   *
   *  [["x" % "y" % Versions.z withSources ()]]
   *
   *  [["x" %% "y" % Versions.z % Test]]
   *
   *  [["x" % "y" % z]]
   *
   *  [[libraryDependencies ++= Seq("x" % "y" % z)]]
   *
   *  @param module
   *  @param toRemoveDependency
   */
  override def removeDependency(module: OpenapiModule.Module, toRemoveDependency: UnifiedDependency): Unit = {
    implicit val project: Project = module.getProject
    val targetedLibDepTuple       =
      SbtDependencyUtils.findLibraryDependency(
        project,
        module,
        toRemoveDependency,
        configurationRequired = false,
        versionRequired = false
      )
    if (targetedLibDepTuple == null) {
      throw AnalyzerCommandNotFoundException("Target dependency not found")
    }
    // dangerous, hard-coded
    targetedLibDepTuple._3.getParent match {
      case _: ScArgumentExprList =>
        inWriteCommandAction {
          targetedLibDepTuple._3.delete()
        }
      case infix: ScInfixExpr if infix.left.textMatches(SbtDependencyUtils.LIBRARY_DEPENDENCIES) =>
        inWriteCommandAction {
          infix.delete()
        }
      case infix: ScInfixExpr if infix.getChildren.length == 3 && infix.getChildren()(2).isInstanceOf[ScUnitExpr] =>
        inWriteCommandAction {
          infix.delete()
        }
      case infix: ScParenthesisedExpr if infix.parents.toList.exists(_.isInstanceOf[ScReferenceExpression]) =>
        val lastRef = infix.parents.toList.filter(_.isInstanceOf[ScReferenceExpression]).lastOption
        inWriteCommandAction {
          lastRef.foreach(_.parent.foreach(_.delete()))
        }
      case _ =>
        throw AnalyzerCommandNotFoundException("This syntax isn’t supported yet.")
    }
  }

  override def addRepository(
    module: OpenapiModule.Module,
    unifiedDependencyRepository: UnifiedDependencyRepository
  ): Unit = {
    implicit val project: Project = module.getProject
    val sbtFileOpt                = SbtDependencyUtils.getSbtFileOpt(module)
    if (sbtFileOpt == null) return
    val sbtFile = sbtFileOpt.orNull
    if (sbtFile == null) return
    val psiSbtFile = PsiManager.getInstance(project).findFile(sbtFile).asInstanceOf[ScalaFile]

    SbtDependencyUtils.addRepository(psiSbtFile, unifiedDependencyRepository)
  }

  override def deleteRepository(
    module: OpenapiModule.Module,
    unifiedDependencyRepository: UnifiedDependencyRepository
  ): Unit = {}

  override def declaredDependencies(module: OpenapiModule.Module): util.List[DeclaredDependency] =
    SbtDependencyUtils.declaredDependencies(module)

  override def declaredRepositories(module: OpenapiModule.Module): util.List[UnifiedDependencyRepository] = try {
    SbtResolverUtils
      .projectResolvers(using module.getProject)
      .collect { case r: SbtMavenResolver =>
        new UnifiedDependencyRepository(r.name, r.presentableName, r.normalizedRoot)
      }
      .toList
      .asJava
  } catch {
    case c: ControlFlowException => throw c
    case e: Exception            =>
      logger.error(
        s"Error occurs when obtaining the list of supported repositories/resolvers for module ${module.getName} using package search plugin",
        e
      )
      emptyList()
  }

  final def addExcludeToDependency(
    module: OpenapiModule.Module,
    currentDependency: UnifiedDependency,
    coordinates: UnifiedCoordinates
  ): Boolean = {
    implicit val project: Project = module.getProject
    val targetedLibDepTuple       =
      SbtDependencyUtils.findLibraryDependency(
        project,
        module,
        currentDependency,
        configurationRequired = false,
        versionRequired = false
      )
    if (targetedLibDepTuple == null) return false
    // add `(expr).exclude('group', 'artifact')`
    inWriteCommandAction {
      val newExpr = wrapInParentheses(targetedLibDepTuple._3)
      val newCode = s"""${newExpr.getText}.${ScalaPsiElementFactory
          .createNewLine()
          .getText}exclude("${coordinates.getGroupId}", "${coordinates.getArtifactId}")"""
      targetedLibDepTuple._3.replace(code"""$newCode""")
    }
    true
  }

  private def wrapInParentheses(expression: ScExpression)(implicit ctx: ProjectContext): ScParenthesisedExpr = {
    val parenthesised = ScalaPsiElementFactory
      .createElementFromText[ScParenthesisedExpr](expression.getText.parenthesize(true), expression)
    parenthesised.innerElement.foreach(_.replace(expression.copy()))
    parenthesised
  }
}
