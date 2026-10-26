package bitlap.sbt.analyzer.jbexternal.util

import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectNotificationAware
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.project.Project

/** Requests external-system refreshes after checking project trust. */
@Suppress("DEPRECATION")
object ProjectUtil {
    fun refreshProject(project: Project) {
        val projectNotificationAware = ExternalSystemProjectNotificationAware.getInstance(project)
        val systemIds = projectNotificationAware.getSystemIds()
        if (ExternalSystemTrustedProjectDialog.confirmLoadingUntrustedProject(project, systemIds)) {
            val projectTracker = ExternalSystemProjectTracker.getInstance(project)
            projectTracker.scheduleProjectRefresh()
        }
    }
}
