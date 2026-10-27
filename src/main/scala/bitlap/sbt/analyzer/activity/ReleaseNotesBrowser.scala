package bitlap.sbt.analyzer.activity

import java.awt.Desktop
import java.net.URI
import java.util.Collections

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.{ BrowserUtil, GeneralLocalSettings }
import com.intellij.ide.browsers.{ DefaultBrowserPolicy, WebBrowserManager }
import com.intellij.openapi.util.SystemInfo

/** Reports browser launch acceptance instead of relying on BrowserUtil.browse's asynchronous, void result. Uses the IDE
 *  browser preference and preserves configured browser arguments and environment. Call from a worker thread.
 */
private[analyzer] object ReleaseNotesBrowser {

  def open(url: String): Boolean = {
    val settings = GeneralLocalSettings.getInstance()
    val manager  = WebBrowserManager.getInstance()
    val browser  =
      if (manager.getDefaultBrowserPolicy == DefaultBrowserPolicy.FIRST) Option(manager.getFirstActiveBrowser)
      else None
    browser match {
      case Some(selected) =>
        Option(selected.getPath).filter(_.trim.nonEmpty).exists { path =>
          val specific = Option(selected.getSpecificSettings)
          val command  = new GeneralCommandLine(
            BrowserUtil.getOpenBrowserCommand(
              path,
              url,
              specific.map(_.getAdditionalParameters).getOrElse(Collections.emptyList[String]()),
              false
            )
          )
          specific.foreach(s => command.withEnvironment(s.getEnvironmentVariables))
          launch(command)
        }
      case None if !settings.getUseDefaultBrowser =>
        Option(settings.getBrowserPath)
          .filter(_.trim.nonEmpty)
          .exists(path =>
            launch(
              new GeneralCommandLine(
                BrowserUtil.getOpenBrowserCommand(path, url, Collections.emptyList[String](), false)
              )
            )
          )
      case None if Desktop.isDesktopSupported && Desktop.getDesktop.isSupported(Desktop.Action.BROWSE) =>
        Desktop.getDesktop.browse(URI.create(url))
        true
      case None =>
        val command =
          if (SystemInfo.isMac) new GeneralCommandLine("/usr/bin/open", url)
          else if (SystemInfo.isWindows) new GeneralCommandLine("cmd.exe", "/c", "start", "\"\"", url)
          else new GeneralCommandLine("xdg-open", url)
        launch(command)
    }
  }

  private def launch(command: GeneralCommandLine): Boolean = {
    // A configured browser may stay alive; never terminate it just because the launch wait timed out.
    val output = new CapturingProcessHandler.Silent(command).runProcess(10000, false)
    !output.isCancelled && (output.isTimeout || output.getExitCode == 0)
  }
}
