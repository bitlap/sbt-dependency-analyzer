package bitlap.sbt.analyzer.activity

import scala.util.Try

import bitlap.sbt.analyzer.SbtDependencyAnalyzerPlugin
import bitlap.sbt.analyzer.util.Notifications

import org.jetbrains.plugins.scala.project.Version

import com.intellij.ide.plugins.{ DynamicPluginListener, IdeaPluginDescriptor }
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.{ ApplicationManager, ModalityState }
import com.intellij.openapi.project.{ Project, ProjectManager }
import com.intellij.openapi.util.Conditions

private[analyzer] object UpdatePagePolicy {

  def shouldShow(current: String, previous: String): Boolean =
    current != null && current.nonEmpty && current != previous &&
    (previous == null || previous.isEmpty || Try(Version(current) > Version(previous)).getOrElse(true))
}

object PluginUpdateActivity {
  // The legacy .version key only meant a notification was sent, not that the page opened.
  private[analyzer] val VersionProperty = s"${SbtDependencyAnalyzerPlugin.PLUGIN_ID}.whatsNewVersion"

  private[analyzer] def schedule(project: Project): Unit = {
    val app = ApplicationManager.getApplication
    if (app.isUnitTestMode || project.isDisposed) return
    app.invokeLater(
      () => checkUpdate(project),
      ModalityState.nonModal(),
      Conditions.is(project.getDisposed)
    )
  }

  private[analyzer] def checkUpdate(
    project: Project,
    open: (Version, Project) => Boolean = WhatsNew.openPage
  ): Unit = {
    if (project.isDisposed) return
    // Check and acknowledge on the EDT, so simultaneous project openings cannot show duplicate pages.
    val plugin     = SbtDependencyAnalyzerPlugin.descriptor
    val properties = PropertiesComponent.getInstance()
    val current    = plugin.getVersion
    if (UpdatePagePolicy.shouldShow(current, properties.getValue(VersionProperty))) {
      val version = Version(current)
      if (open(version, project)) properties.setValue(VersionProperty, current)
      else {
        Notifications.notifyUpdateActivity(
          project,
          version,
          plugin.getName,
          Option(plugin.getChangeNotes).getOrElse("")
        )
      }
    }
  }
}

final class PluginUpdateActivity extends BaseProjectActivity {
  override def onRunActivity(project: Project): Unit = PluginUpdateActivity.schedule(project)
}

/** Dynamic installation can happen after all open projects have already run their startup activities. */
final class PluginInstallListener extends DynamicPluginListener {

  override def pluginLoaded(plugin: IdeaPluginDescriptor): Unit =
    if (plugin.getPluginId.getIdString == SbtDependencyAnalyzerPlugin.PLUGIN_ID)
      ProjectManager.getInstance().getOpenProjects.foreach(PluginUpdateActivity.schedule)
}
