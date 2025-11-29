package bitlap.sbt.analyzer.action

import bitlap.sbt.analyzer.*
import bitlap.sbt.analyzer.util.DependencyUtils

import com.intellij.buildsystem.model.DeclaredDependency
import com.intellij.buildsystem.model.unified.UnifiedCoordinates
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.externalSystem.dependency.analyzer.*
import com.intellij.openapi.module.Module as OpenapiModule

final case class ModifiableDependency(
  module: OpenapiModule,
  coordinates: UnifiedCoordinates,
  declaredDependency: Option[DeclaredDependency],
  candidateDeclaredDependencies: List[DeclaredDependency],
  parentDependency: DependencyAnalyzerDependency
)

object SbtDependencyAnalyzerActionUtil {

  def getModifiableDependency(e: AnActionEvent): ModifiableDependency =
    val project    = e.getProject
    val dependency = e.getData(DependencyAnalyzerView.Companion.getDEPENDENCY)
    if (project == null || dependency == null) return null

    val coordinates: UnifiedCoordinates = getUnifiedCoordinates(dependency)
    val parentDependencyAndModule       = getParentModule(project, dependency)
    if (coordinates == null || parentDependencyAndModule == null) return null

    val (parentDependency, module) = parentDependencyAndModule

    val candidateDeclaredDependencies = DependencyUtils
      .getDeclaredDependency(module)
    val declaredDependency = candidateDeclaredDependencies.find(dc =>
      // hard code, see SbtDependencyUtils#getLibraryDependenciesOrPlacesFromPsi
      val artifactWithoutScala         = DependencyUtils.getArtifactWithoutScalaVersion(coordinates.getArtifactId)
      val declaredArtifactWithoutScala = DependencyUtils.getArtifactWithoutScalaVersion(dc.getCoordinates.getArtifactId)
      (dc.getCoordinates.getArtifactId == coordinates.getArtifactId || declaredArtifactWithoutScala == artifactWithoutScala ||
        // maybe a fixed artifact
        dc.getCoordinates.getVersion == artifactWithoutScala) &&
        dc.getCoordinates.getGroupId == coordinates.getGroupId
    )

    ModifiableDependency(module, coordinates, declaredDependency, candidateDeclaredDependencies, parentDependency)
  end getModifiableDependency

}
