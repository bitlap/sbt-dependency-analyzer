package bitlap.sbt.analyzer.task

import bitlap.sbt.analyzer.util.SbtUtils

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project

/** Only removes settings inserted by this action; existing user session settings remain intact. */
final class RefreshSnapshotsTask extends SbtShellOutputAnalysisTask[Unit] {
  override def executeCommand(project: Project): Unit = executeForModule(project, "")

  def executeForModule(project: Project, moduleId: String): Unit = {
    val prefix                      = if (moduleId.isEmpty) "" else s"$moduleId / "
    val escaped                     = moduleId.replace("\\", "\\\\").replace("\"", "\\\"")
    val settingScope                = if (moduleId.isEmpty) "" else s"LocalProject(\"$escaped\") / "
    def session(): Map[Int, String] = RefreshSnapshotsTask.sessionSettings(getCommandOutput(project, "session list"))
    val before                      = session()
    val settings                    = List(s"${settingScope}update / skip := false") ++
      (if (SbtUtils.getSbtVersion(project).inRange(SbtUtils.least, SbtUtils.latest))
         List(
           s"${settingScope}csrConfiguration := (${settingScope}csrConfiguration).value.withTtl(Some(scala.concurrent.duration.Duration.Zero))"
         )
       else Nil)
    try {
      settings.foreach(setting => getCommandOutput(project, s"set $setting"))
      getCommandOutput(project, s"${prefix}update")
    } finally {
      ProgressManager
        .getInstance()
        .executeNonCancelableSection(new Runnable {
          override def run(): Unit = {
            val added = session().filter { (index, text) => !before.contains(index) && settings.contains(text.trim) }
            added.keys.toList.sorted.reverse.foreach(index => getCommandOutput(project, s"session remove $index"))
          }
        })
    }
  }
}

object RefreshSnapshotsTask {
  private val Entry = """(?:\[info\]\s*)?(\d+)\.\s*(.*)""".r

  private[analyzer] def sessionSettings(output: String): Map[Int, String] = output.linesIterator
    .map(_.trim)
    .collect { case Entry(index, text) =>
      index.toInt -> text.trim
    }
    .toMap
}
