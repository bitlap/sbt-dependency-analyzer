package bitlap.sbt.analyzer.action

import bitlap.sbt.analyzer.*
import bitlap.sbt.analyzer.parsing.DependencyDeclaration

import org.jetbrains.sbt.project.SbtProjectSystem

import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.externalSystem.dependency.analyzer.*
import com.intellij.openapi.project.DumbService
import com.intellij.pom.Navigatable

class SbtDependencyAnalyzerGoToAction extends DependencyAnalyzerGoToAction(SbtProjectSystem.Id) {
  getTemplatePresentation.setText(SbtDependencyAnalyzerBundle.message("analyzer.action.gotoAction.text"))

  protected def candidates(e: AnActionEvent): List[DependencyDeclaration] =
    Option(SbtDependencyAnalyzerActionUtil.getModifiableDependency(e)).toList
      .flatMap(_.navigationDeclarations)
      .flatMap(d => Option(d.getDataContext.getData(DependencyDeclaration.Key)))
      .filter(_.resolve.nonEmpty)
      .distinctBy(_.expression.getElement)

  override def getNavigatable(e: AnActionEvent): Navigatable = candidates(e) match {
    case List(one) => PsiNavigationSupport.getInstance().getDescriptor(one.expression.getElement)
    case _         => null
  }

  override def update(e: AnActionEvent): Unit = {
    // The platform action hides itself when getNavigatable returns null, including our chooser case.
    val applicable = SbtDependencyAnalyzerActionUtil.isSbtDependency(e)
    val indexing   = applicable && DumbService.isDumb(e.getProject)
    val available  = applicable && !indexing && candidates(e).nonEmpty
    e.getPresentation.setVisible(applicable)
    e.getPresentation.setEnabled(available)
    e.getPresentation.setDescription(
      if (indexing) SbtDependencyAnalyzerBundle.message("analyzer.action.source.indexing")
      else if (applicable && !available) SbtDependencyAnalyzerBundle.message("analyzer.action.source.unavailable")
      else getTemplatePresentation.getDescription
    )
  }

  override def actionPerformed(e: AnActionEvent): Unit = {
    if (!SbtDependencyAnalyzerActionUtil.isSbtDependency(e) || DumbService.isDumb(e.getProject)) return
    val declarations = candidates(e)
    DeclarationChooser
      .choose(e.getProject, e.getPresentation.getText, declarations)
      .flatMap(_.resolve)
      .foreach { (core, _, _) =>
        PsiNavigationSupport.getInstance().getDescriptor(core).navigate(true)
      }
  }
}
