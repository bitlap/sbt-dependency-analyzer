package bitlap.sbt.analyzer

import com.intellij.ide.plugins.PluginDetailsService
import com.intellij.ide.plugins.PluginDetailsService.PluginDetails
import com.intellij.openapi.extensions.PluginId

/** Provides installed plugin metadata and the stable ID used by services and update tracking. */
object SbtDependencyAnalyzerPlugin {

  val PLUGIN_ID = "org.bitlap.sbtDependencyAnalyzer"

  /** Queries metadata on demand; object initialization must never request an application service. */
  def details: PluginDetails = Option(PluginDetailsService.getInstance().findDetails(PluginId.getId(PLUGIN_ID)))
    .getOrElse(throw new IllegalStateException(s"Plugin $PLUGIN_ID not found"))

}
