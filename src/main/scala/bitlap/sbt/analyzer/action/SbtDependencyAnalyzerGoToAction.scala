package bitlap.sbt.analyzer.action

import bitlap.sbt.analyzer.*
import bitlap.sbt.analyzer.parsing.DependencyDeclaration

import org.jetbrains.sbt.project.SbtProjectSystem

import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.externalSystem.dependency.analyzer.*
import com.intellij.openapi.ui.Messages
import com.intellij.pom.Navigatable

final class SbtDependencyAnalyzerGoToAction extends DependencyAnalyzerGoToAction(SbtProjectSystem.Id) {
  getTemplatePresentation.setText(SbtDependencyAnalyzerBundle.message("analyzer.action.gotoAction.text"))

  private def candidates(e: AnActionEvent): List[DependencyDeclaration] =
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
    super.update(e)
    e.getPresentation.setEnabled(candidates(e).nonEmpty)
  }

  override def actionPerformed(e: AnActionEvent): Unit = {
    val declarations = candidates(e)
    DeclarationChooser
      .choose(e.getProject, e.getPresentation.getText, declarations)
      .flatMap(_.resolve)
      .foreach { (core, _, _) =>
        PsiNavigationSupport.getInstance().getDescriptor(core).navigate(true)
      }
  }
}
