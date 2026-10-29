package bitlap.sbt.analyzer.jbexternal

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionStub
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.OverridingAction
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerAction
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerView
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import com.intellij.openapi.externalSystem.view.ExternalSystemNode
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil

/**
 * Keeps Gradle's toolbar and popup entries out of sbt contexts without changing their other contexts.
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

    private fun isSbtContext(e: AnActionEvent): Boolean {
        e.getData(ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID)?.let { return it.id == "SBT" }
        val nodes = e.getData(ExternalSystemDataKeys.SELECTED_NODES)
        if (!nodes.isNullOrEmpty()) {
            return nodes.all { node ->
                generateSequence(node) { it.parent as? ExternalSystemNode<*> }
                    .mapNotNull { it.data as? AbstractExternalEntityData }
                    .firstOrNull()?.owner?.id == "SBT"
            }
        }
        val modules = e.getData(LangDataKeys.MODULE_CONTEXT_ARRAY)?.toList()
            ?: listOfNotNull(e.getData(LangDataKeys.MODULE))
        return modules.isNotEmpty() && modules.all {
            ExternalSystemApiUtil.isExternalSystemAwareModule(ProjectSystemId("SBT"), it)
        }
    }

    override fun getSystemId(e: AnActionEvent): ProjectSystemId? = original.getSystemId(e)

    override fun isEnabledAndVisible(e: AnActionEvent): Boolean =
        !isSbtContext(e) && original.isEnabledAndVisible(e)

    override fun setSelectedState(view: DependencyAnalyzerView, e: AnActionEvent) =
        original.setSelectedState(view, e)

    override fun actionPerformed(e: AnActionEvent) {
        // Let the platform action invoke our delegated system/selection hooks.
        if (!isSbtContext(e)) super.actionPerformed(e)
    }
}
