package bitlap
package sbt
package analyzer
package util

import java.util.Collections

import scala.jdk.CollectionConverters.*
import scala.util.boundary

import bitlap.sbt.analyzer.util.SbtDependencyUtils.GetMode.GetDep

import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.extensions.*
import org.jetbrains.plugins.scala.lang.psi.api.{ ScalaElementVisitor, ScalaFile }
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScStringLiteral
import org.jetbrains.plugins.scala.lang.psi.api.expr.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.lang.psi.types.result.TypeResultExt
import org.jetbrains.plugins.scala.project.*
import org.jetbrains.sbt.Sbt
import org.jetbrains.sbt.SbtUtil.{ getBuildModuleData, getSbtModuleData }
import org.jetbrains.sbt.language.utils.{ DependencyOrRepositoryPlaceInfo, SbtArtifactInfo, SbtDependencyCommon }

import com.intellij.buildsystem.model.DeclaredDependency
import com.intellij.buildsystem.model.unified.*
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.*
import com.intellij.openapi.module.{ Module as OpenapiModule, ModuleManager }
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*

// Adapted from https://github.com/JetBrains/intellij-scala/blob/idea242.x/sbt/sbt-impl/src/org/jetbrains/sbt/language/utils/SbtDependencyUtils.scala
/** Source discovery and PSI editing helpers for sbt dependencies and repositories. Analyzer declarations use the
 *  structural resolver; older package-search operations retain their tuple-based API. PSI reads require read access and
 *  mutations are performed through write commands.
 */
object SbtDependencyUtils {
  val LIBRARY_DEPENDENCIES: String = "libraryDependencies"
  val SETTINGS: String             = "settings"
  val JS_SETTINGS: String          = "jsSettings"
  val NATIVE_SETTINGS: String      = "nativeSettings"
  val JVM_SETTINGS: String         = "jvmSettings"
  val CROSS_PROJECT: String        = "_root_.sbtcrossproject.CrossProject"

  val CROSS_PROJECT_FUNCTION: String =
    "scala.Seq[_root_.sbt.Def.SettingsDefinition] => _root_.sbtcrossproject.CrossProject"
  val SEQ: String  = "Seq"
  val LIST: String = "List"
  val ANY: String  = "Any"

  val SBT_PROJECT_TYPE       = "_root_.sbt.Project"
  val SBT_SETTING_TYPE       = "_root_.sbt.Def.Setting"
  val SBT_CROSS_SETTING_TYPE = "_root_.sbtcrossproject.CrossProject"
  val SBT_MODULE_ID_TYPE     = "sbt.ModuleID"
  val SBT_LIB_CONFIGURATION  = "_root_.sbt.librarymanagement.Configuration"

  private val LOG = Logger.getInstance(SbtDependencyUtils.getClass)

  def isSettings(setting: String): Boolean = {
    setting == SETTINGS || setting == JS_SETTINGS || setting == NATIVE_SETTINGS || setting == JVM_SETTINGS
  }

