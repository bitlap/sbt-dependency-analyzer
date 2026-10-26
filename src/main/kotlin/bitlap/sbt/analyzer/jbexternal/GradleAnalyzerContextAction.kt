package bitlap.sbt.analyzer.jbexternal

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionStub
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.OverridingAction
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerAction
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerView
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId

/**
 * Keeps Gradle's shared-toolbar entry out of sbt tool windows without changing its other contexts.
 * The optional descriptor declares the override so the platform owns action-group replacement and unload restoration.
 * Base-action lookup is confined here because the platform currently exposes it through ActionManagerImpl.
 */
class GradleAnalyzerContextAction : DependencyAnalyzerAction(), OverridingAction {

    // Declarative overrides can retain a lazy ActionStub as their base. Instantiate it through its owning plugin,
    // leaving the platform's original registration intact so unload can restore it normally.
    private val original: DependencyAnalyzerAction by lazy {
        val base = requireNotNull((ActionManager.getInstance() as ActionManagerImpl).getBaseAction(this))
        if (base is ActionStub) {
            ApplicationManager.getApplication().instantiateClass<DependencyAnalyzerAction>(base.className, base.plugin)
                .also { base.initAction(it) }
        } else {
            base as DependencyAnalyzerAction
        }
    }

    override fun getSystemId(e: AnActionEvent): ProjectSystemId? = original.getSystemId(e)

    override fun isEnabledAndVisible(e: AnActionEvent): Boolean =
        e.getData(ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID)?.id != "SBT" && original.isEnabledAndVisible(e)

    override fun setSelectedState(view: DependencyAnalyzerView, e: AnActionEvent) =
        original.setSelectedState(view, e)

    override fun actionPerformed(e: AnActionEvent) = original.actionPerformed(e)
}
