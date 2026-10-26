package bitlap.sbt.analyzer.activity

import scala.util.control.NonFatal

import bitlap.sbt.analyzer.{ Constants, SbtDependencyAnalyzerBundle, SbtDependencyAnalyzerPlugin }

import org.jetbrains.plugins.scala.project.Version

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Conditions

/** Builds bundled release-note HTML and opens it in an IDE editor, with a browser fallback for manual requests. */
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

  /** Schedules the bundled editor for a project and acknowledges successful opening. With no project, or after an
   *  editor failure, opens the release URL in the browser.
   */
  def browse(version: Version, project: Project): Unit = {
    if (project == null) BrowserUtil.browse(getReleaseNotes(version))
    else
      ApplicationManager.getApplication.invokeLater(
        () => {
          if (openPage(version, project))
            com.intellij.ide.util.PropertiesComponent
              .getInstance()
              .setValue(PluginUpdateActivity.VersionProperty, version.presentation)
          else BrowserUtil.browse(getReleaseNotes(version))
        },
        ModalityState.nonModal(),
        Conditions.is(project.getDisposed)
      )
  }
}
