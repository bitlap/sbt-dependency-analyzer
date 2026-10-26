package bitlap
package sbt
package analyzer
package util

import java.io.*
import java.nio.file.{ Path, Paths }

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.jetbrains.sbt.{ SbtUtil as SSbtUtil, SbtVersion }
import org.jetbrains.sbt.project.*
import org.jetbrains.sbt.project.settings.*
import org.jetbrains.sbt.settings.SbtSettings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.autoimport.{
  ExternalSystemProjectNotificationAware,
  ExternalSystemProjectTracker
}
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.project.dependencies.DependencyNode
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.externalSystem.util.{ ExternalSystemApiUtil, ExternalSystemUtil }
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.*
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar

object SbtUtils {

  val least  = SbtVersion("1.4.0")
  val latest = SbtVersion.Latest.Sbt_LatestIncludingUnreleased

  private val LOG = Logger.getInstance(getClass)

  /** sbt: com.softwaremill.sttp.shared:zio_3:1.3.7:jar
   */
  def getLibrarySize(project: Project, artifact: String): Long = {
    if (project.isDisposed) return 0L
    bitlap.sbt.analyzer.parsing.AnalyzerCacheService
      .getInstance(project)
      .librarySizes
      .computeIfAbsent(
        artifact,
        _ => {
          val libraryTable = LibraryTablesRegistrar.getInstance.getLibraryTable(project)
          val library      = libraryTable.getLibraryByName(s"sbt: $artifact:jar")
          if (library == null) 0L
          else {
            val files = library.getFiles(OrderRootType.CLASSES)
            files.toList
              .map(f =>
                Option(com.intellij.openapi.vfs.JarFileSystem.getInstance().getVirtualFileForJar(f)).getOrElse(f)
              )
              .filterNot(_.isDirectory)
              .distinct
              .map(_.getLength)
              .sum
          }
        }
      )
      .longValue()
  }

  /** Unique resolved artifacts, including the supplied roots. O(V+E), with no recursive stack. */
  def getLibraryTotalSize(project: Project, ds: List[DependencyNode]): Long = {
    val seen    = scala.collection.mutable.HashSet.empty[String]
    val pending = scala.collection.mutable.ArrayDeque.from(ds)
    var total   = 0L
    while (pending.nonEmpty) {
      com.intellij.openapi.progress.ProgressManager.checkCanceled()
      val node = pending.removeLast()
      if (node.getSelectionReason != "Evicted By" && seen.add(node.getDisplayName)) {
        total += getLibrarySize(project, node.getDisplayName)
        pending.appendAll(node.getDependencies.asScala)
      }
    }
    total
  }

  def getSbtProject(project: Project): SbtSettings = SSbtUtil.sbtSettings(project)

  def forceRefreshProject(project: Project): Unit = {
    ExternalSystemUtil.refreshProjects(
      new ImportSpecBuilder(project, SbtProjectSystem.Id)
        .dontNavigateToError()
        .dontReportRefreshErrors()
        .build()
    )
  }

  def untilProjectReady(project: Project): Boolean = {
    val timeout   = 10.minutes
    val interval  = 100.millis
    val startTime = System.currentTimeMillis()
    val endTime   = startTime + timeout.toMillis
    while (!project.isDisposed && System.currentTimeMillis() < endTime && !SbtUtils.isProjectReady(project)) {
      waitInterval(interval)
    }
    !project.isDisposed && isProjectReady(project)
  }

  // TODO
  private def isProjectReady(project: Project): Boolean = {
    SbtUtils
      .getExternalProjectPath(project)
      .map { externalProjectPath =>
        // index is ready?
        val processingManager = ApplicationManager.getApplication.getService(classOf[ExternalSystemProcessingManager])
        if (
          processingManager
            .findTask(ExternalSystemTaskType.RESOLVE_PROJECT, SbtProjectSystem.Id, externalProjectPath) != null
          || processingManager
            .findTask(ExternalSystemTaskType.REFRESH_TASKS_LIST, SbtProjectSystem.Id, externalProjectPath) != null
        ) {
          false
        } else true
      }
      .forall(identity)
  }

  def getExternalProjectPath(project: Project): List[String] =
    getSbtProject(project).getLinkedProjectsSettings.asScala.map(_.getExternalProjectPath()).toList

  def getSbtExecutionSettings(dir: String, project: Project): SbtExecutionSettings =
    SbtExternalSystemManager.executionSettingsFor(project, dir)

  def launcherJar(sbtSettings: SbtExecutionSettings): Path =
    sbtSettings.customLauncher.map(_.toPath).getOrElse(SSbtUtil.defaultLauncherPath)

  def getSbtVersion(project: Project): SbtVersion = {
    val workingDirPath = getWorkingDirPath(project)
    val sbtSettings    = getSbtExecutionSettings(workingDirPath, project)
    lazy val launcher  = launcherJar(sbtSettings)
    SSbtUtil.detectSbtVersion(Paths.get(workingDirPath), launcher)
  }

  // see https://github.com/JetBrains/intellij-scala/blob/idea232.x/sbt/sbt-impl/src/org/jetbrains/sbt/shell/SbtProcessManager.scala
  def getWorkingDirPath(project: Project): String = {
    // Fist try to calculate root path based on `getExternalRootProjectPath`
    // When sbt project reference another sbt project via `RootProject` this will correctly find the root project path (see SCL-21143)
    // However, if user manually linked multiple SBT projects via external system tool window (sbt tool window)
    // using "Link sbt Project" button (the one with "plus" icon), it  will randomly choose one of the projects
    val externalRootProjectPath: Option[String] = {
      val modules = ModuleManager.getInstance(project).getModules.toSeq
      modules.iterator.map(ExternalSystemApiUtil.getExternalRootProjectPath).find(_ != null)
    }
    externalRootProjectPath.orElse {
      // Not sure when externalRootProjectPath can be empty in SBT projects
      // But just in case fallback to ProjectUtil.guessProjectDir, but notice that it's not reliable in some cases (see SCL-21143)
      val message =
        s"Can't calculate external root project path for project `${project.getName}`, fallback to `ProjectUtil.guessProjectDir`"
      if (ApplicationManager.getApplication.isInternal)
        LOG.error(message)
      else
        LOG.warn(message)
      Option(ProjectUtil.guessProjectDir(project)).map(_.getCanonicalPath)
    }
      .getOrElse(throw new IllegalStateException(s"no project directory found for project ${project.getName}"))
  }

}
