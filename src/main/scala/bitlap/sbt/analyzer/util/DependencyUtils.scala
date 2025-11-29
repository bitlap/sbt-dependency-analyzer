package bitlap
package sbt
package analyzer
package util

import java.util.concurrent.atomic.AtomicLong

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import bitlap.sbt.analyzer.model.*
import bitlap.sbt.analyzer.parsing.*
import bitlap.sbt.analyzer.util.SbtDependencyUtils

import org.jetbrains.plugins.scala.extensions.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition
import org.jetbrains.plugins.scala.project.*
import org.jetbrains.sbt.SbtUtil as SSbtUtil

import com.intellij.buildsystem.model.DeclaredDependency
import com.intellij.openapi.externalSystem.dependency.analyzer.DAScope
import com.intellij.openapi.externalSystem.model.project.dependencies.*
import com.intellij.openapi.module.Module as OpenapiModule
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

object DependencyUtils {

  final val DEFAULT_CONFIGURATION = toDAScope("default")

  private final val rootId       = new AtomicLong(0)
  private final val artifactId   = new AtomicLong(0)
  private val SBT_ARTIFACT_REGEX = "(.*):(.*):(.*)".r

  val SCALA_VERSION_PATTERN: Regex =
    """^([a-zA-Z0-9-]+?)(?:_(?:sjs\d+(?:\.\d+)?|native\d*(?:\.\d+)?|2\.1[123]|3))+$""".r

  private final case class PlatformModule(
    module: String,
    scalaVersion: String
  )

  def getDeclaredDependency(module: OpenapiModule): List[DeclaredDependency] = {
    SbtDependencyUtils.declaredDependencies(module).asScala.toList
  }

  /** Handles the processing of ProjectDependencyNodeImpl objects by converting them to a unified DependencyNode type
   *  and applying filtering logic, which is critical for dependency graph/tree operations as this node serves as the
   *  root of the structure.
   */
  def isSelfNode(dn: DependencyNode, context: AnalyzerContext): Boolean = {
    dn.getDisplayName match
      case SBT_ARTIFACT_REGEX(group, artifact, _) =>
        context.organization == group && isSubModule(artifact, context)
      case _ => false
  }

  def getArtifactInfoFromDisplayName(displayName: String): Option[ArtifactInfo] = {
    displayName match
      case SBT_ARTIFACT_REGEX(group, artifact, version) =>
        Some(ArtifactInfo(artifactId.getAndIncrement().toInt, group, artifact, version))
      case _ => None
  }

  def toDAScope(name: String): DAScope = DAScope(name, StringUtil.toTitleCase(name))

  /** Skip analysis for this module */
  def canIgnoreModule(module: OpenapiModule): Boolean = {
    // if module is itself a build module, skip build module
    val isBuildModule = module.isBuildModule
    isBuildModule || module.isSharedSourceModule
  }

  def getScopedCommandKey(project: String, scope: DependencyScopeEnum, cmd: String): String = {
    if (project == null || project.isEmpty) s"$scope / $cmd"
    else s"$project / $scope / $cmd"
  }

  def analysisFilePath(scope: DependencyScopeEnum, parserTypeEnum: DependencyGraphType): String =
    s"/target/dependencies-${scope.toString.toLowerCase}.${parserTypeEnum.suffix}"

  def createRootScopeNode(dependencyScope: DependencyScopeEnum, project: Project): DependencyScopeNode = {
    val scopeDisplayName = "project " + project.getBasePath + " (" + dependencyScope.toString + ")"
    val node             = new DependencyScopeNode(
      rootId.getAndIncrement(),
      dependencyScope.toString,
      scopeDisplayName,
      dependencyScope.toString
    )
    node.setResolutionState(ResolutionState.RESOLVED)
    node
  }

  def appendChildrenAndFixProjectNodes[N <: DependencyNode](
    parentNode: N,
    nodes: Seq[DependencyNode],
    context: AnalyzerContext
  ): Unit = {
    parentNode.getDependencies.addAll(nodes.asJava)
    val moduleDependencies = nodes.filter(d => isProjectModule(d, context))
    parentNode.getDependencies.removeIf(node => moduleDependencies.exists(_.getId == node.getId))
    val mds = moduleDependencies.map(d => toProjectDependencyNode(d, context)).collect { case Some(value) =>
      value
    }
    parentNode.getDependencies.addAll(mds.asJava)

    mds.filter(_.isInstanceOf[ArtifactDependencyNodeImpl]).foreach { node =>
      val artifact   = getArtifactInfoFromDisplayName(node.getDisplayName)
      val artifactId = artifact.map(_.artifact).getOrElse(Constants.EMPTY_STRING)
      val group      = artifact.map(_.group).getOrElse(Constants.EMPTY_STRING)
      // Use artifact to determine whether there are modules in the dependency.
      if (
        context.moduleIdArtifactIdsCache.values
          .exists(d => group == context.organization && getArtifactWithoutScalaVersion(artifactId) == d)
      ) {
        appendChildrenAndFixProjectNodes(
          node,
          node.getDependencies.asScala.toList,
          context
        )
      }
    }
  }