  val SCALA_DEPENDENCIES_WITH_MINOR_SCALA_VERSION_LIST = List(
    "ch.epfl.scala:scalafix-cli",
    "ch.epfl.scala:scalafix-reflect",
    "ch.epfl.scala:scalafix-testkit",
    "com.avsystem.scex:scex-core",
    "com.avsystem.scex:scex-macros",
    "com.avsystem.scex:scex-util",
    "com.github.cb372:scala-typed-holes",
    "com.github.dkhalansky:paradisenglib",
    "com.github.dkhalansky:paradisengplugin",
    "com.github.ghik:silencer-lib",
    "com.github.ghik:silencer-plugin",
    "com.github.tomasmikula:pascal",
    "com.github.wheaties:twotails",
    "com.github.wheaties:twotails-annotations",
    "com.kubukoz:better-tostring",
    "com.lihaoyi:ammonite",
    "com.lihaoyi:ammonite-api",
    "com.lihaoyi:ammonite-compiler",
    "com.lihaoyi:ammonite-compiler-interface",
    "com.lihaoyi:ammonite-interp",
    "com.lihaoyi:ammonite-interp-api",
    "com.lihaoyi:ammonite-interpApi",
    "com.lihaoyi:ammonite-repl",
    "com.lihaoyi:ammonite-repl-api",
    "com.lihaoyi:ammonite-replApi",
    "com.lihaoyi:ammonite-runtime",
    "com.lihaoyi:ammonite-shell",
    "com.lihaoyi:ammonite-sshd",
    "com.lihaoyi:ammonite-test-api",
    "com.lihaoyi:ammonite-util",
    "com.lihaoyi:mill-bridge",
    "com.sksamuel.scapegoat:scalac-scapegoat-plugin",
    "com.typesafe.genjavadoc:genjavadoc-plugin",
    "edu.berkeley.cs:chisel3-plugin",
    "io.methvin:orphan-finder",
    "io.regadas:socco-ng",
    "io.tryp:splain",
    "org.jupyter-scala:ammonite",
    "org.jupyter-scala:ammonite-compiler",
    "org.jupyter-scala:ammonite-repl",
    "org.jupyter-scala:ammonite-runtime",
    "org.jupyter-scala:ammonite-sshd",
    "org.jupyter-scala:ammonite-util",
    "org.jupyter-scala:scala-api",
    "org.jupyter-scala:scala-cli",
    "org.jupyter-scala:scala-kernel",
    "org.scala-js:scalajs-compiler",
    "org.scala-js:scalajs-junit-test-plugin",
    "org.scala-lang.plugins:scala-continuations-plugin",
    "org.scala-native:junit-plugin",
    "org.scala-native:nscplugin",
    "org.scala-refactoring:org.scala-refactoring.library",
    "org.scala-sbt.sxr:sxr",
    "org.scalamacros:paradise",
    "org.scalameta:interactive",
    "org.scalameta:metac",
    "org.scalameta:mtags",
    "org.scalameta:paradise",
    "org.scalameta:scalahost",
    "org.scalameta:scalahost-nsc",
    "org.scalameta:semanticdb-scalac",
    "org.scalameta:semanticdb-scalac-core",
    "org.scalaz:deriving-plugin",
    "org.scoverage:scalac-scoverage-plugin",
    "org.scoverage:scalac-scoverage-runtime",
    "org.scoverage:scalac-scoverage-runtime_sjs1",
    "org.typelevel:kind-projector",
    "org.virtuslab.semanticgraphs:scalac-plugin",
    "org.wartremover:wartremover",
    "org.wartremover:wartremover-contrib",
    "sh.almond:scala-interpreter",
    "sh.almond:scala-kernel",
    "sh.almond:scala-kernel-api"
  )

  sealed trait GetMode

  object GetMode {
    case object GetPlace extends GetMode
    case object GetDep   extends GetMode
  }

  def preprocessVersion(v: String): String = {
    val pattern = """\d+""".r
    v.split("\\.")
      .map(part => {
        val newPart = pattern.findAllIn(part).toList
        if (newPart.isEmpty) 0
        else newPart(0)
      })
      .mkString(".")
  }

  def isVersionStable(version: String): Boolean = {
    val unstablePattern = """.*[a-zA-Z-].*"""
    !version.matches(unstablePattern)
  }

  def isGreaterStableVersion(newVer: String, oldVer: String): Boolean = {
    val oldVerPreprocessed = preprocessVersion(oldVer)
    val newVerPreprocessed = preprocessVersion(newVer)
    newVerPreprocessed
      .split("\\.")
      .zipAll(oldVerPreprocessed.split("\\."), "0", "0")
      .find { case (a, b) => a != b }
      .fold(0) { case (a, b) => a.toInt - b.toInt } > 0
  }

  def buildScalaArtifactIdString(groupId: String, artifactId: String, scalaVer: String): String = {
    org.jetbrains.plugins.scala.packagesearch.util.DependencyUtil.buildScalaArtifactIdString(
      artifactId,
      scalaVer,
      SCALA_DEPENDENCIES_WITH_MINOR_SCALA_VERSION_LIST.contains(s"$groupId:$artifactId")
    )
  }

  /** Finds one legacy dependency tuple matching the requested coordinates and optional version/scope constraints.
   *  Returns null when absent and throws on multiple matches; analyzer actions provide their own declaration chooser.
   */
  def findLibraryDependency(
    project: Project,
    module: OpenapiModule,
    dependency: UnifiedDependency,
    versionRequired: Boolean = true,
    configurationRequired: Boolean = true
  ): (ScInfixExpr, String, ScInfixExpr) = {
    val sbtFileOpt            = getSbtFileOpt(module)
    val targetCoordinates     = dependency.getCoordinates
    val targetDepText: String = generateArtifactTextVerbose(
      targetCoordinates.getGroupId,
      DependencyUtils.getArtifactWithoutScalaVersion(targetCoordinates.getArtifactId),
      if (versionRequired) targetCoordinates.getVersion else "",
      if (configurationRequired) dependency.getScope else SbtDependencyCommon.defaultLibScope
    )
    val libDeps = getLibraryDependenciesOrPlaces(sbtFileOpt, project, module, GetDep)
    val matches = libDeps.filter(libDep => {
      var processedDep: List[String] = List()
      processedDep = processLibraryDependencyFromExprAndString(libDep.asInstanceOf[(ScInfixExpr, String, ScInfixExpr)])
        .map(_.asInstanceOf[String])
      var processedDepText: String = ""
      processedDep match {
        case List(a, b, c) =>
          if (b == targetCoordinates.getVersion) {
            processedDepText =
              generateArtifactTextVerbose(a, c, if (versionRequired) b else "", SbtDependencyCommon.defaultLibScope)
          } else {
            processedDepText =
              generateArtifactTextVerbose(a, b, if (versionRequired) c else "", SbtDependencyCommon.defaultLibScope)
          }
        case List(a, b, c, d) =>
          if (b == targetCoordinates.getVersion) {
            processedDepText = generateArtifactTextVerbose(
              a,
              c,
              if (versionRequired) c else "",
              if (configurationRequired) d else SbtDependencyCommon.defaultLibScope
            )
          } else {
            processedDepText = generateArtifactTextVerbose(
              a,
              b,
              if (versionRequired) c else "",
              if (configurationRequired) d else SbtDependencyCommon.defaultLibScope
            )
          }
        case _ =>
      }

      targetDepText.equals(processedDepText)
    })
    matches match {
      case Seq(one) => one.asInstanceOf[(ScInfixExpr, String, ScInfixExpr)]
      case Seq()    => null
      case _        =>
        throw new IllegalArgumentException(
          "Multiple dependency declarations match; choose a declaration in the analyzer"
        )
    }
  }

