package bitlap
package sbt
package analyzer
package task

import org.jetbrains.plugins.scala.project.Version

import com.intellij.openapi.project.Project

import util.SbtUtils

/** Handles the `set csrConfiguration;update` command to load fresh snapshot dependencies for the sbt shell environment.
 */
final class RefreshSnapshotsTask extends SbtShellOutputAnalysisTask[Unit]:

  override def executeCommand(project: Project): Unit =
    val sbtVersion = SbtUtils.getSbtVersion(project)
    // see https://www.scala-sbt.org/1.x/docs/Dependency-Management-Flow.html#Notes+on+SNAPSHOTs
    if (sbtVersion.inRange(SbtUtils.least, SbtUtils.latest)) {
      getCommandOutputLines(
        project,
        """
          |set update / skip := false;
          |set csrConfiguration := csrConfiguration.value.withTtl(Option(scala.concurrent.duration.DurationInt(0).seconds));
          |update;
          |""".stripMargin
      )
    } else {
      getCommandOutputLines(
        project,
        """
          |set update / skip := false;
          |update;
          |""".stripMargin
      )
    }

end RefreshSnapshotsTask
