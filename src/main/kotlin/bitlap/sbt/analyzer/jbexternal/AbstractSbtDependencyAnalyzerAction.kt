// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package bitlap.sbt.analyzer.jbexternal

import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerAction
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerDependency
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerView


import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.module.Module

/** Opens the project analyzer editor and delegates transfer of the current action selection. */
abstract class BaseDependencyAnalyzerAction : DependencyAnalyzerAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val systemId = getSystemId(e) ?: return
        val dependencyAnalyzerManager = DependencyAnalyzerManager.getInstance(project)
        val dependencyAnalyzerView = dependencyAnalyzerManager.getOrCreate(systemId)
        setSelectedState(dependencyAnalyzerView, e)
    }
}

/** Maps an external selection to an IDE module and optional dependency/scope before selecting it in the analyzer. */
abstract class AbstractSbtDependencyAnalyzerAction<Data> : BaseDependencyAnalyzerAction() {

    abstract fun getSelectedData(e: AnActionEvent): Data?

    abstract fun getModule(e: AnActionEvent, selectedData: Data): Module?

    abstract fun getDependencyData(e: AnActionEvent, selectedData: Data): DependencyAnalyzerDependency.Data?

    abstract fun getDependencyScope(e: AnActionEvent, selectedData: Data): String?

    override fun isEnabledAndVisible(e: AnActionEvent): Boolean {
        val selectedData = getSelectedData(e) ?: return false
        return getModule(e, selectedData) != null
    }

    override fun setSelectedState(view: DependencyAnalyzerView, e: AnActionEvent) {
        val selectedData = getSelectedData(e) ?: return
        val module = getModule(e, selectedData) ?: return
        val dependencyData = getDependencyData(e, selectedData)
        val dependencyScope = getDependencyScope(e, selectedData)
        if (dependencyData != null && dependencyScope != null) {
            view.setSelectedDependency(module, dependencyData, dependencyScope)
        } else if (dependencyData != null) {
            view.setSelectedDependency(module, dependencyData)
        } else {
            view.setSelectedExternalProject(module)
        }
    }
}