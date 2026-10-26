package bitlap.sbt.analyzer.task

import bitlap.sbt.analyzer.util.SbtUtils

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project

/** Only removes settings inserted by this action; existing user session settings remain intact. */
class RefreshSnapshotsTask extends SbtShellOutputAnalysisTask[Unit] {
  protected def commandOutput(project: Project, command: String): String = getCommandOutput(project, command)

  protected def supportsTtl(project: Project): Boolean =
    SbtUtils.getSbtVersion(project).inRange(SbtUtils.least, SbtUtils.latest)

  override def executeCommand(project: Project): Unit = executeForModule(project, "")

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

  private[analyzer] def sessionSettings(output: String): Map[Int, String] = output.linesIterator
    .map(_.trim)
    .collect { case Entry(index, text) =>
      index.toInt -> text.trim
    }
    .toMap
}
