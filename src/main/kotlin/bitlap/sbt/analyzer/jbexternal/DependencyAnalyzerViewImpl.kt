// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package bitlap.sbt.analyzer.jbexternal

import bitlap.sbt.analyzer.jbexternal.util.DependencyGroup
import bitlap.sbt.analyzer.jbexternal.util.DependencyGroup.Companion.hasWarnings
import bitlap.sbt.analyzer.jbexternal.util.DependencyList
import bitlap.sbt.analyzer.jbexternal.util.DependencyTree
import bitlap.sbt.analyzer.jbexternal.util.ExternalProjectSelector
import bitlap.sbt.analyzer.jbexternal.util.ProjectUtil
import bitlap.sbt.analyzer.jbexternal.util.ScopeItem
import bitlap.sbt.analyzer.jbexternal.util.SearchScopeSelector
import bitlap.sbt.analyzer.jbexternal.util.UsagesTree
import bitlap.sbt.analyzer.jbexternal.util.action
import bitlap.sbt.analyzer.jbexternal.util.asActionButton
import bitlap.sbt.analyzer.jbexternal.util.cardPanel
import bitlap.sbt.analyzer.jbexternal.util.collapseTreeAction
import bitlap.sbt.analyzer.jbexternal.util.expandTreeAction
import bitlap.sbt.analyzer.jbexternal.util.getDisplayText
import bitlap.sbt.analyzer.jbexternal.util.horizontalPanel
import bitlap.sbt.analyzer.jbexternal.util.horizontalSplitPanel
import bitlap.sbt.analyzer.jbexternal.util.label
import bitlap.sbt.analyzer.jbexternal.util.popupActionGroup
import bitlap.sbt.analyzer.jbexternal.util.separator
import bitlap.sbt.analyzer.jbexternal.util.toggleAction
import bitlap.sbt.analyzer.jbexternal.util.toolWindowPanel
import bitlap.sbt.analyzer.jbexternal.util.toolbarPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectNotificationAware
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerDependency
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerExtension
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerProject
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerView
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerView.Companion.ACTION_PLACE
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.ui.ExternalSystemIconProvider
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle
import com.intellij.openapi.module.Module
import com.intellij.openapi.observable.operation.core.AtomicOperationTrace
import com.intellij.openapi.observable.operation.core.getOperationInProgressProperty
import com.intellij.openapi.observable.operation.core.isOperationInProgress
import com.intellij.openapi.observable.operation.core.withCompletedOperation
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.openapi.observable.util.and
import com.intellij.openapi.observable.util.bind
import com.intellij.openapi.observable.util.bindBooleanStorage
import com.intellij.openapi.observable.util.bindEmptyText
import com.intellij.openapi.observable.util.bindEnabled
import com.intellij.openapi.observable.util.bindLoading
import com.intellij.openapi.observable.util.bindSelected
import com.intellij.openapi.observable.util.bindVisible
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.progress.util.BackgroundTaskUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.addPreferredFocusedComponent
import com.intellij.openapi.util.text.NaturalComparator
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.util.Locale
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import kotlin.collections.ArrayDeque
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.LinkedHashMap
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.Set
import kotlin.collections.all
import kotlin.collections.asSequence
import kotlin.collections.associate
import kotlin.collections.contains
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.find
import kotlin.collections.firstOrNull
import kotlin.collections.flatMap
import kotlin.collections.getOrPut
import kotlin.collections.groupBy
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlin.collections.map
import kotlin.collections.singleOrNull
import kotlin.collections.sortedWith
import kotlin.collections.toSet
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerDependency as Dependency

/**
 * https://github.com/JetBrains/intellij-community/blob/idea/253.20558.43/platform/external-system-impl/src/com/intellij/openapi/externalSystem/dependency/analyzer/DependencyAnalyzerViewImpl.kt
 */
