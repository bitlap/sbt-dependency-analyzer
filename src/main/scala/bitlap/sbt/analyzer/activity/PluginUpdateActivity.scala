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

/** Decides whether a version needs release notes: first install and upgrades qualify, repeated versions and downgrades
 *  do not. Unparseable version changes are treated as eligible.
 */
private[analyzer] object UpdatePagePolicy {

  def shouldShow(current: String, previous: String): Boolean =
    current != null && current.nonEmpty && current != previous &&
    (previous == null || previous.isEmpty || Try(Version(current) > Version(previous)).getOrElse(true))
}

/** Schedules release notes on the EDT and acknowledges a version only after a browser launch or bundled editor opens
 *  successfully. Application-level persistence prevents duplicate pages across projects; failed opens retain a
 *  notification fallback.
 */
object PluginUpdateActivity {
  // The legacy .version key only meant a notification was sent, not that the page opened.
  private[analyzer] val VersionProperty = s"${SbtDependencyAnalyzerPlugin.PLUGIN_ID}.whatsNewVersion"

  private val pending = scala.collection.mutable.Set.empty[String]

  private[analyzer] def schedule(project: Project): Unit = {
    val app = ApplicationManager.getApplication
    if (app.isUnitTestMode || app.isHeadlessEnvironment || project.isDisposed) return
    app.invokeLater(
      () => checkUpdate(project),
      ModalityState.nonModal(),
      Conditions.is(project.getDisposed)
    )
  }

  private[analyzer] def checkUpdate(
    project: Project,
    open: (Version, Project, Boolean => Unit) => Unit = (version, project, completed) =>
      WhatsNew.openReleaseNotes(version, project, completed)
  ): Unit = {
    if (project.isDisposed) return
    // Reserve on the EDT before launching asynchronously to deduplicate simultaneous project openings.
    val plugin     = SbtDependencyAnalyzerPlugin.details
    val properties = PropertiesComponent.getInstance()
    val current    = plugin.getVersion
    if (UpdatePagePolicy.shouldShow(current, properties.getValue(VersionProperty)) && pending.add(current)) {
      val version                    = Version(current)
      val completed: Boolean => Unit = opened => {
        pending.remove(current)
        if (opened) properties.setValue(VersionProperty, current)
        else if (!project.isDisposed)
          Notifications.notifyUpdateActivity(
            project,
            version,
            plugin.getName,
            Option(plugin.getChangeNotes).getOrElse("")
          )
      }
      try open(version, project, completed)
      catch {
        case error: Throwable =>
          pending.remove(current)
          throw error
      }
    }
  }
}

/** Schedules the update-page check when a project opens. */
final class PluginUpdateActivity extends BaseProjectActivity {
  override def onRunActivity(project: Project): Unit = PluginUpdateActivity.schedule(project)
}

/** Schedules update-page checks in already-open projects after this plugin is installed or loaded dynamically. */
final class PluginInstallListener extends DynamicPluginListener {

  override def pluginLoaded(plugin: IdeaPluginDescriptor): Unit =
    if (plugin.getPluginId.getIdString == SbtDependencyAnalyzerPlugin.PLUGIN_ID)
      ProjectManager.getInstance().getOpenProjects.foreach(PluginUpdateActivity.schedule)
}
