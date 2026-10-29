package bitlap.sbt.analyzer

import bitlap.sbt.analyzer.activity.{ PluginUpdateActivity, WhatsNew }

import org.jetbrains.plugins.scala.project.Version

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/** Exercises production asynchronous opening with fake launchers, without opening the user's browser. */
object AnalyzerSmokeStarterWhatsNew {

  def run(project: Project, check: (String, () => Unit) => Unit): Unit = {
    for (mode <- Seq("browser", "fallback", "exception", "both-fail", "cancel")) {
      check(
        s"What's New browser-first order and completion: $mode",
        () => {
          val app                     = ApplicationManager.getApplication
          val calls                   = new java.util.concurrent.CopyOnWriteArrayList[String]()
          var result: Option[Boolean] = None
          WhatsNew.openReleaseNotes(
            Version("0.11.0"),
            project,
            opened => { assert(app.isDispatchThread); result = Some(opened) },
            _ => {
              assert(!app.isDispatchThread)
              calls.add("browser")
              if (mode == "exception") throw new java.io.IOException("Simulated browser launch failure")
              if (mode == "cancel") throw new com.intellij.openapi.progress.ProcessCanceledException()
              mode == "browser"
            },
            (_, _) => {
              assert(app.isDispatchThread)
              calls.add("bundled")
              mode != "both-fail"
            }
          )
          AnalyzerSmokeStarterCore.eventually(mode)(result.nonEmpty)
          val fallback = mode != "browser" && mode != "cancel"
          assert(calls.toArray.toList == (if (fallback) List("browser", "bundled") else List("browser")))
          assert(result.contains(mode != "both-fail" && mode != "cancel"))
        }
      )
    }
    check(
      "What's New production browser launcher falls back for a missing configured executable",
      () => {
        val settings                = com.intellij.ide.GeneralLocalSettings.getInstance()
        val manager                 = com.intellij.ide.browsers.WebBrowserManager.getInstance()
        val previousPath            = settings.getBrowserPath
        val previousDefault         = settings.getUseDefaultBrowser
        val previousState           = manager.getState.clone()
        var result: Option[Boolean] = None
        var fallback                = false
        try {
          manager.loadState(previousState.clone().setAttribute("default", "alternative"))
          settings.setUseDefaultBrowser(false)
          settings.setBrowserPath(
            java.nio.file.Path.of(System.getProperty("idea.system.path"), "missing-browser").toString
          )
          WhatsNew.openReleaseNotes(
            Version("0.11.0"),
            project,
            opened => result = Some(opened),
            bundled = (_, _) => { fallback = true; true }
          )
          AnalyzerSmokeStarterCore.eventually("invalid browser path")(result.nonEmpty)
          assert(result.contains(true) && fallback)
        } finally {
          settings.setBrowserPath(previousPath)
          settings.setUseDefaultBrowser(previousDefault)
          manager.loadState(previousState)
        }
      }
    )
    check(
      "What's New deduplicates in-flight requests and retries after asynchronous failure",
      () => {
        val properties                                        = PropertiesComponent.getInstance()
        val key                                               = PluginUpdateActivity.VersionProperty
        val previous                                          = properties.getValue(key)
        var attempts                                          = 0
        var finish: Boolean => Unit                           = _ => ()
        val open: (Version, Project, Boolean => Unit) => Unit = (_, _, completed) => {
          attempts += 1
          finish = completed
        }
        try {
          properties.unsetValue(key)
          PluginUpdateActivity.checkUpdate(project, open)
          PluginUpdateActivity.checkUpdate(project, open)
          assert(attempts == 1 && properties.getValue(key) == null)
          finish(false)
          assert(properties.getValue(key) == null)
          PluginUpdateActivity.checkUpdate(project, open)
          assert(attempts == 2)
          finish(true)
          PluginUpdateActivity.checkUpdate(project, open)
          assert(attempts == 2)
          assert(properties.getValue(key) == SbtDependencyAnalyzerPlugin.details.getVersion)
        } finally {
          if (previous == null) properties.unsetValue(key) else properties.setValue(key, previous)
        }
      }
    )
  }
}
