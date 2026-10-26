package bitlap.sbt.analyzer.action

import bitlap.sbt.analyzer.*
import bitlap.sbt.analyzer.parsing.{ AnalyzerCacheService, DependencyDeclaration }
import bitlap.sbt.analyzer.util.{ Notifications, SbtReimportProject }
import bitlap.sbt.analyzer.util.packagesearch.DependencyEditPlan

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.{ ControlFlowException, Logger }
import com.intellij.openapi.ui.Messages

/** Previews removal of a direct dependency or exclusion of a transitive artifact from its direct declaration. Shared
 *  definitions are not edited automatically; document changes invalidate a prepared preview.
 */
final class SbtDependencyAnalyzerExcludeAction extends BaseRefreshDependenciesAction {
  override lazy val eventText        = SbtDependencyAnalyzerBundle.message("analyzer.action.excludeAction.text")
  override lazy val eventDescription = SbtDependencyAnalyzerBundle.message("analyzer.action.excludeAction.description")

  override def update(e: AnActionEvent): Unit = {
    super.update(e)
    val applicable = SbtDependencyAnalyzerActionUtil.isSbtDependency(e)
    e.getPresentation.setVisible(applicable)
    if (!applicable) {
      e.getPresentation.setEnabled(false)
      return
    }
    if (com.intellij.openapi.project.DumbService.isDumb(e.getProject)) {
      e.getPresentation.setEnabled(false)
      e.getPresentation.setDescription(SbtDependencyAnalyzerBundle.message("analyzer.action.source.indexing"))
      return
    }
    if (e.getPresentation.isEnabled) {
      val target = Option(SbtDependencyAnalyzerActionUtil.getModifiableDependency(e))
      e.getPresentation.setEnabled(
        target.exists(
          _.candidateDeclaredDependencies.exists(d =>
            Option(d.getDataContext.getData(DependencyDeclaration.Key)).exists(_.editable)
          )
        )
      )
      if (!e.getPresentation.isEnabled)
        e.getPresentation.setDescription(SbtDependencyAnalyzerBundle.message("analyzer.action.source.unavailable"))
      target.foreach { t =>
        val remove = getUnifiedCoordinates(t.parentDependency) == t.coordinates
        e.getPresentation.setText(
          SbtDependencyAnalyzerBundle.message(
            if (remove) "analyzer.action.remove.text" else "analyzer.action.exclude.from.text",
            getUnifiedCoordinates(t.parentDependency).getDisplayName
          )
        )
      }
    }
  }

  override def actionPerformed(e: AnActionEvent): Unit = {
    val target = SbtDependencyAnalyzerActionUtil.getModifiableDependency(e)
    if (target == null) return
    val project    = target.module.getProject
    val candidates =
      target.candidateDeclaredDependencies.flatMap(d =>
        Option(d.getDataContext.getData(DependencyDeclaration.Key)).filter(_.editable)
      )
    val declaration = DeclarationChooser.choose(project, eventText, candidates)
    if (declaration.isEmpty) return
    val remove = getUnifiedCoordinates(target.parentDependency) == target.coordinates
    try {
      val plan = DependencyEditPlan.prepare(project, declaration.get, target.coordinates, remove)
      if (DependencyEditPlan.preview(plan, e.getPresentation.getText)) {
        plan.apply()
        AnalyzerCacheService.getInstance(project).declarations.invalidateCache(target.module)
        Notifications.notifyDependencyChanged(project, target.coordinates.getDisplayName, self = remove)

      }
    } catch {
      case c: ControlFlowException => throw c
      case error: Exception        =>
        Logger.getInstance(getClass).warn("Cannot modify dependency", error)
        Messages.showErrorDialog(project, error.getMessage, eventText)
    }
  }
}