  /** Collects top-level and project-specific legacy dependency/place tuples for the imported sbt ID. */
  def getLibraryDependenciesOrPlacesUtil(
    module: OpenapiModule,
    psiSbtFile: ScalaFile,
    mode: GetMode
  ): Seq[(PsiElement, String, PsiElement)] = {
    var res: Seq[(PsiElement, String, PsiElement)] = Seq()
    val sbtFileModule                              = psiSbtFile.module.getOrElse(null)
    if (sbtFileModule != null && (sbtFileModule == module || sbtFileModule.getName == s"""${module.getName}-build"""))
      res ++= getTopLevelLibraryDependencies(psiSbtFile).flatMap(libDep =>
        getLibraryDependenciesOrPlacesFromPsi(libDep, mode)
      )

    val sbtProjectsInModule =
      getTopLevelSbtProjects(psiSbtFile).filter(proj => DependencyUtils.containsModuleName(proj, module))

    res ++= sbtProjectsInModule
      .flatMap(proj => getPossiblePsiFromProjectDefinition(proj))
      .flatMap(elem => getLibraryDependenciesOrPlacesFromPsi(elem, mode))

    res.distinct
  }

  /** Looks up the optional build file and collects legacy tuples within a read action; missing files yield an empty
   *  sequence.
   */
  def getLibraryDependenciesOrPlaces(
    sbtFileOpt: Option[VirtualFile],
    project: Project,
    module: OpenapiModule,
    mode: GetMode
  ): Seq[(PsiElement, String, PsiElement)] = {

    val libDeps = inReadAction(
      for {
        sbtFile <- sbtFileOpt
        psiSbtFile = PsiManager.getInstance(project).findFile(sbtFile).asInstanceOf[ScalaFile]
        deps       = getLibraryDependenciesOrPlacesUtil(module, psiSbtFile, mode)
      } yield deps
    )
    libDeps.getOrElse(Seq.empty)
  }

  /** Collects literal/configuration leaves from a supported legacy expression and appends inherited scope. With
   *  preserve enabled returns PSI leaves instead of cleaned text. This is a traversal-based compatibility API, not a
   *  guaranteed group/artifact/version tuple for arbitrary expressions.
   */
  def processLibraryDependencyFromExprAndString(
    elem: (ScExpression, String, ScExpression),
    preserve: Boolean = false
  ): List[Any] = {
    var res: List[Any] = List()

    def callbackInfix(psiElement: PsiElement): Boolean = {
      psiElement match {
        case stringLiteral: ScStringLiteral =>
          if (preserve) {
            res = stringLiteral :: res
          } else {
            res = cleanUpDependencyPart(stringLiteral.getText) :: res
          }
        case ref: ScReferenceExpression if ref.`type`().getOrAny.canonicalText.equals(SBT_LIB_CONFIGURATION) =>
          if (preserve) {
            res = ref :: res
          } else {
            res = cleanUpDependencyPart(ref.getText) :: res
          }
        case _ =>
      }
      true
    }

    elem._1 match {
      case infix: ScInfixExpr =>
        SbtDependencyTraverser.traverseInfixExpr(infix)(callbackInfix)
      case ref: ScReferenceExpression =>
        var infix: ScInfixExpr = null

        def callbackRef(psiElement: PsiElement): Boolean = {
          psiElement match {
            case subInfix: ScInfixExpr if subInfix.operation.refName.contains("%") =>
              infix = subInfix
              return false
            case _ =>
          }
          true
        }

        SbtDependencyTraverser.traverseReferenceExpr(ref)(callbackRef)
        SbtDependencyTraverser.traverseInfixExpr(infix)(callbackInfix)
    }

    elem._2 match {
      case s if s.nonEmpty => res = cleanUpDependencyPart(s) :: res
      case _               =>
    }
    res.reverse
  }

