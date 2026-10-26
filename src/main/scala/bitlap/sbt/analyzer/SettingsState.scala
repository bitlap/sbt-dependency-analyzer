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

@State(name = "SbtDependencyAnalyzer.Settings", storages = Array(new Storage("bitlap.sbt.dependency.analyzer.xml")))
final class SettingsState extends PersistentStateComponent[SettingsState] {

  @BeanProperty
  var disableAnalyzeCompile: Boolean = false

  @BeanProperty
  var disableAnalyzeProvided: Boolean = false

  @BeanProperty
  var disableAnalyzeTest: Boolean = false

  @BeanProperty
  var organization: String = ""

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

  /** *
   *  {{{
   *     ApplicationManager
   *    .getApplication()
   *    .messageBus
   *    .connect(this)
   *    .subscribe(SettingsChangeListener.TOPIC, this)
   *  }}}
   */
  val SettingsChangePublisher: SettingsChangeListener =
    ApplicationManager.getApplication.getMessageBus.syncPublisher(_Topic)

}
