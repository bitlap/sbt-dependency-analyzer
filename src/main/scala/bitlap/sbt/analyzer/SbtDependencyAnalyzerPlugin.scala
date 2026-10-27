package bitlap.sbt.analyzer

import com.intellij.ide.plugins.{ IdeaPluginDescriptor, PluginManager }

/** Provides the installed plugin descriptor and stable plugin ID used by services and update tracking. */
object SbtDependencyAnalyzerPlugin {

  val PLUGIN_ID = "org.bitlap.sbtDependencyAnalyzer"

  val descriptor: IdeaPluginDescriptor = PluginManager.getPlugins
    .find(_.getPluginId.getIdString == PLUGIN_ID)
    .getOrElse(throw new IllegalStateException(s"Plugin $PLUGIN_ID not found"))

}