  def cleanUpDependencyPart(s: String): String = s.trim.replaceAll("^\"|\"$", "")

  def isScalaLibraryDependency(psi: PsiElement): Boolean = {
    var result = false

    def callback(psiElement: PsiElement): Boolean = {
      psiElement match {
        case infix: ScInfixExpr if infix.operation.refName.contains("%%") =>
          result = true
          false
        case _ => true
      }
    }

    inReadAction({
      psi match {
        case infix: ScInfixExpr             => SbtDependencyTraverser.traverseInfixExpr(infix)(callback)
        case call: ScMethodCall             => SbtDependencyTraverser.traverseMethodCall(call)(callback)
        case refExpr: ScReferenceExpression => SbtDependencyTraverser.traverseReferenceExpr(refExpr)(callback)
        case _                              =>
      }
    })

    result
  }

  /** Collects legacy dependency expressions or candidate insertion places from supported PSI.
   *
   *  @param psi
   *    root expression to traverse under read access
   *  @param mode
   *    GetDep collects dependencies; GetPlace collects supported insertion locations
   *  @return
   *    tuples of dependency/place PSI, inherited configuration text, and its enclosing expression
   */
  def getLibraryDependenciesOrPlacesFromPsi(psi: PsiElement, mode: GetMode): Seq[(PsiElement, String, PsiElement)] = {
    var result: Seq[(PsiElement, String, PsiElement)] = List()

    def callbackDep(psiElement: PsiElement): Boolean = {
      psiElement match {
        case infix: ScInfixExpr if infix.operation.refName.contains("%") =>
          infix.getText.split('%').map(_.trim).count(_.nonEmpty) - 1 match {
            case 1
                if infix.right.isInstanceOf[ScReferenceExpression] &&
                  infix.right.`type`().getOrAny.canonicalText.equals(SBT_LIB_CONFIGURATION) =>
              inReadAction {
                val configuration = cleanUpDependencyPart(infix.right.getText).toLowerCase.capitalize

                def callbackRef(psiElement: PsiElement): Boolean = {
                  psiElement match {
                    case subInfix: ScInfixExpr if subInfix.operation.refName.contains("%") =>
                      result ++= Seq((subInfix, configuration, infix))
                      return false
                    case _ =>
                  }
                  true
                }

                infix.left match {
                  case refExpr: ScReferenceExpression =>
                    SbtDependencyTraverser.traverseReferenceExpr(refExpr)(callbackRef)
                  case _ =>
                }
                false
              }
            case _
                if infix.left.isInstanceOf[ScInfixExpr] && !infix.left
                  .asInstanceOf[ScInfixExpr]
                  .right
                  .isInstanceOf[ScReferenceExpression] &&
                  infix.right.isInstanceOf[ScReferenceExpression] &&
                  infix.right.`type`().getOrAny.canonicalText.equals(SBT_LIB_CONFIGURATION) =>
              val configuration = cleanUpDependencyPart(infix.right.getText).toLowerCase.capitalize
              result ++= Seq((infix.left, configuration, infix))
              return false
            case _ if infix.left.isInstanceOf[ScInfixExpr] =>
              // our fix to  resolve if version is a val/var,e.g., pass artifact through configuration
              val fixedSplits = infix.left.asInstanceOf[ScInfixExpr].right match
                case _: ScStringLiteral =>
                  infix.getText.split('%').filter(_.nonEmpty)
                case _: ScReferenceExpression =>
                  infix.getText.split('%').filter(_.nonEmpty)
                case _ => Array.empty[String]

              if (fixedSplits.length == 3) {
                result ++= Seq((infix, fixedSplits(1), infix))
              } else if (fixedSplits.length == 4) {
                result ++= Seq((infix.left.asInstanceOf[ScInfixExpr], fixedSplits(1), infix))
              } else {
                result ++= Seq((infix, "", infix))
              }
              return false
            case _ =>
              result ++= Seq((infix, "", infix))
              return false
          }
        case ref: ScReferenceExpression if ref.`type`().getOrAny.canonicalText.equals(SBT_MODULE_ID_TYPE) =>
          result ++= Seq((ref, "", ref))
          return false
        case _ =>
      }
      true
    }

    def callbackPlace(psiElement: PsiElement): Boolean = {
      psiElement match {
        case libDep: ScInfixExpr
            if libDep.left.textMatches(LIBRARY_DEPENDENCIES) & isAddableLibraryDependencies(libDep) =>
          result ++= Seq((libDep, "", libDep))
        case call: ScMethodCall
            if call.deepestInvokedExpr.textMatches(SEQ) || call.deepestInvokedExpr.textMatches(LIST) =>
          result ++= Seq((call, "", call))
        case settings: ScMethodCall =>
          settings.getEffectiveInvokedExpr match {
            case expr: ScReferenceExpression if SbtDependencyUtils.isSettings(expr.refName) =>
              result ++= Seq((settings, "", settings))
            case _ =>
          }
        case _ =>
      }
      true
    }

    def callback(psiElement: PsiElement): Boolean = {
      if (mode == GetDep) callbackDep(psiElement)
      else callbackPlace(psiElement)
    }

    psi match {
      case infix: ScInfixExpr             => SbtDependencyTraverser.traverseInfixExpr(infix)(callback)
      case call: ScMethodCall             => SbtDependencyTraverser.traverseMethodCall(call)(callback)
      case refExpr: ScReferenceExpression => SbtDependencyTraverser.traverseReferenceExpr(refExpr)(callback)
      case _                              =>
    }

    result
  }

