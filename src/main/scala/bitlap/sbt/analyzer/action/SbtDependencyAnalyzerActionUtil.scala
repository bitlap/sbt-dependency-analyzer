package bitlap.sbt.analyzer.action

import bitlap.sbt.analyzer.*
import bitlap.sbt.analyzer.util.DependencyUtils

import com.intellij.buildsystem.model.DeclaredDependency
import com.intellij.buildsystem.model.unified.UnifiedCoordinates
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.externalSystem.dependency.analyzer.*
import com.intellij.openapi.module.Module as OpenapiModule

/** Source context for a selected analyzer node. Navigation candidates describe the selected artifact; edit candidates
 *  describe its direct dependency ancestor. A single unambiguous declaration is also exposed separately for callers
 *  that require it.
 */
final case class ModifiableDependency(
  module: OpenapiModule,
  coordinates: UnifiedCoordinates,
  declaredDependency: Option[DeclaredDependency],
  candidateDeclaredDependencies: List[DeclaredDependency],
  parentDependency: DependencyAnalyzerDependency,
  navigationDeclarations: List[DeclaredDependency]
)

/** Resolves analyzer selection data to imported modules and matching declarations without guessing an ambiguous target.
 */
object SbtDependencyAnalyzerActionUtil {

  def isSbtDependency(e: AnActionEvent): Boolean =
    e.getProject != null && !e.getProject.isDisposed &&
    e.getData(com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID) ==
      org.jetbrains.sbt.project.SbtProjectSystem.Id &&
      e.getData(DependencyAnalyzerView.Companion.getDEPENDENCY) != null

  /** Resolves the selected artifact and its direct ancestor declarations under read access. Returns null when
   *  selection, module identity or smart-mode PSI resolution is unavailable; ambiguity is retained in the candidate
   *  lists for the caller to present.
   */
  def getModifiableDependency(e: AnActionEvent): ModifiableDependency =
    val project    = e.getProject
    val dependency = e.getData(DependencyAnalyzerView.Companion.getDEPENDENCY)
    if (project == null || dependency == null) return null

    val coordinates: UnifiedCoordinates = getUnifiedCoordinates(dependency)
    val parentDependencyAndModule       = getParentModule(project, dependency)
    if (coordinates == null || parentDependencyAndModule == null) return null

    val (parentDependency, module) = parentDependencyAndModule

    if (module == null || module.isDisposed || com.intellij.openapi.project.DumbService.isDumb(project)) return null
    val declarations = DependencyUtils.getDeclaredDependency(module)
    def matches(dc: DeclaredDependency, target: UnifiedCoordinates, versionRequired: Boolean = true): Boolean = {
      val c = dc.getCoordinates
      target != null && DeclarationMatcher.matches(
        c.getGroupId,
        c.getArtifactId,
        if (versionRequired) c.getVersion else target.getVersion,
        dc.getUnifiedDependency.getScope,
        target.getGroupId,
        target.getArtifactId,
        target.getVersion,
        dependency.getScope.getName,
        Option(dc.getDataContext.getData(bitlap.sbt.analyzer.parsing.DependencyDeclaration.Key)).fold("%")(_.cross)
      )
    }
    val directCoordinates             = getUnifiedCoordinates(parentDependency)
    val candidateDeclaredDependencies =
      DeclarationMatcher.select(declarations)(matches(_, directCoordinates), matches(_, directCoordinates, false))
    val selected = DeclarationMatcher.select(declarations)(matches(_, coordinates), matches(_, coordinates, false))
    ModifiableDependency(
      module,
      coordinates,
      selected match { case List(one) => Some(one); case _ => None },
      candidateDeclaredDependencies,
      parentDependency,
      selected
    )

  end getModifiableDependency

}