class DependencyAnalyzerViewImpl(
    private val project: Project, private val systemId: ProjectSystemId, private val parentDisposable: Disposable
) : DependencyAnalyzerView {

    private val iconsProvider = ExternalSystemIconProvider.getExtension(systemId)
    private val contributor =
        DependencyAnalyzerExtension.getExtension(systemId).createContributor(project, parentDisposable)

    private val backgroundExecutor =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("DependencyAnalyzerView.backgroundExecutor", 1)

    @Volatile private var disposed = false
    private val modelRevision = java.util.concurrent.atomic.AtomicLong()
    private val filterRevision = java.util.concurrent.atomic.AtomicLong()
    private val usagesRevision = java.util.concurrent.atomic.AtomicLong()
    private var viewComponent: JComponent? = null
    private var loadingError: String? = null
    private val filterTimer = javax.swing.Timer(150) { rebuildFilteredModel() }.apply { isRepeats = false }

    private val dependencyLoadingOperation = AtomicOperationTrace("DA: Dependency loading")
    private val dependencyLoadingProperty = dependencyLoadingOperation.getOperationInProgressProperty()

    private val externalProjectProperty = AtomicProperty<DependencyAnalyzerProject?>(null)
    private val dependencyDataFilterProperty = AtomicProperty("")
    private val dependencyScopeFilterProperty = AtomicProperty(emptyList<ScopeItem>())
    private val showDependencyWarningsProperty = AtomicProperty(false)
    private val showDependencyGroupIdProperty = AtomicProperty(false).bindBooleanStorage(SHOW_GROUP_ID_PROPERTY)
    private val showDependencySizeProperty = AtomicProperty(false).bindBooleanStorage(SHOW_ARTIFACT_SIZE_PROPERTY)

    private val dependencyModelProperty = AtomicProperty(emptyList<DependencyGroup>())
    private val dependencyProperty = AtomicProperty<Dependency?>(null)
    private val showDependencyTreeProperty = AtomicProperty(false).bindBooleanStorage(SHOW_AS_TREE_PROPERTY)
    private val dependencyEmptyTextProperty = AtomicProperty("")
    private val usagesTitleProperty = AtomicProperty("")

    private var externalProject by externalProjectProperty
    private var dependencyDataFilter by dependencyDataFilterProperty
    private var dependencyScopeFilter by dependencyScopeFilterProperty
    private var showDependencyWarnings by showDependencyWarningsProperty
    private var showDependencyGroupId by showDependencyGroupIdProperty

    private var dependencyModel by dependencyModelProperty
    private var dependency by dependencyProperty
    private var dependencyEmptyState by dependencyEmptyTextProperty
    private var usagesTitle by usagesTitleProperty

    private val externalProjects = ArrayList<DependencyAnalyzerProject>()

    private val dependencyListModel = CollectionListModel<DependencyGroup>()
    private val dependencyTreeModel = DefaultTreeModel(null)
    private val usagesTreeModel = DefaultTreeModel(null)

    override fun setSelectedExternalProject(module: Module) {
        setSelectedExternalProject(module) {}
    }

    override fun setSelectedDependency(module: Module, data: Dependency.Data) {
        setSelectedExternalProject(module) {
            dependency = findDependency { it.data == data } ?: dependency
        }
    }

    override fun setSelectedDependency(module: Module, data: Dependency.Data, scope: String) {
        setSelectedExternalProject(module) {
            dependency = findDependency { it.data == data && it.scope.name == scope } ?: dependency
        }
    }

    override fun setSelectedDependency(module: Module, path: List<DependencyAnalyzerDependency.Data>) {
        setSelectedExternalProject(module) {
            dependency = findDependency { d -> getTreePath(d).map { it.data } == path } ?: dependency
        }
    }

    override fun setSelectedDependency(module: Module, path: List<DependencyAnalyzerDependency.Data>, scope: String) {
        setSelectedExternalProject(module) {
            dependency =
                findDependency { d -> d.scope.name == scope && getTreePath(d).map { it.data } == path } ?: dependency
        }
    }

    private fun setSelectedExternalProject(module: Module, onReady: () -> Unit) {
        whenLoadingOperationCompleted {
            externalProject = findExternalProject { it.module == module } ?: externalProject
            whenLoadingOperationCompleted {
                onReady()
            }
        }
    }

    private fun findDependency(predicate: (Dependency) -> Boolean): Dependency? {
        return dependencyListModel.items.flatMap { it.variances }.find(predicate)
            ?: dependencyModel.flatMap { it.variances }.find(predicate)
    }

    private fun findExternalProject(predicate: (DependencyAnalyzerProject) -> Boolean): DependencyAnalyzerProject? {
        return externalProjects.find(predicate)
    }

    private fun updateViewModel() {
        modelRevision.incrementAndGet()
        filterRevision.incrementAndGet()
        usagesRevision.incrementAndGet()
        runInEdt {
            if (!disposed) {
                dependencyModel = emptyList()
                updateExternalProjectsModel()
            }
        }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[DependencyAnalyzerView.VIEW] = this
        sink[CommonDataKeys.PROJECT] = project
        sink[ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID] = systemId
        sink[PlatformCoreDataKeys.MODULE] = externalProject?.module
    }

    private fun Iterable<Dependency>.filterDependencies(): List<Dependency> {
        val dependencyDataFilter = dependencyDataFilter
        val dependencyScopeFilter = dependencyScopeFilter.filter { it.isSelected }.map { it.scope }
        val showDependencyWarnings = showDependencyWarnings
        return filter { dependency ->
            dependencyDataFilter.lowercase(Locale.ENGLISH) in dependency.data.getDisplayText(showDependencyGroupId)
                .lowercase(Locale.ENGLISH)
        }.filter { dependency -> dependency.scope in dependencyScopeFilter }
            .filter { dependency -> if (showDependencyWarnings) dependency.hasWarnings else true }
    }

    private fun updateExternalProjectsModel() {
        externalProjects.clear()
        executeLoadingTask(onBackgroundThread = {
            contributor.getProjects()
        }, onUiThread = { projects ->
            externalProjects.addAll(projects)

            val selected = externalProjects.find { it == externalProject } ?: externalProjects.firstOrNull()
            if (selected == externalProject) {
                updateScopesModel()
                updateDependencyModel()
            } else externalProject = selected
        })
    }

    private fun updateScopesModel() {
        val selectedProject = externalProject
        executeLoadingTask(onBackgroundThread = {
            selectedProject?.let(contributor::getDependencyScopes) ?: emptyList()
        }, onUiThread = { scopes ->
            val scopesIndex = dependencyScopeFilter.associate { it.scope to it.isSelected }
            val isAny = scopesIndex.all { it.value }
            dependencyScopeFilter = scopes.map { ScopeItem(it, scopesIndex[it] ?: isAny) }
        })
    }

    private fun updateDependencyModel() {
        dependencyModel = emptyList()
        val selectedProject = externalProject
        val groupId = showDependencyGroupId
        executeLoadingTask(onBackgroundThread = {
            (selectedProject?.let(contributor::getDependencies) ?: emptyList()).collectAllDependencies().createDependencyGroups(groupId)
        }, onUiThread = { groups -> dependencyModel = groups })
    }

    private fun updateFilteredDependencyModel() {
        if (disposed) return
        filterRevision.incrementAndGet()
        filterTimer.restart()
    }

    private fun rebuildFilteredModel() {
        if (disposed) return
        val revision = filterRevision.get()
        val model = dependencyModel
        val query = dependencyDataFilter.lowercase(Locale.ROOT)
        val scopes = dependencyScopeFilter.filter { it.isSelected }.map { it.scope }.toSet()
        val warningsOnly = showDependencyWarnings
        val groupId = showDependencyGroupId
        backgroundExecutor.execute {
            val groups = model.mapNotNull { group ->
                val matches = group.variances.filter {
                    query in it.data.getDisplayText(groupId).lowercase(Locale.ROOT) && it.scope in scopes && (!warningsOnly || it.hasWarnings)
                }
                if (matches.isEmpty()) null else DependencyGroup(matches)
            }
            val dependencies = groups.flatMap { it.variances }
            val tree = buildTree(dependencies, groupId)
            javax.swing.SwingUtilities.invokeLater {
                if (!disposed && revision == filterRevision.get()) {
                    dependencyListModel.replaceAll(groups)
                    dependencyTreeModel.setRoot(tree)
                    dependency = dependencies.find { it == dependency } ?: dependencies.firstOrNull()
                }
            }
        }
    }

    private fun updateDependencyEmptyState() {
        runInEdt {
            dependencyEmptyState = when {
                dependencyLoadingOperation.isOperationInProgress() -> ""
                else -> loadingError ?: ExternalSystemBundle.message("external.system.dependency.analyzer.resolved.empty")
            }
        }
    }

    private fun updateUsagesTitle() {
        val text = dependency?.data?.getDisplayText(showDependencyGroupId)
        usagesTitle = if (text == null) "" else ExternalSystemBundle.message(
            "external.system.dependency.analyzer.usages.title", text
        )
    }

    private fun updateUsagesModel() {
        if (disposed) return
        val revision = usagesRevision.incrementAndGet()
        val selected = dependency?.data
        val model = dependencyModel
        val groupId = showDependencyGroupId
        backgroundExecutor.execute {
            val matches = model.filter { group -> group.variances.any { it.data == selected } }.flatMap { it.variances }
            val tree = buildTree(matches, groupId)
            javax.swing.SwingUtilities.invokeLater {
                if (!disposed && revision == usagesRevision.get()) usagesTreeModel.setRoot(tree)
            }
        }
    }

    private fun <R> executeLoadingTask(onBackgroundThread: () -> R, onUiThread: (R) -> Unit) {
        if (disposed) return
        val revision = modelRevision.get()
        dependencyLoadingOperation.traceStart()
        loadingError = null
        backgroundExecutor.execute {
            val result = try { Result.success(onBackgroundThread()) } catch (error: Exception) { Result.failure(error) }
            javax.swing.SwingUtilities.invokeLater {
                try {
                    if (!disposed && revision == modelRevision.get()) {
                        result.fold(onSuccess = onUiThread, onFailure = { error ->
                            if (error !is com.intellij.openapi.progress.ProcessCanceledException) {
                                loadingError = error.message ?: error.toString()
                                logger<DependencyAnalyzerView>().warn("Dependency analysis failed", error)
                                com.intellij.notification.NotificationGroupManager.getInstance()
                                    .getNotificationGroup("Sbt.DependencyAnalyzer.Notification")
                                    .createNotification(loadingError!!, com.intellij.notification.NotificationType.ERROR)
                                    .addAction(com.intellij.notification.NotificationAction.createSimple(
                                        SbtDependencyExternalBundle.message("analyzer.external.retry")) { updateViewModel() })
                                    .notify(project)
                            }
                        })
                    }
                } finally { dependencyLoadingOperation.traceFinish() }
            }
        }
    }

    private fun whenLoadingOperationCompleted(onUiThread: () -> Unit) {
        dependencyLoadingOperation.withCompletedOperation(parentDisposable) {
            runInEdt {
                onUiThread()
            }
        }
    }

    private fun buildTree(dependencies: Iterable<Dependency>, groupId: Boolean = showDependencyGroupId): DefaultMutableTreeNode? {
        val dependenciesForTree = dependencies.collectAllDependencies()

        if (dependenciesForTree.isEmpty()) {
            return null
        }
        val rootDependencyGroup =
            dependenciesForTree.filter { it.parent == null }.createDependencyGroups(groupId).singleOrNull()
        if (rootDependencyGroup == null) {
            val rawTree = dependenciesForTree.joinToString("\n")
            logger<DependencyAnalyzerView>().error("Cannot determine root of dependency tree:\n$rawTree")
            return null
        }

        val nodeMap = LinkedHashMap<Dependency, MutableList<Dependency>>()
        for (dependency in dependenciesForTree) {
            val usage = dependency.parent ?: continue
            val children = nodeMap.getOrPut(usage) { ArrayList() }
            children.add(dependency)
        }

        val rootNode = DefaultMutableTreeNode(rootDependencyGroup)
        val queue = ArrayDeque<DefaultMutableTreeNode>()
        queue.addLast(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val dependencyGroup = node.userObject as DependencyGroup
            val children = dependencyGroup.variances.flatMap { nodeMap[it] ?: emptyList() }.createDependencyGroups(groupId)
            for (child in children) {
                val childNode = DefaultMutableTreeNode(child)
                node.add(childNode)
                queue.addLast(childNode)
            }
        }
        return rootNode
    }

    private fun Iterable<Dependency>.collectAllDependencies(): Set<Dependency> {
        val result = LinkedHashSet<Dependency>()
        for (dependency in this) {
            var current: Dependency? = dependency
            while (current != null && result.add(current)) current = current.parent
        }
        return result
    }

    private fun getTreePath(dependency: Dependency): List<Dependency> {
        val dependencyPath = ArrayList<Dependency>()
        var current: Dependency? = dependency
        while (current != null) {
            dependencyPath.add(current)
            current = current.parent
        }
        return dependencyPath
    }

    private fun Dependency.Data.getGroup(): String = when (this) {
        is Dependency.Data.Module -> name
        is Dependency.Data.Artifact -> "$groupId:$artifactId"
    }

    private fun Iterable<Dependency>.createDependencyGroups(groupId: Boolean = showDependencyGroupId): List<DependencyGroup> = sortedWith(
        Comparator.comparing(
            { it.data }, DependencyDataComparator(groupId)
        )
    ).groupBy { it.data.getGroup() }.map { DependencyGroup(it.value) }

    fun createComponent(): JComponent {
        val externalProjectSelector = ExternalProjectSelector(
            externalProjectProperty, externalProjects, iconsProvider
        ).bindEnabled(!dependencyLoadingProperty)
        val dataFilterField =
            SearchTextField(SEARCH_HISTORY_PROPERTY).apply { textEditor.bind(dependencyDataFilterProperty) }
                .bindEnabled(!dependencyLoadingProperty)
        val scopeFilterSelector =
            SearchScopeSelector(dependencyScopeFilterProperty).bindEnabled(!dependencyLoadingProperty)
        val dependencyInspectionFilterButton = toggleAction(showDependencyWarningsProperty).apply {
            templatePresentation.text =
                ExternalSystemBundle.message("external.system.dependency.analyzer.conflicts.show")
        }.apply { templatePresentation.icon = AllIcons.General.ShowWarning }.asActionButton(ACTION_PLACE)
            .bindEnabled(!dependencyLoadingProperty)
        val showDependencyGroupIdAction = toggleAction(showDependencyGroupIdProperty).apply {
            templatePresentation.text = ExternalSystemBundle.message("external.system.dependency.analyzer.groupId.show")
        }
        val showDependencySizeAction = toggleAction(showDependencySizeProperty).apply {
            templatePresentation.text = SbtDependencyExternalBundle.message("analyzer.external.showSize.name")
        }
        val viewOptionsButton = popupActionGroup(showDependencyGroupIdAction, showDependencySizeAction).apply {
            templatePresentation.icon = AllIcons.Actions.Show
        }.asActionButton(ACTION_PLACE).bindEnabled(!dependencyLoadingProperty)
        val notificationAware = ExternalSystemProjectNotificationAware.getInstance(project)
        val reloadNotificationProperty = AtomicProperty(notificationAware.isNotificationVisible(systemId))
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(ExternalSystemProjectNotificationAware.TOPIC, object : ExternalSystemProjectNotificationAware.Listener {
                override fun onNotificationChanged(project: Project) {
                    if (project == this@DependencyAnalyzerViewImpl.project) runInEdt {
                        if (!disposed) reloadNotificationProperty.set(notificationAware.isNotificationVisible(systemId))
                    }
                }
            })
        val projectReloadSeparator = separator().bindVisible(reloadNotificationProperty)
        val projectReloadAction = action { ProjectUtil.refreshProject(project) }.apply {
            templatePresentation.icon = AllIcons.Actions.BuildLoadChanges
        }.asActionButton(ACTION_PLACE).bindVisible(reloadNotificationProperty)

        val dependencyTitle = label(ExternalSystemBundle.message("external.system.dependency.analyzer.resolved.title"))
        val dependencyList =
            object : DependencyList(dependencyListModel, showDependencyGroupIdProperty, showDependencySizeProperty) {
                override fun uiDataSnapshot(sink: DataSink) {
                    super.uiDataSnapshot(sink)
                    DataSink.uiDataSnapshot(sink, this@DependencyAnalyzerViewImpl)
                }
            }.bindEmptyText(dependencyEmptyTextProperty).bindDependency(dependencyProperty)
                .bindEnabled(!dependencyLoadingProperty)
        val dependencyTree =
            object : DependencyTree(dependencyTreeModel, showDependencyGroupIdProperty, showDependencySizeProperty) {
                override fun uiDataSnapshot(sink: DataSink) {
                    super.uiDataSnapshot(sink)
                    DataSink.uiDataSnapshot(sink, this@DependencyAnalyzerViewImpl)
                }
            }.bindEmptyText(dependencyEmptyTextProperty).bindDependency(dependencyProperty)
                .bindEnabled(!dependencyLoadingProperty)
        val dependencyPanel = cardPanel<Boolean> {
            ScrollPaneFactory.createScrollPane(if (it) dependencyTree else dependencyList, true)
        }.bindSelected(showDependencyTreeProperty)
        val dependencyLoadingPanel =
            JBLoadingPanel(BorderLayout(), parentDisposable).apply { add(dependencyPanel, BorderLayout.CENTER) }
                .apply { setLoadingText(ExternalSystemBundle.message("external.system.dependency.analyzer.dependency.loading")) }
                .bindLoading(dependencyLoadingProperty)
        val showDependencyTreeButton = toggleAction(showDependencyTreeProperty).apply {
            templatePresentation.text =
                ExternalSystemBundle.message("external.system.dependency.analyzer.resolved.tree.show")
        }.apply { templatePresentation.icon = AllIcons.Actions.ShowAsTree }.asActionButton(ACTION_PLACE)
            .bindEnabled(!dependencyLoadingProperty)
        val expandDependencyTreeButton = expandTreeAction(dependencyTree).asActionButton(ACTION_PLACE)
            .bindEnabled(showDependencyTreeProperty and !dependencyLoadingProperty)
        val collapseDependencyTreeButton = collapseTreeAction(dependencyTree).asActionButton(ACTION_PLACE)
            .bindEnabled(showDependencyTreeProperty and !dependencyLoadingProperty)

        val usagesTitle = label(usagesTitleProperty)
        val usagesTree =
            object : UsagesTree(usagesTreeModel, showDependencyGroupIdProperty, showDependencySizeProperty) {
                override fun uiDataSnapshot(sink: DataSink) {
                    super.uiDataSnapshot(sink)
                    DataSink.uiDataSnapshot(sink, this@DependencyAnalyzerViewImpl)
                }
            }.apply { emptyText.text = "" }.bindEnabled(!dependencyLoadingProperty)
        val expandUsagesTreeButton =
            expandTreeAction(usagesTree).asActionButton(ACTION_PLACE).bindEnabled(!dependencyLoadingProperty)
        val collapseUsagesTreeButton =
            collapseTreeAction(usagesTree).asActionButton(ACTION_PLACE).bindEnabled(!dependencyLoadingProperty)

        return toolWindowPanel {
            toolbar = toolbarPanel {
                addToLeft(
                    horizontalPanel(
                        externalProjectSelector,
                        dataFilterField,
                        scopeFilterSelector,
                        separator(),
                        dependencyInspectionFilterButton,
                        viewOptionsButton,
                        projectReloadSeparator,
                        projectReloadAction
                    )
                )
            }
            setContent(horizontalSplitPanel(SPLIT_VIEW_PROPORTION_PROPERTY, 0.5f) {
                firstComponent = toolWindowPanel {
                    toolbar = toolbarPanel {
                        addToLeft(dependencyTitle)
                        addToRight(
                            horizontalPanel(
                                showDependencyTreeButton,
                                separator(),
                                expandDependencyTreeButton,
                                collapseDependencyTreeButton
                            )
                        )
                    }
                    setContent(dependencyLoadingPanel)
                }
                secondComponent = toolWindowPanel {
                    toolbar = toolbarPanel {
                        addToLeft(usagesTitle)
                        addToRight(horizontalPanel(expandUsagesTreeButton, collapseUsagesTreeButton))
                    }
                    setContent(ScrollPaneFactory.createScrollPane(usagesTree, true))
                }
            })
        }.also {
            viewComponent = it
            it.addPreferredFocusedComponent(dataFilterField)
        }
    }

    init {
        com.intellij.openapi.util.Disposer.register(parentDisposable, Disposable {
            disposed = true
            filterTimer.stop()
            backgroundExecutor.shutdownNow()
        })
        externalProjectProperty.afterChange { modelRevision.incrementAndGet(); updateScopesModel() }
        externalProjectProperty.afterChange { updateDependencyModel() }
        dependencyModelProperty.afterChange { updateFilteredDependencyModel() }
        dependencyDataFilterProperty.afterChange { updateFilteredDependencyModel() }
        dependencyScopeFilterProperty.afterChange { updateFilteredDependencyModel() }
        showDependencyWarningsProperty.afterChange { updateFilteredDependencyModel() }
        showDependencyGroupIdProperty.afterChange { updateFilteredDependencyModel() }
        showDependencySizeProperty.afterChange { viewComponent?.repaint() }
        dependencyProperty.afterChange { updateUsagesTitle() }
        dependencyProperty.afterChange { updateUsagesModel() }
        showDependencyGroupIdProperty.afterChange { updateUsagesTitle() }
        showDependencySizeProperty.afterChange { updateUsagesTitle() }
        dependencyLoadingProperty.afterChange { updateDependencyEmptyState() }
        contributor.whenDataChanged(::updateViewModel, parentDisposable)
        updateViewModel()
    }

    private class DependencyDataComparator(private val showDependencyGroupId: Boolean) : Comparator<Dependency.Data> {
        override fun compare(o1: Dependency.Data, o2: Dependency.Data): Int {
            val text1 = o1.getDisplayText(showDependencyGroupId)
            val text2 = o2.getDisplayText(showDependencyGroupId)
            return when (o1) {
                is Dependency.Data.Module -> when (o2) {
                    is Dependency.Data.Module -> NaturalComparator.INSTANCE.compare(text1, text2)
                    is Dependency.Data.Artifact -> -1
                }

                is Dependency.Data.Artifact -> when (o2) {
                    is Dependency.Data.Module -> 1
                    is Dependency.Data.Artifact -> NaturalComparator.INSTANCE.compare(text1, text2)
                }
            }
        }
    }

    companion object {
        private const val SEARCH_HISTORY_PROPERTY = "ExternalSystem.DependencyAnalyzerView.search"
        private const val SHOW_GROUP_ID_PROPERTY = "ExternalSystem.DependencyAnalyzerView.showGroupId"
        private const val SHOW_AS_TREE_PROPERTY = "ExternalSystem.DependencyAnalyzerView.showAsTree"
        private const val SPLIT_VIEW_PROPORTION_PROPERTY = "ExternalSystem.DependencyAnalyzerView.splitProportion"
        private const val SHOW_ARTIFACT_SIZE_PROPERTY = "ExternalSystem.DependencyAnalyzerView.showSize"
    }
}