  /** Collects settings expressions from a supported project definition for legacy dependency/place discovery. */
  def getPossiblePsiFromProjectDefinition(proj: ScPatternDefinition): Seq[PsiElement] = {
    var res: Seq[PsiElement] = List()

    def action(psiElement: PsiElement): Boolean = {
      psiElement match {
        case e: ScInfixExpr if e.left.textMatches(LIBRARY_DEPENDENCIES) && isAddableLibraryDependencies(e) =>
          res ++= Seq(e)
        case call: ScMethodCall
            if call.deepestInvokedExpr.textMatches(SEQ) || call.deepestInvokedExpr.textMatches(LIST) =>
          res ++= Seq(call)
        case typedSeq: ScTypedExpression if typedSeq.isSequenceArg =>
          typedSeq.expr match {
            case call: ScMethodCall
                if call.deepestInvokedExpr.textMatches(SEQ) || call.deepestInvokedExpr.textMatches(LIST) =>
              res ++= Seq(typedSeq)
            case _ =>
          }
        case settings: ScMethodCall =>
          settings.getEffectiveInvokedExpr match {
            case expr: ScReferenceExpression if SbtDependencyUtils.isSettings(expr.refName) => res ++= Seq(settings)
            case _                                                                          =>
          }
        case _ =>
      }
      true
    }

    // support cross-platform CrossProject
    SbtDependencyTraverser.traversePatternDef(proj)(action)

    res
  }

  /** Finds top-level definitions whose inferred type is a supported sbt Project or cross-project type. */
  def getTopLevelSbtProjects(psiSbtFile: ScalaFile): Seq[ScPatternDefinition] = {
    var res: Seq[ScPatternDefinition] = List()

    psiSbtFile.acceptChildren(new ScalaElementVisitor {
      override def visitPatternDefinition(pat: ScPatternDefinition): Unit = {
        if (pat.expr.isEmpty)
          return

        if (
          pat.expr.get.`type`().getOrAny.canonicalText != SBT_PROJECT_TYPE &&
          // support cross-build
          pat.expr.get.`type`().getOrAny.canonicalText != SBT_CROSS_SETTING_TYPE
        )
          return

        res = res ++ Seq(pat)
        super.visitPatternDefinition(pat)
      }
    })

    res
  }

  /** Finds top-level libraryDependencies assignments outside explicit project definitions. */
  def getTopLevelLibraryDependencies(psiSbtFile: ScalaFile): Seq[ScInfixExpr] = {
    var res: Seq[ScInfixExpr] = List()

    psiSbtFile.acceptChildren(new ScalaElementVisitor {
      override def visitInfixExpression(infix: ScInfixExpr): Unit = {
        if (infix.left.textMatches(LIBRARY_DEPENDENCIES) && infix.getParent.isInstanceOf[PsiFile]) {
          res = res ++ Seq(infix)
        }
      }
    })

    res
  }

  /** Creates a file-level insertion candidate; callers decide whether it belongs to the selected project. */
  def getTopLevelPlaceToAdd(psiFile: ScalaFile)(implicit project: Project): Option[DependencyOrRepositoryPlaceInfo] = {
    val line: Int = StringUtil.offsetToLineNumber(psiFile.charSequence, psiFile.getTextLength) + 1
    getRelativePath(psiFile).map { relpath =>
      DependencyOrRepositoryPlaceInfo(relpath, psiFile.getTextLength, line, psiFile, Seq())
    }
  }