  private def isSubModule(maybeModule: String, context: AnalyzerContext): Boolean = {
    // Handles the cross-platform, module name is not equals to artifact!
    val currentModuleName =
      context.moduleIdArtifactIdsCache.getOrElse(
        context.currentModuleId,
        context.moduleIdArtifactIdsCache.getOrElse(
          Constants.SINGLE_SBT_MODULE,
          context.moduleIdArtifactIdsCache.getOrElse(Constants.ROOT_SBT_MODULE, context.currentModuleId)
        )
      )

    // NOTE: we don't determine the Scala version number.
    maybeModule match {
      case SCALA_VERSION_PATTERN(module) => module.equalsIgnoreCase(currentModuleName)
      case _                             => maybeModule.equalsIgnoreCase(currentModuleName)
    }
  }

  def getArtifactWithoutScalaVersion(artifact: String): String = {
    artifact match
      case SCALA_VERSION_PATTERN(module) => module
      case _                             => artifact
  }

  private def toProjectDependencyNode(dn: DependencyNode, context: AnalyzerContext): Option[DependencyNode] = {
    val artifactInfo = getArtifactInfoFromDisplayName(dn.getDisplayName).orNull
    if (artifactInfo == null) return None
    val sbtModuleName  = getArtifactWithoutScalaVersion(artifactInfo.artifact)
    val ideaModuleName = context.moduleIdArtifactIdsCache.find(_._2 == sbtModuleName).map(_._1)

    // Processing cross-platform, module name is not artifact
    // This is a project node, we need a module not an artifact to get project path!

    val fixedCustomName = context.moduleNamePathsCache.map { case (name, path) =>
      if (name.exists(_ == ' '))
        name.toLowerCase.replace(' ', '-') -> path
      else
        name -> path
    }

    val projectPath =
      ideaModuleName
        .flatMap(m => context.moduleNamePathsCache.get(m))
        .getOrElse(
          context.moduleNamePathsCache
            .getOrElse(sbtModuleName, fixedCustomName.getOrElse(sbtModuleName.toLowerCase, Constants.EMPTY_STRING))
        )

    val p = new ProjectDependencyNodeImpl(
      dn.getId,
      sbtModuleName,
      projectPath
    )
    if (p.getProjectPath.isEmpty) {
      p.setResolutionState(ResolutionState.UNRESOLVED)
    } else {
      p.setResolutionState(ResolutionState.RESOLVED)
    }
    p.getDependencies.addAll(
      dn.getDependencies.asScala
        .filterNot(d => isSelfNode(d, context.copy(currentModuleId = sbtModuleName)))
        .asJava
    )
    Some(p)
  }

  private def isProjectModule(dn: DependencyNode, context: AnalyzerContext): Boolean = {
    // module dependency
    val artifactInfo = getArtifactInfoFromDisplayName(dn.getDisplayName).orNull
    if (artifactInfo == null) return false
    if (artifactInfo.group != context.organization) return false
    // Use artifacts to determine if there are dependent modules
    val matchModule =
      context.moduleIdArtifactIdsCache.values.filter(m => m == getArtifactWithoutScalaVersion(artifactInfo.artifact))

    matchModule.nonEmpty

  }

  def containsModuleName(proj: ScPatternDefinition, module: OpenapiModule): Boolean = {
    val project    = module.getProject
    val moduleName = module.getName
    val settings   = SSbtUtil.sbtSettings(project)
    val moduleData = SSbtUtil.getSbtModuleDataNode(module)
    if (moduleData.isEmpty) {
      return false
    }
    def isEqualModule(name: String) =
      proj.getText.toLowerCase.contains(("\"" + name + "\"").toLowerCase) ||
      proj.getText.toLowerCase.contains(
        ("lazy val `" + name + "`").toLowerCase
      ) ||                                                                  // if project doesn't set module name
      proj.getText.toLowerCase.contains(("val `" + name + "`").toLowerCase) // if project doesn't set module name

    val projectSettings = settings.getLinkedProjectSettings(module).orNull
    if (projectSettings == null) {
      return false
    }
    val moduleExists  = proj.getText.toLowerCase.contains("\"" + moduleName + "\"".toLowerCase)
    val fixModuleName = if (!projectSettings.isUseQualifiedModuleNames && moduleName.exists(_ == '-')) {
      isEqualModule(moduleName)
    } else {
      // hard code
      if (projectSettings.isUseQualifiedModuleNames && moduleName.exists(_ == '.')) {
        moduleName.count(_ == ' ') match
          case 1 =>
            val mm = moduleName.split(' ').last // root.Circe core.coreJs
            if (mm.count(_ == '.') == 1) {
              if (module.isScalaJs || module.isScalaNative) isEqualModule(mm.split('.').head) // core.coreJs
              else isEqualModule(mm.split('.').last) || isEqualModule(mm.split('.').head)     // zim.zim-api
            } else isEqualModule(mm)

          case i if i > 1 =>
            // root.Circe scalafix internal input
            isEqualModule(moduleName.split(' ').tail.mkString("/")) || isEqualModule(
              moduleName.split(' ').tail.mkString("-")
            ) ||
            // root.Circe numbers testing.numbersTestingJS
            isEqualModule(moduleName.split(' ').last.split('.').head.replace(" ", "-"))
          case 0 =>
            // pekko-root.pekko.actor, pekko-root.pekko-actor
            val splits = moduleName.split('.')
            if (moduleName.exists(_ == '-')) {
              isEqualModule(splits.last) ||
              isEqualModule(splits.last.replace("-", ".")) ||
              isEqualModule(splits.last.split('-').tail.mkString("-")) ||
              isEqualModule(splits.last.split('.').tail.mkString("."))
            } else isEqualModule(moduleName) || isEqualModule(splits.last)
      } else {
        isEqualModule(moduleName)
      }
    }
    moduleExists || fixModuleName
  }
}
