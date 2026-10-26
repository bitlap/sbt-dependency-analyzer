package bitlap
package sbt
package analyzer

import java.util.{ Collections, Map as JMap }

import scala.beans.BeanProperty

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

/** Persistent project settings for enabled scopes, the optional organization override and cache lifetime. Cache
 *  lifetime is stored in seconds; zero disables reuse. Negative persisted values are clamped on load.
 */
@State(name = "SbtDependencyAnalyzer.Settings", storages = Array(new Storage("bitlap.sbt.dependency.analyzer.xml")))
final class SettingsState extends PersistentStateComponent[SettingsState] {

  @BeanProperty
  var disableAnalyzeCompile: Boolean = false

  @BeanProperty
  var disableAnalyzeProvided: Boolean = false

  @BeanProperty
  var disableAnalyzeTest: Boolean = false

  /** Optional manual organization override; an empty value queries the selected sbt project. */
  @BeanProperty
  var organization: String = ""

  /** Cache lifetime in seconds; zero disables parsed-file and scope-model reuse. */
  @BeanProperty
  var fileCacheTimeout: Int = 3600

  override def getState: SettingsState = this

  override def loadState(state: SettingsState): Unit = {
    XmlSerializerUtil.copyBean(state, this)
    fileCacheTimeout = math.max(0, fileCacheTimeout)
  }

}

object SettingsState {

  def getSettings(project: Project): SettingsState = project.getService(classOf[SettingsState])

  val _Topic: Topic[SettingsChangeListener] =
    Topic.create("SbtDependencyAnalyzerSettingsChanged", classOf[SettingsChangeListener])

  trait SettingsChangeListener:

    def onConfigurationChanged(project: Project, settingsState: SettingsState): Unit

  end SettingsChangeListener

  /** Publishes settings changes synchronously; listeners filter events by project. */
  val SettingsChangePublisher: SettingsChangeListener =
    ApplicationManager.getApplication.getMessageBus.syncPublisher(_Topic)

}