  /** Dispatches dependency insertion to supported assignment, collection, settings-call or file containers. */
  def addDependency(expr: PsiElement, info: SbtArtifactInfo)(implicit project: Project): Option[PsiElement] = {
    expr match {
      case e: ScInfixExpr if e.left.textMatches(LIBRARY_DEPENDENCIES) => addDependencyToLibraryDependencies(e, info)
      case call: ScMethodCall if call.deepestInvokedExpr.textMatches(SEQ) | call.deepestInvokedExpr.textMatches(LIST) =>
        addDependencyToSeq(call, info)
      case typedSeq: ScTypedExpression if typedSeq.isSequenceArg => addDependencyToTypedSeq(typedSeq, info)
      case settings: ScMethodCall                                =>
        settings.getEffectiveInvokedExpr match {
          case expr: ScReferenceExpression if SbtDependencyUtils.isSettings(expr.refName) =>
            Option(addDependencyToSettings(settings, info))
          case _ => None
        }
      case file: PsiFile =>
        Option(addDependencyToFile(file, info)(using project))
      case _ => None
    }
  }

  /** Appends a repository to the containing build file when available. */
  def addRepository(expr: PsiElement, unifiedDependencyRepository: UnifiedDependencyRepository)(implicit
    project: Project
  ): Option[PsiElement] = {
    expr match {
      case file: PsiFile =>
        Option(addRepositoryToFile(file, unifiedDependencyRepository)(using project))
      case _ => None
    }
  }

  /** Extends supported libraryDependencies assignments, preserving the distinction between single and sequence values.
   */
  def addDependencyToLibraryDependencies(infix: ScInfixExpr, info: SbtArtifactInfo)(implicit
    project: Project
  ): Option[PsiElement] = {

    val psiFile = infix.getContainingFile

    infix.operation.refName match {
      case "+=" =>
        val dependency: ScExpression = infix.right
        val seqCall: ScMethodCall    = generateSeqPsiMethodCall(infix)

        doInSbtWriteCommandAction(
          {
            seqCall.args.addExpr(dependency.copy().asInstanceOf[ScExpression])
            seqCall.args.addExpr(generateArtifactPsiExpression(info, infix))
            infix.operation.replace(ScalaPsiElementFactory.createElementFromText("++=", infix)(using project))
            dependency.replace(seqCall)
          },
          psiFile
        )

        Option(infix.right)

      case "++=" =>
        val dependencies: ScExpression = infix.right
        dependencies match {
          case call: ScMethodCall
              if call.deepestInvokedExpr.textMatches(SEQ) || call.deepestInvokedExpr.textMatches(LIST) =>
            val addedExpr = generateArtifactPsiExpression(info, call)
            doInSbtWriteCommandAction(call.args.addExpr(addedExpr), psiFile)
            Option(addedExpr)
          case subInfix: ScInfixExpr if subInfix.operation.refName == "++" =>
            doInSbtWriteCommandAction(
              subInfix.replace(
                ScalaPsiElementFactory.createExpressionFromText(
                  s"${subInfix.getText} ++ Seq(${generateArtifactText(info)})",
                  infix
                )(using project)
              ),
              psiFile
            )
            Option(infix.right)
          case _ => None
        }

      case _ => None
    }
  }

  /** Appends a generated dependency argument to a supported collection call in a write command. */
  def addDependencyToSeq(seqCall: ScMethodCall, info: SbtArtifactInfo)(implicit
    project: Project
  ): Option[PsiElement] = {
    val addedExpr =
      if (!seqCall.`type`().getOrAny.canonicalText.contains(SBT_SETTING_TYPE))
        generateArtifactPsiExpression(info, seqCall)
      else generateLibraryDependency(info, seqCall)
    doInSbtWriteCommandAction(seqCall.args.addExpr(addedExpr), seqCall.getContainingFile)
    Some(addedExpr)
  }

  /** Inserts into a supported typed collection expression; unsupported shapes yield None. */
  def addDependencyToTypedSeq(typedSeq: ScTypedExpression, info: SbtArtifactInfo): Option[PsiElement] =
    typedSeq.expr match {
      case seqCall: ScMethodCall =>
        val addedExpr = generateLibraryDependency(info, typedSeq)
        doInSbtWriteCommandAction(
          {
            seqCall.args.addExpr(addedExpr)
          },
          seqCall.getContainingFile
        )
        Option(addedExpr)
      case _ => None
    }

  /** Appends a new libraryDependencies += statement to the build file in a write command. */
  def addDependencyToFile(file: PsiFile, info: SbtArtifactInfo)(implicit project: Project): PsiElement = {
    var addedExpr: PsiElement = null
    doInSbtWriteCommandAction(
      {
        file.addAfter(generateNewLine(using project), file.getLastChild)
        addedExpr = file.addAfter(generateLibraryDependency(info, file), file.getLastChild)
      },
      file
    )
    addedExpr
  }

  /** Appends a generated libraryDependencies += argument to a settings call. */
  def addDependencyToSettings(settings: ScMethodCall, info: SbtArtifactInfo): PsiElement = {
    val addedExpr = generateLibraryDependency(info, settings)
    doInSbtWriteCommandAction(
      {
        settings.args.addExpr(addedExpr)
      },
      settings.getContainingFile
    )
    addedExpr
  }

