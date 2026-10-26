package bitlap.sbt.analyzer.parsing

import java.nio.file.{ Files, Path }
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.util.SbtUtils

import org.jetbrains.sbt.project.SbtProjectSystem

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.task.*
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.project.Project

/** Lives longer than analyzer editors, so reimports also invalidate files while the view is closed. */
@Service(Array(Service.Level.PROJECT))
final class AnalyzerCacheService(project: Project) extends Disposable {
  val librarySizes              = new ConcurrentHashMap[String, java.lang.Long]()
  val dotFiles                  = new DotFileCache()
  val declarations              = new DependencyDeclarationCache()
  private val reimportListeners = new java.util.concurrent.CopyOnWriteArrayList[Runnable]()

  def whenReimported(parent: Disposable, listener: Runnable): Unit = {
    reimportListeners.add(listener)
    com.intellij.openapi.util.Disposer.register(
      parent,
      new Disposable {
        override def dispose(): Unit = { reimportListeners.remove(listener); () }
      }
    )
  }
  private val analysisFiles                 = ConcurrentHashMap.newKeySet[String]()
  def trackAnalysisFile(path: String): Unit = { analysisFiles.add(path); () }
  def clear(): Unit = { librarySizes.clear(); dotFiles.clearAllCache(); declarations.clearAllCache() }

  def invalidateAnalysisFiles(): Unit = {
    clear()
    analysisFiles.asScala.foreach { file =>
      try Files.deleteIfExists(Path.of(file))
      catch {
        case error: java.io.IOException =>
          Logger.getInstance(getClass).warn(s"Cannot remove stale analysis file $file", error)
      }
    }
  }

  ExternalSystemProgressNotificationManager
    .getInstance()
    .addNotificationListener(
      new ExternalSystemTaskNotificationListener {
        override def onTaskOutput(id: ExternalSystemTaskId, text: String, outputType: ProcessOutputType): Unit = ()
        override def onEnd(path: String, id: ExternalSystemTaskId): Unit                                       = {
          if (
            !project.isDisposed && id.getType == ExternalSystemTaskType.RESOLVE_PROJECT &&
            id.getProjectSystemId == SbtProjectSystem.Id && SbtUtils.getExternalProjectPath(project).contains(path)
          ) {
            invalidateAnalysisFiles()
            reimportListeners.asScala.foreach(_.run())
          }
        }
      },
      this
    )
  override def dispose(): Unit = { clear(); analysisFiles.clear(); reimportListeners.clear() }
}

object AnalyzerCacheService {
  def getInstance(project: Project): AnalyzerCacheService = project.getService(classOf[AnalyzerCacheService])
}
