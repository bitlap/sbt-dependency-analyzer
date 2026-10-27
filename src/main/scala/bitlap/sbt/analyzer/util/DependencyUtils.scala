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

/** Converts parsed artifact nodes to analyzer nodes and matches them against imported sbt project metadata. */
object DependencyUtils {

  final val DEFAULT_CONFIGURATION = toDAScope("default")

  private final val rootId       = new AtomicLong(0)
  private final val artifactId   = new AtomicLong(0)
  private val SBT_ARTIFACT_REGEX = "(.*):(.*):(.*)".r

  val SCALA_VERSION_PATTERN: Regex =
    """^(.+?)(?:_(?:sjs\d+(?:\.\d+)*|native\d*(?:\.\d+)*)(?:_(?:2\.\d+(?:\.\d+)?|3(?:\.\d+)*))?|_(?:2\.\d+(?:\.\d+)?|3(?:\.\d+)*))$""".r

  private final case class PlatformModule(
    module: String,
    scalaVersion: String
  )

  def getDeclaredDependency(module: OpenapiModule): List[DeclaredDependency] = {
    if (module == null || module.isDisposed) Nil
    else AnalyzerCacheService.getInstance(module.getProject).declarations.getDeclaredDependencyWithCache(module)
  }

  /** Recognizes the current project coordinate using its effective organization and mapped moduleName. */
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

  /** Excludes absent/disposed modules, build-definition modules and shared-source modules from analysis. */
  def canIgnoreModule(module: OpenapiModule): Boolean = {
    // if module is itself a build module, skip build module
    if (module == null || module.isDisposed) return true
    val isBuildModule = module.isBuildModule
    isBuildModule || module.isSharedSourceModule
  }

  /** Builds a task key from a project selector and configuration; an empty selector uses the shell current project. */
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

  /** Attaches children and replaces recognized project-artifact nodes with imported project nodes. Matching currently
   *  uses the context organization; unmatched coordinates remain artifacts.
   */
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

  /** Removes a recognized Scala/JVM, Scala.js or Scala Native suffix; leaves other artifact names intact. */
  def getArtifactWithoutScalaVersion(artifact: String): String = {
    artifact match
      case SCALA_VERSION_PATTERN(module) => module
      case _                             => artifact
  }

  private def toProjectDependencyNode(dn: DependencyNode, context: AnalyzerContext): Option[DependencyNode] = {
    val artifactInfo = getArtifactInfoFromDisplayName(dn.getDisplayName).getOrElse(null)
    if (artifactInfo == null) return None
    val sbtModuleName = getArtifactWithoutScalaVersion(artifactInfo.artifact)
    val projectPath   = SbtModuleIdentity
      .projectIdForArtifact(artifactInfo.artifact, context.moduleIdArtifactIdsCache, context.modulePlatforms)
      .flatMap(context.moduleNamePathsCache.get)
      .getOrElse(Constants.EMPTY_STRING)

    // Source-to-module ambiguity is not an sbt resolution failure. Retain the resolved artifact and its children.
    if (projectPath.isEmpty) return Some(dn)
    val p = new ProjectDependencyNodeImpl(
      dn.getId,
      sbtModuleName,
      projectPath
    )
    p.setResolutionState(dn.getResolutionState)
    p.getDependencies.addAll(
      dn.getDependencies.asScala
        .filterNot(d => isSelfNode(d, context.copy(currentModuleId = sbtModuleName)))
        .asJava
    )
    Some(p)
  }

  private def isProjectModule(dn: DependencyNode, context: AnalyzerContext): Boolean = {
    // module dependency
    val artifactInfo = getArtifactInfoFromDisplayName(dn.getDisplayName).getOrElse(null)
    if (artifactInfo == null) return false
    if (artifactInfo.group != context.organization) return false
    // Use artifacts to determine if there are dependent modules
    val matchModule =
      context.moduleIdArtifactIdsCache.values.filter(m => m == getArtifactWithoutScalaVersion(artifactInfo.artifact))

    matchModule.nonEmpty

  }

  /** Matches a source project declaration against the imported sbt ID, independently of its moduleName setting. */
  def containsModuleName(proj: ScPatternDefinition, module: OpenapiModule): Boolean =
    SSbtUtil.getSbtProjectUriAndId(module).exists { identity =>
      bitlap.sbt.analyzer.parsing.SbtProjectResolver.matches(proj, identity.id)
    }
}