  def addRepositoryToFile(file: PsiFile, unifiedDependencyRepository: UnifiedDependencyRepository)(implicit
    project: Project
  ): PsiElement = {
    var addedExpr: PsiElement = null
    doInSbtWriteCommandAction(
      {
        file.addAfter(generateNewLine(using project), file.getLastChild)
        addedExpr = file.addAfter(generateResolverPsiExpression(unifiedDependencyRepository, file), file.getLastChild)
      },
      file
    )
    addedExpr
  }

  def isAddableLibraryDependencies(libDeps: ScInfixExpr): Boolean =
    libDeps.operation.refName match {
      case "+=" | "++=" => true
      case _            => false
    }

  private def doInSbtWriteCommandAction[T](f: => T, psiSbtFile: PsiFile): T =
    WriteCommandAction
      .writeCommandAction(psiSbtFile)
      .compute(() => f)

  private def generateSeqPsiMethodCall(ctx: PsiElement): ScMethodCall =
    ScalaPsiElementFactory.createElementFromText[ScMethodCall](s"$SEQ()", ctx)(using ctx)

  private def generateLibraryDependency(info: SbtArtifactInfo, ctx: PsiElement): ScInfixExpr =
    ScalaPsiElementFactory.createElementFromText[ScInfixExpr](
      s"$LIBRARY_DEPENDENCIES += ${generateArtifactText(info)}",
      ctx
    )(using ctx)

  def generateArtifactPsiExpression(info: SbtArtifactInfo, ctx: PsiElement): ScExpression =
    ScalaPsiElementFactory.createElementFromText[ScExpression](
      generateArtifactText(info),
      ctx
    )(using ctx)

  private def generateNewLine(implicit ctx: ProjectContext): PsiElement =
    ScalaPsiElementFactory.createNewLine()

  def generateArtifactText(info: SbtArtifactInfo): String =
    generateArtifactTextVerbose(info.groupId, info.artifactId, info.version, info.configuration)

  /** Builds a legacy quoted coordinate expression with optional configuration. A trailing numeric artifact suffix is
   *  converted to %% syntax; this is not a general cross-version evaluator.
   */
  def generateArtifactTextVerbose(
    groupId: String,
    artifactId: String,
    version: String,
    configuration: String
  ): String = {
    var artifactText = ""
    if (artifactId.matches("^.+_\\d+.*$"))
      artifactText += s""""${groupId}" %% "${artifactId.replaceAll("_\\d+.*$", "")}" % "${version}""""
    else
      artifactText += s""""${groupId}" % "${artifactId}" % "${version}""""

    if (configuration != SbtDependencyCommon.defaultLibScope) {
      artifactText += s""" % $configuration"""
    }
    artifactText
  }

  def generateResolverText(unifiedDependencyRepository: UnifiedDependencyRepository): String =
    s"""resolvers += Resolver.url("${unifiedDependencyRepository.getId}", url("${unifiedDependencyRepository.getUrl}"))"""

  def generateResolverPsiExpression(
    unifiedDependencyRepository: UnifiedDependencyRepository,
    ctx: PsiElement
  ): ScExpression =
    ScalaPsiElementFactory.createExpressionFromText(generateResolverText(unifiedDependencyRepository), ctx)(using ctx)

  def getRelativePath(elem: PsiElement)(implicit project: ProjectContext): Option[String] = {
    for {
      path <- Option(elem.getContainingFile.getVirtualFile.getCanonicalPath)
      if path.startsWith(project.getBasePath)
    } yield path.substring(project.getBasePath.length + 1)
  }

  def toDependencyPlaceInfo(elem: PsiElement, affectedProjects: Seq[String])(implicit
    ctx: ProjectContext
  ): Option[DependencyOrRepositoryPlaceInfo] = {
    val offset =
      elem match {
        case call: ScMethodCall =>
          call.getEffectiveInvokedExpr match {
            case expr: ScReferenceExpression => expr.nameId.getTextOffset
            case _                           => elem.getTextOffset
          }
        case _ => elem.getTextOffset
      }

    val line: Int = StringUtil.offsetToLineNumber(elem.getContainingFile.charSequence, offset) + 1

    getRelativePath(elem).map { relpath =>
      DependencyOrRepositoryPlaceInfo(relpath, offset, line, elem, affectedProjects)
    }
  }

  /** Finds build.sbt beside a build-definition content root, then build.sbt or another .sbt file inside a root. */
  def getSbtFileFromBuildModule(buildModule: OpenapiModule): Option[VirtualFile] = {
    val buildSbt = ModuleRootManager
      .getInstance(buildModule)
      .getContentRoots
      .flatMap(f => f.getParent.findChild(Sbt.BuildFile).toOption)

    val otherSbtFiles = ModuleRootManager
      .getInstance(buildModule)
      .getContentRoots
      .flatMap(f => f.getChildren.filter(_.getName.endsWith(Sbt.Extension)))

    buildSbt.headOption
      .orElse(otherSbtFiles.find(_.getName == Sbt.BuildFile))
      .orElse(otherSbtFiles.headOption)
  }

