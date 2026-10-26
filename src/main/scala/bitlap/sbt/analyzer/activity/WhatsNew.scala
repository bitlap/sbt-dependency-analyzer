package bitlap.sbt.analyzer.activity

import scala.util.control.NonFatal

import bitlap.sbt.analyzer.{ Constants, SbtDependencyAnalyzerBundle, SbtDependencyAnalyzerPlugin }

import org.jetbrains.plugins.scala.project.Version

import com.intellij.openapi.application.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project

/** Opens release notes in the browser first, falling back to bundled HTML when browser launch fails. */
object WhatsNew {
  private lazy val Log     = Logger.getInstance(getClass)
  private val ReleaseNotes = "https://github.com/bitlap/sbt-dependency-analyzer/releases/tag/v"

  def getReleaseNotes(version: Version): String = ReleaseNotes + version.presentation

  /** Uses the latest bundled change-note section as HTML, escaping the title and release URL. The bundled note body is
   *  trusted plugin markup and is not HTML-escaped.
   */
  private[analyzer] def pageHtml(title: String, notes: String, url: String): String = {
    def escape(text: String): String = text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")
    val latest =
      Option(notes).getOrElse("").split(java.util.regex.Pattern.quote(Constants.CHANGE_NOTES_SEPARATOR), 2).head
    s"""<!doctype html><html><head><meta charset="UTF-8"></head>
       |<body style="font-family:sans-serif;margin:32px;line-height:1.6">
       |<h1>${escape(title)}</h1>$latest
       |<p><a href="${escape(url)}">${escape(url)}</a></p></body></html>""".stripMargin
  }

  /** Returns true only when FileEditorManager creates an editor for the bundled page. Call on the EDT. Disposed
   *  projects and recoverable editor failures return false; cancellation propagates.
   */
  private[analyzer] def openPage(version: Version, project: Project): Boolean = {
    if (project.isDisposed) return false
    val title = SbtDependencyAnalyzerBundle.message("analyzer.action.whatsNew.text", "Sbt Dependency Analyzer")
    val html  = pageHtml(title, SbtDependencyAnalyzerPlugin.descriptor.getChangeNotes, getReleaseNotes(version))
    try {
      FileEditorManager.getInstance(project).openFile(new WhatsNewFile(title, html), true).nonEmpty
    } catch {
      case e: ProcessCanceledException => throw e
      case NonFatal(e)                 =>
        Log.warn("Failed to open What's New page", e)
        false
    }
  }

  /** Launches the browser off the EDT, then reports acceptance or opens the bundled fallback on the EDT. Browser
   *  acceptance does not prove that the remote page loaded. Injectable launchers keep tests from opening real browsers.
   */
  private[analyzer] def openReleaseNotes(
    version: Version,
    project: Project,
    completed: Boolean => Unit,
    browser: String => Boolean = ReleaseNotesBrowser.open,
    bundled: (Version, Project) => Boolean = openPage
  ): Unit = {
    val app = ApplicationManager.getApplication
    app.executeOnPooledThread((() => {
      var cancelled = false
      val launched  = try {
        if (project != null && project.isDisposed) false else browser(getReleaseNotes(version))
      } catch {
        case _: ProcessCanceledException => cancelled = true; false
        case NonFatal(error)             =>
          Log.warn("Failed to launch release notes browser", error)
          false
      }
      // Always run completion, including after project disposal, so pending startup requests can be released.
      app.invokeLater(
        () => {
          var opened = launched
          try {
            if (!launched && !cancelled && project != null && !project.isDisposed)
              opened = bundled(version, project)
          } finally completed(opened)
        },
        ModalityState.nonModal()
      )
    }): Runnable)
  }

  /** Manual and notification requests follow the same browser-first path as installation and startup. */
  def browse(version: Version, project: Project): Unit =
    openReleaseNotes(
      version,
      project,
      opened => {
        if (opened)
          com.intellij.ide.util.PropertiesComponent
            .getInstance()
            .setValue(PluginUpdateActivity.VersionProperty, version.presentation)
      }
    )
}
