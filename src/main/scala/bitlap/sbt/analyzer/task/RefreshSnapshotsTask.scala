package bitlap.sbt.analyzer.task

import bitlap.sbt.analyzer.util.SbtUtils

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project

/** Runs update with skip disabled and, where supported, a zero Coursier TTL. Snapshots existing session entries first
 *  and removes only matching entries added by this invocation, in reverse index order. Cleanup runs in a non-cancelable
 *  section and leaves pre-existing user settings intact.
 */
class RefreshSnapshotsTask extends SbtShellOutputAnalysisTask[Unit] {
  protected def commandOutput(project: Project, command: String): String = getCommandOutput(project, command)

  protected def supportsTtl(project: Project): Boolean =
    SbtUtils.getSbtVersion(project).inRange(SbtUtils.least, SbtUtils.latest)

  override def executeCommand(project: Project): Unit = executeForModule(project, "")

  /** Refreshes a local or build-qualified project; an empty selector uses the shell current project. Call through the
   *  project task gate so another analyzer refresh cannot interleave temporary session edits.
   */
  def executeForModule(project: Project, moduleId: String): Unit = {
    val prefix                      = if (moduleId.isEmpty) "" else s"$moduleId / "
    val settingScope                = bitlap.sbt.analyzer.util.SbtProjectReference.settingScope(moduleId)
    def session(): Map[Int, String] = RefreshSnapshotsTask.sessionSettings(commandOutput(project, "session list"))
    val before                      = session()
    val settings                    = List(s"${settingScope}update / skip := false") ++
      (if (supportsTtl(project))
         List(
           s"${settingScope}csrConfiguration := (${settingScope}csrConfiguration).value.withTtl(Some(scala.concurrent.duration.Duration.Zero))"
         )
       else Nil)
    try {
      settings.foreach(setting => commandOutput(project, s"set $setting"))
      commandOutput(project, s"${prefix}update")
    } finally {
      ProgressManager
        .getInstance()
        .executeNonCancelableSection(new Runnable {
          override def run(): Unit = {
            val added = session().filter { (index, text) => !before.contains(index) && settings.contains(text.trim) }
            added.keys.toList.sorted.reverse.foreach(index => commandOutput(project, s"session remove $index"))
          }
        })
    }
  }
}

object RefreshSnapshotsTask {
  private val Entry = """(?:\[info\]\s*)?(\d+)\.\s*(.*)""".r

  /** Extracts numbered session entries, tolerating the optional sbt info prefix. */
  private[analyzer] def sessionSettings(output: String): Map[Int, String] = output.linesIterator
    .map(_.trim)
    .collect { case Entry(index, text) =>
      index.toInt -> text.trim
    }
    .toMap
}