  /** Finds the imported build-definition module whose build URI matches the selected sbt module. */
  def getBuildModule(module: OpenapiModule): Option[OpenapiModule] = {
    val project       = module.getProject
    val moduleManager = ModuleManager.getInstance(project)
    val buildModules  = for {
      moduleData    <- getSbtModuleData(module).to(Seq)
      m             <- moduleManager.getModules
      sbtModuleData <- getBuildModuleData(project, m)
      if moduleData.buildURI == sbtModuleData.buildFor
    } yield m
    buildModules.headOption // we only expect zero or one here
  }

  /** Finds an sbt file through the matching build-definition module, falling back to the supplied module content roots.
   */
  def getSbtFileOpt(module: OpenapiModule): Option[VirtualFile] =
    getBuildModule(module)
      .flatMap(getSbtFileFromBuildModule)
      .orElse(getSbtFileFromBuildModule(module)) // if module is itself a build module

  /** Resolves declarations for the imported sbt identity under read access, retaining use-site scopes and definitions.
   *  Shared or computed containers may remain navigable without being editable. Recoverable resolution failures return
   *  an empty list; IDE control-flow exceptions propagate.
   */
  def declaredDependencies(module: OpenapiModule): java.util.List[DeclaredDependency] = try {
    inReadAction {
      val platform = if (module.isScalaJs) "js" else if (module.isScalaNative) "native" else "jvm"
      val roots    = getSbtFileOpt(module).toSeq.flatMap { build =>
        // sbt loads every .sbt file in a project directory, not only build.sbt.
        val directories = Seq(
          Option(build.getParent),
          getSbtModuleData(module).flatMap(data =>
            Option(com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(data.baseDirectory))
          )
        ).flatten.distinct
        directories.flatMap(_.getChildren).filter(_.getExtension == "sbt").toSeq
      }.distinct.flatMap(f =>
        Option(PsiManager.getInstance(module.getProject).findFile(f)).collect { case file: ScalaFile =>
          file
        }
      )
      val expressions = roots.flatMap { file =>
        val projects  = bitlap.sbt.analyzer.parsing.SbtProjectResolver.projects(file)
        val selected  = projects.filter(DependencyUtils.containsModuleName(_, module))
        val settings  = selected.flatMap(bitlap.sbt.analyzer.parsing.SbtProjectResolver.settings(_, platform))
        val base      = getSbtModuleData(module).map(_.baseDirectory.toPath.toAbsolutePath.normalize())
        val localFile = Option(file.getVirtualFile).exists(f =>
          base.contains(java.nio.file.Path.of(f.getParent.getPath).toAbsolutePath.normalize())
        )
        val id       = org.jetbrains.sbt.SbtUtil.getSbtProjectUriAndId(module).map(_.id)
        val topLevel = file.getChildren.toSeq.collect {
          case e: ScInfixExpr if bitlap.sbt.analyzer.parsing.SbtDeclarationResolver.dependencyKey(e.left) => e
        }.filter(e => id.exists(bitlap.sbt.analyzer.parsing.SbtProjectResolver.appliesTo(e.left, _, localFile)))
        settings ++ topLevel
      }
      expressions
        .flatMap(
          bitlap.sbt.analyzer.parsing.SbtDeclarationResolver
            .dependencies(_, org.jetbrains.sbt.SbtUtil.getSbtProjectUriAndId(module).map(_.id))
        )
        .distinctBy(e => (e.site, e.core, e.scope))
        .map { entry =>
          val manager     = SmartPointerManager.getInstance(module.getProject)
          val declaration = bitlap.sbt.analyzer.parsing.DependencyDeclaration(
            manager.createSmartPsiElementPointer(entry.core),
            entry.scope,
            manager.createSmartPsiElementPointer(entry.site),
            entry.editable,
            entry.cross
          )
          val dataContext: DataContext = (dataId: String) => {
            if (bitlap.sbt.analyzer.parsing.DependencyDeclaration.Key.is(dataId)) declaration
            else if (CommonDataKeys.PSI_ELEMENT.is(dataId)) declaration.expression.getElement
            else null
          }
          // Matching retains the declared artifact; cross-version normalization belongs in the matcher.
          new DeclaredDependency(
            new UnifiedDependency(entry.group, entry.artifact, entry.version, entry.scope),
            dataContext
          )
        }
        .toList
        .asJava
    }
  } catch {
    case c: ControlFlowException =>
      throw c
    case e: Exception =>
      LOG.warn(
        s"Error occurs when obtaining the list of dependencies for module ${module.getName} using package search plugin",
        e
      )
      Collections.emptyList()
  }
}
