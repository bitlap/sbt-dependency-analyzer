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

  private val LOG = Logger.getInstance(getClass)

  /** sbt: com.softwaremill.sttp.shared:zio_3:1.3.7:jar
   */
  def getLibrarySize(project: Project, artifact: String): Long = {
    val libraryTable = LibraryTablesRegistrar.getInstance.getLibraryTable(project)
    val library      = libraryTable.getLibraryByName(s"sbt: $artifact:jar")
    if (library == null) return 0
    val vf = library.getFiles(OrderRootType.CLASSES)
    if (vf != null) {
      vf.headOption.map(_.getLength).getOrElse(0)
    } else 0
  }

  def getLibraryTotalSize(project: Project, ds: List[DependencyNode]): Long = {
    if (ds.isEmpty) return 0L
    ds.map(d =>
      getLibrarySize(project, d.getDisplayName) + getLibraryTotalSize(project, d.getDependencies.asScala.toList)
    ).sum
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
    while (System.currentTimeMillis() < endTime && !SbtUtils.isProjectReady(project)) {
      waitInterval(interval)
    }
    true
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
