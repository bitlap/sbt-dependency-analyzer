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

  /** Renders only the newest release, including bundles where older release headings have no separator comment. Styling
   *  contains layout only; the IDE HTML component supplies theme colors and fonts.
   */
  private[analyzer] def pageHtml(title: String, notes: String, url: String, version: String = ""): String = {
    def escape(text: String): String = text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")
    val section = Option(notes)
      .getOrElse("")
      .split(java.util.regex.Pattern.quote(Constants.CHANGE_NOTES_SEPARATOR), 2)
      .head
    val releaseHeading = """(?is)<h1\b[^>]*>(.*?)</h1\s*>""".r
    val first          = releaseHeading.findFirstMatchIn(section)
    val body           = first.fold(section)(m => section.substring(m.end))
    val latest         = releaseHeading.findFirstMatchIn(body).fold(body)(m => body.substring(0, m.start)).trim
    val releaseVersion = Option(version)
      .filter(_.nonEmpty)
      .orElse(first.map(_.group(1).replaceAll("<[^>]*>", "").trim))
      .filter(_.nonEmpty)
    val versionHtml = releaseVersion.fold("")(v =>
      s"<p class=\"version\">${escape(SbtDependencyAnalyzerBundle.message("analyzer.whatsNew.version", v))}</p>"
    )
    val content =
      if (latest.nonEmpty) latest
      else s"<p>${escape(SbtDependencyAnalyzerBundle.message("analyzer.whatsNew.empty"))}</p>"
    s"""<!doctype html><html><head><meta charset="UTF-8">
       |<style>
       |body { margin: 0; font-weight: normal; line-height: 1.4; }
       |h1 { font-size: 26px; margin: 0 0 8px 0; }
       |h2 { font-size: 17px; margin: 18px 0 6px 0; }
       |p.version { margin: 0 0 12px 0; }
       |li { margin-bottom: 4px; }
       |ul { margin: 4px 0 12px 0; padding-left: 22px; }
       |p.release-link { margin: 28px 0 0 0; }
       |</style></head><body>
       |<h1>${escape(title)}</h1>$versionHtml
       |$content
       |<p class="release-link"><a href="${escape(url)}">${escape(
        SbtDependencyAnalyzerBundle.message("analyzer.whatsNew.releaseLink")
      )}</a></p>
       |</body></html>""".stripMargin
  }

  /** Returns true only when FileEditorManager creates an editor for the bundled page. Call on the EDT. Disposed
   *  projects and recoverable editor failures return false; cancellation propagates.
   */
  private[analyzer] def openPage(version: Version, project: Project): Boolean = {
    if (project.isDisposed) return false
    val title = SbtDependencyAnalyzerBundle.message("analyzer.action.whatsNew.text", "Sbt Dependency Analyzer")
    val html  = pageHtml(
      title,
      SbtDependencyAnalyzerPlugin.descriptor.getChangeNotes,
      getReleaseNotes(version),
      version.presentation
    )
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
