package bitlap.sbt.analyzer.task

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.*

import bitlap.sbt.analyzer.{ Constants, SbtDependencyAnalyzerBundle }

import com.intellij.notification.{ NotificationGroupManager, NotificationType }
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.{ ControlFlowException, Logger }
import com.intellij.openapi.progress.{ ProgressIndicator, ProgressManager, Task }
import com.intellij.openapi.project.Project

@Service(Array(Service.Level.PROJECT))
final class SbtTaskRunner(project: Project) {
  private val running    = new AtomicBoolean(false)
  def isRunning: Boolean = running.get()

  def run(title: String)(body: => Unit): Unit = {
    if (!running.compareAndSet(false, true)) return
    ProgressManager
      .getInstance()
      .run(new Task.Backgroundable(project, title, true) {
        override def run(indicator: ProgressIndicator): Unit = try {
          body
        } catch {
          case c: ControlFlowException => throw c
          case e: Exception            =>
            Logger.getInstance(classOf[SbtTaskRunner]).warn(title, e)
            NotificationGroupManager
              .getInstance()
              .getNotificationGroup("Sbt.DependencyAnalyzer.Notification")
              .createNotification(title, Option(e.getMessage).getOrElse(e.toString), NotificationType.ERROR)
              .notify(project)
        } finally running.set(false)
      })
  }
}

object SbtTaskRunner {
  def getInstance(project: Project): SbtTaskRunner = project.getService(classOf[SbtTaskRunner])

  def await[T](project: Project, future: Future[T]): T = {
    require(!ApplicationManager.getApplication.isDispatchThread, "sbt commands must run on a background thread")
    val deadline = Constants.TIMEOUT.fromNow
    while (!future.isCompleted && deadline.hasTimeLeft()) {
      ProgressManager.checkCanceled()
      if (project.isDisposed) throw new com.intellij.openapi.progress.ProcessCanceledException()
      try Await.ready(future, 100.millis)
      catch { case _: scala.concurrent.TimeoutException => () }
    }
    ProgressManager.checkCanceled()
    Await.result(future, Duration.Zero)
  }
}
