package bitlap.sbt.analyzer

import java.nio.file.{ Files, Path }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }

import scala.concurrent.{ Future, Promise }
import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.jbexternal.{ DependencyAnalyzerViewImpl, SbtDAArtifact }
import bitlap.sbt.analyzer.parsing.DependencyDeclaration
import bitlap.sbt.analyzer.task.{ RefreshSnapshotsTask, SbtTaskRunner }
import bitlap.sbt.analyzer.util.packagesearch.DependencyEditPlan

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.dependency.analyzer.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

/** IDE-runtime checks for structural declarations, project matching, reviewed edits and shell refresh cleanup. */
object AnalyzerSmokeStarterCore {

  private[analyzer] def eventually(label: String)(ready: => Boolean): Unit = {
    def waitForResult(): Unit = {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
      while (!ready && System.nanoTime() < deadline) {
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2))
      }
      assert(ready, s"Timed out: $label")
    }
    // ApplicationStarter holds the UI intent lock for main(); background read actions must get a chance to run.
    val app = ApplicationManager.getApplication
    if (app.isWriteIntentLockAcquired && !app.isWriteAccessAllowed)
      app
        .asInstanceOf[com.intellij.openapi.application.impl.ApplicationImpl]
        .getThreadingSupport
        .releaseTheAcquiredWriteIntentLockThenExecuteActionAndTakeWriteIntentLockBack(() => {
          waitForResult(); kotlin.Unit.INSTANCE
        })
    else waitForResult()
  }

  private def background[A](body: => A): java.util.concurrent.Future[A] =
    com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService.submit(new java.util.concurrent.Callable[A] {
      override def call(): A = body
    })

  private[analyzer] def field[A](target: AnyRef, name: String): A = {
    val f = target.getClass.getDeclaredField(name)
    f.setAccessible(true)
    f.get(target).asInstanceOf[A]
  }

  private[analyzer] def property[A](view: DependencyAnalyzerViewImpl, name: String) =
    field[com.intellij.openapi.observable.properties.AtomicProperty[A]](view, name)

  private[analyzer] def loading(view: DependencyAnalyzerViewImpl): Boolean =
    field[com.intellij.openapi.observable.properties.ObservableProperty[java.lang.Boolean]](
      view,
      "dependencyLoadingProperty"
    ).get()

  private def load(view: DependencyAnalyzerViewImpl)(body: => String)(apply: String => Unit): Unit = {
    val method = view.getClass.getDeclaredMethods.find(_.getName == "executeLoadingTask").get
    method.setAccessible(true)
    method.invoke(
      view,
      (() => body): kotlin.jvm.functions.Function0[String],
      ((value: String) => { apply(value); kotlin.Unit.INSTANCE }): kotlin.jvm.functions.Function1[String, kotlin.Unit]
    )
  }

  def run(
    project: Project,
    check: (String, () => Unit) => Unit,
    declaration: (String, String) => DependencyDeclaration
  ): Unit = {
    check(
      "all source actions are registered in list tree and usages popup groups",
      () => {
        val manager  = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val expected = Set(
          "Sbt.DependencyAnalyzer.GoTo",
          "Sbt.DependencyAnalyzer.ExcludeDependency",
          "Sbt.DependencyAnalyzer.OpenConfig"
        )
        for (name <- Seq("DependencyListGroup", "DependencyTreeGroup", "UsagesTreeGroup")) {
          val group = manager
            .getAction(s"ExternalSystem.DependencyAnalyzer.$name")
            .asInstanceOf[com.intellij.openapi.actionSystem.DefaultActionGroup]
          val registered = group.getChildActionsOrStubs.map {
            case stub: com.intellij.openapi.actionSystem.ActionStubBase => stub.getId
            case action                                                 => manager.getId(action)
          }.toSet
          assert(expected.subsetOf(registered), s"$name: $registered")
        }
      }
    )

    check(
      "settings apply reset validation and change events are atomic",
      () => {
        val settings = SettingsState.getSettings(project)
        val saved    = new SettingsState()
        saved.loadState(settings)
        val owner   = Disposer.newDisposable()
        val changed = new AtomicInteger()
        ApplicationManager.getApplication.getMessageBus
          .connect(owner)
          .subscribe(
            SettingsState._Topic,
            new SettingsState.SettingsChangeListener {
              override def onConfigurationChanged(p: Project, state: SettingsState): Unit =
                if (p == project) changed.incrementAndGet()
            }
          )
        try {
          val panel = new SbtDependencyAnalyzerPanel(project)
          panel.organization.setText("  org.example  ")
          panel.fileCache.setText("0")
          panel.compileCheckBox.setSelected(true)
          panel.testCheckBox.setSelected(true)
          panel.providedCheckBox.setSelected(true)
          assert(panel.isModified)
          panel.apply()
          assert(settings.organization == "org.example" && settings.fileCacheTimeout == 0)
          assert(settings.disableAnalyzeCompile && settings.disableAnalyzeTest && settings.disableAnalyzeProvided)
          assert(changed.get() == 1 && !panel.isModified)
          panel.apply()
          assert(changed.get() == 1)
          panel.organization.setText("not-applied")
          panel.fileCache.setText("invalid")
          try { panel.apply(); throw new AssertionError("Invalid settings accepted") }
          catch { case _: com.intellij.openapi.options.ConfigurationException => () }
          assert(settings.organization == "org.example" && settings.fileCacheTimeout == 0 && changed.get() == 1)
          panel.reset()
          assert(!panel.isModified && panel.organization.getText == "org.example")
        } finally { Disposer.dispose(owner); settings.loadState(saved) }
      }
    )

    check(
      "SNAPSHOT refresh preserves user settings on success partial set failure update failure and cancellation",
      () => {
        for (
          moduleId <- Seq("", "core-js"); mode <- Seq("success", "set-failure", "failure", "cancel");
          ttl      <- Seq(false, true)
        ) {
          val skip =
            if (moduleId.isEmpty) "update / skip := false" else "LocalProject(\"core-js\") / update / skip := false"
          // An existing setting with exactly the same text must survive cleanup too.
          val entries  = scala.collection.mutable.Map(1 -> "update / skip := true", 2 -> "userSetting := 7", 3 -> skip)
          val original = entries.toMap
          val commands = scala.collection.mutable.ListBuffer.empty[String]
          val failure  = new IllegalStateException("Expected shell failure")
          val task     = new RefreshSnapshotsTask {
            override protected def supportsTtl(p: Project): Boolean                   = ttl
            override protected def commandOutput(p: Project, command: String): String = {
              commands += command
              if (command == "session list")
                entries.toSeq.sortBy(_._1).map((i, text) => s"[info] $i. $text").mkString("\n")
              else if (command.startsWith("set ")) {
                entries(entries.keys.max + 1) = command.stripPrefix("set ")
                if (mode == "set-failure") throw failure
                ""
              } else if (command.startsWith("session remove ")) {
                val removed = command.stripPrefix("session remove ").toInt
                assert(entries.contains(removed))
                val remaining = entries.toList.filterNot(_._1 == removed).map { (index, text) =>
                  (if (index > removed) index - 1 else index) -> text
                }
                entries.clear(); entries ++= remaining
                ""
              } else {
                assert(command == (if (moduleId.isEmpty) "update" else "core-js / update"))
                if (mode == "failure") throw failure
                if (mode == "cancel") throw new com.intellij.openapi.progress.ProcessCanceledException()
                ""
              }
            }
          }
          try { task.executeForModule(project, moduleId); assert(mode == "success") }
          catch {
            case _: com.intellij.openapi.progress.ProcessCanceledException => assert(mode == "cancel")
            case caught: IllegalStateException => assert(Set("failure", "set-failure")(mode) && (caught eq failure))
          }
          assert(entries.toMap == original, s"$moduleId / $mode / ttl=$ttl changed user settings")
          assert(commands.contains(s"set $skip"))
          assert(
            commands.exists(command =>
              command.startsWith("set ") && command.contains("csrConfiguration")
            ) == (ttl && mode != "set-failure")
          )
        }
      }
    )

    check(
      "RootProject SNAPSHOT refresh uses the external ProjectRef for temporary settings and update",
      () => {
        val ref      = bitlap.sbt.analyzer.util.SbtProjectReference("file:/external%20build/", "project1Root")
        val commands = scala.collection.mutable.ListBuffer.empty[String]
        val entries  = scala.collection.mutable.Map(1 -> "existingUserSetting := true")
        val task     = new RefreshSnapshotsTask {
          override protected def supportsTtl(p: Project): Boolean                   = false
          override protected def commandOutput(p: Project, command: String): String = {
            commands += command
            if (command == "session list") entries.toSeq.map((i, value) => s"[info] $i. $value").mkString("\n")
            else if (command.startsWith("set ")) { entries(2) = command.stripPrefix("set "); "" }
            else if (command == "session remove 2") { entries.remove(2); "" }
            else { assert(command == s"${ref.selector} / update"); "" }
          }
        }
        task.executeForModule(project, ref.selector)
        assert(commands.contains(s"set ${ref.settingReference} / update / skip := false"))
        assert(entries.toMap == Map(1 -> "existingUserSetting := true"))
      }
    )

    check(
      "task await enforces background execution and propagates failure and cancellation",
      () => {
        try { SbtTaskRunner.await(project, Future.successful(1)); throw new AssertionError("EDT command accepted") }
        catch { case _: IllegalArgumentException => () }
        val task = background {
          assert(SbtTaskRunner.await(project, Future.successful(7)) == 7)
          val error = new IllegalStateException("shell failed")
          try { SbtTaskRunner.await(project, Future.failed(error)); throw new AssertionError("Failure swallowed") }
          catch { case caught: IllegalStateException => assert(caught eq error) }
          val indicator = new com.intellij.openapi.progress.EmptyProgressIndicator()
          var cancelled = false
          com.intellij.openapi.progress.ProgressManager
            .getInstance()
            .runProcess(
              new Runnable {
                override def run(): Unit = try {
                  indicator.cancel(); SbtTaskRunner.await(project, Promise[Int]().future)
                } catch { case _: com.intellij.openapi.progress.ProcessCanceledException => cancelled = true }
              },
              indicator
            )
          assert(cancelled)
        }
        eventually("await outcomes")(task.isDone)
        task.get()
      }
    )

    check(
      "shell startup isolates ANY modality and permits the Scala shell document-save callback",
      () => {
        val task = background {
          val app      = ApplicationManager.getApplication
          val progress = com.intellij.openapi.progress.ProgressManager.getInstance()
          val caller   = new com.intellij.openapi.progress.EmptyProgressIndicator(
            com.intellij.openapi.application.ModalityState.any()
          )
          progress.runProcess(
            new Runnable {
              override def run(): Unit = {
                assert(
                  com.intellij.openapi.application.ModalityState
                    .defaultModalityState() == com.intellij.openapi.application.ModalityState.any()
                )
                app.invokeAndWait(() =>
                  assert(
                    !com.intellij.openapi.application.TransactionGuard
                      .getInstance()
                      .asInstanceOf[com.intellij.openapi.application.TransactionGuardImpl]
                      .isWritingAllowed
                  )
                )
                val result = SbtTaskRunner.submit(project) {
                  assert(!app.isDispatchThread)
                  assert(
                    com.intellij.openapi.application.ModalityState
                      .defaultModalityState() == com.intellij.openapi.application.ModalityState.nonModal()
                  )
                  // Matches Scala SbtProcessManager: default invokeAndWait, a write action, then saveAllDocuments.
                  org.jetbrains.plugins.scala.extensions.invokeAndWait {
                    assert(
                      com.intellij.openapi.application.TransactionGuard
                        .getInstance()
                        .asInstanceOf[com.intellij.openapi.application.TransactionGuardImpl]
                        .isWritingAllowed
                    )
                    app.runWriteAction(new Runnable {
                      override def run(): Unit =
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
                    })
                  }
                  Future.successful(17)
                }
                assert(SbtTaskRunner.await(project, result) == 17)
                assert(caller.isRunning && !caller.isCanceled)
                assert(
                  com.intellij.openapi.application.ModalityState
                    .defaultModalityState() == com.intellij.openapi.application.ModalityState.any()
                )
              }
            },
            caller
          )
        }
        eventually("write-safe shell startup")(task.isDone)
        task.get()
      }
    )

    check(
      "shell submission rejects EDT and read locks and forwards synchronous and asynchronous failures",
      () => {
        val called = new AtomicBoolean()
        try {
          SbtTaskRunner.submit(project) { called.set(true); Future.successful(1) };
          throw new AssertionError("EDT submission accepted")
        } catch { case _: IllegalArgumentException => () }
        assert(!called.get())
        val task = background {
          ApplicationManager.getApplication.runReadAction(new Runnable {
            override def run(): Unit = {
              try {
                SbtTaskRunner.submit(project) { called.set(true); Future.successful(1) };
                throw new AssertionError("Read-locked submission accepted")
              } catch { case _: IllegalArgumentException => () }
            }
          })
          assert(!called.get())
          val error = new IllegalStateException("Shell startup failed")
          for (synchronous <- Seq(true, false)) {
            val result = SbtTaskRunner.submit[Int](project) {
              if (synchronous) throw error else Future.failed(error)
            }
            try { SbtTaskRunner.await(project, result); throw new AssertionError("Shell failure swallowed") }
            catch { case caught: IllegalStateException => assert(caught eq error) }
          }
        }
        eventually("shell submission guards")(task.isDone)
        task.get()
      }
    )

    check(
      "shell submission preserves cancellation and skips disposed projects before starting the command",
      () => {
        val called = new AtomicBoolean()
        val task   = background {
          val caller = new com.intellij.openapi.progress.EmptyProgressIndicator(
            com.intellij.openapi.application.ModalityState.any()
          )
          val pending = new AtomicReference[Future[Int]]()
          com.intellij.openapi.progress.ProgressManager
            .getInstance()
            .runProcess(
              new Runnable {
                override def run(): Unit = {
                  caller.cancel()
                  pending.set(SbtTaskRunner.submit(project) { called.set(true); Future.successful(1) })
                }
              },
              caller
            )
          try { SbtTaskRunner.await(project, pending.get()); throw new AssertionError("Cancellation swallowed") }
          catch { case _: com.intellij.openapi.progress.ProcessCanceledException => () }
          assert(!called.get())
          val disposed = java.lang.reflect.Proxy
            .newProxyInstance(
              classOf[Project].getClassLoader,
              Array(classOf[Project]),
              (_, method, _) => if (method.getName == "isDisposed") java.lang.Boolean.TRUE else null
            )
            .asInstanceOf[Project]
          val skipped = SbtTaskRunner.submit(disposed) { called.set(true); Future.successful(1) }
          try { SbtTaskRunner.await(project, skipped); throw new AssertionError("Disposed project accepted") }
          catch { case _: com.intellij.openapi.progress.ProcessCanceledException => () }
          assert(!called.get())
        }
        eventually("shell startup cancellation and disposal")(task.isDone)
        task.get()
      }
    )

    check(
      "background task runner serializes actions and releases its gate after success error and cancellation",
      () => {
        val runner = SbtTaskRunner.getInstance(project)
        for (mode <- Seq("success", "failure", "cancel")) {
          val complete  = new AtomicBoolean()
          val duplicate = new AtomicBoolean()
          val assertion = new AtomicReference[Throwable]()
          runner.run(s"Core regression $mode") {
            try {
              try {
                assert(runner.isRunning)
                background { runner.run("duplicate") { duplicate.set(true) } }.get(3, TimeUnit.SECONDS)
                assert(!duplicate.get(), "Concurrent action entered while another task held the gate")
              } catch { case error: Throwable => assertion.set(error) }
              if (mode == "failure") throw new IllegalStateException("Expected test failure")
              if (mode == "cancel") throw new com.intellij.openapi.progress.ProcessCanceledException()
            } finally complete.set(true)
          }
          eventually("task gate released")(complete.get() && !runner.isRunning)
          if (assertion.get() != null) throw new AssertionError("Task concurrency regression", assertion.get())
        }
      }
    )

    check(
      "loading errors cancellation and retries restore loading state",
      () => {
        val owner = Disposer.newDisposable()
        val view  = new DependencyAnalyzerViewImpl(project, org.jetbrains.sbt.project.SbtProjectSystem.Id, owner)
        try {
          eventually("initial loading")(!loading(view))
          val applied = new AtomicReference[String]()
          load(view) { throw new IllegalStateException("Expected loading failure") }(applied.set)
          eventually("failed loading completes")(!loading(view))
          assert(field[String](view, "loadingError") == "Expected loading failure" && applied.get() == null)
          load(view) { throw new com.intellij.openapi.progress.ProcessCanceledException() }(applied.set)
          eventually("cancelled loading completes")(!loading(view))
          assert(field[String](view, "loadingError") == null)
          load(view) { "recovered" }(applied.set)
          eventually("retry applies")(!loading(view) && applied.get() == "recovered")
        } finally Disposer.dispose(owner)
      }
    )

    check(
      "obsolete and disposed view results never overwrite the current model",
      () => {
        for (dispose <- Seq(false, true)) {
          val owner   = Disposer.newDisposable()
          val view    = new DependencyAnalyzerViewImpl(project, org.jetbrains.sbt.project.SbtProjectSystem.Id, owner)
          val started = new AtomicBoolean()
          val release = new CountDownLatch(1)
          val applied = new AtomicBoolean()
          try {
            eventually("initial loading")(!loading(view))
            load(view) { started.set(true); release.await(5, TimeUnit.SECONDS); "obsolete" }(_ => applied.set(true))
            eventually("pending load starts")(started.get())
            if (dispose) Disposer.dispose(owner)
            else field[java.util.concurrent.atomic.AtomicLong](view, "modelRevision").incrementAndGet()
            release.countDown()
            eventually("obsolete loading finishes")(!loading(view))
            assert(!applied.get())
          } finally { release.countDown(); if (!Disposer.isDisposed(owner)) Disposer.dispose(owner) }
        }
      }
    )

    check(
      "declaration edits require confirmation and support Undo Redo",
      () => {
        val text     = """libraryDependencies += "g" % "a" % "1""""
        val target   = """"g" % "a" % "1""""
        val d        = declaration(text, target)
        val file     = d.expression.getElement.getContainingFile.getVirtualFile
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
        val plan     = DependencyEditPlan.prepare(
          project,
          d,
          new com.intellij.buildsystem.model.unified.UnifiedCoordinates("g", "a", "1"),
          true
        )
        assert(document.getText == text, "Preparing or cancelling preview must not mutate the file")
        val provider = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance()
        val editor   = provider.createEditor(project, file)
        try {
          plan.apply()
          assert(document.getText.trim.isEmpty)
          val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
          assert(undo.isUndoAvailable(editor))
          undo.undo(editor)
          assert(document.getText == text)
          assert(undo.isRedoAvailable(editor))
          undo.redo(editor)
          assert(document.getText.trim.isEmpty)
        } finally provider.disposeEditor(editor)
      }
    )

    check(
      "unsupported and read only declarations cannot produce a writable edit plan",
      () => {
        val expression = """"g" % "a" % "1""""
        val d          = declaration(s"libraryDependencies += $expression", expression)
        val coordinate = new com.intellij.buildsystem.model.unified.UnifiedCoordinates("g", "a", "1")
        try {
          DependencyEditPlan.prepare(project, d.copy(editable = false), coordinate, true);
          throw new AssertionError("Unsupported declaration accepted")
        } catch { case _: IllegalArgumentException => () }
        val file = d.expression.getElement.getContainingFile.getVirtualFile
        try {
          ApplicationManager.getApplication.runWriteAction(new Runnable {
            override def run(): Unit = file.setWritable(false)
          })
          try {
            DependencyEditPlan.prepare(project, d, coordinate, true);
            throw new AssertionError("Read only declaration accepted")
          } catch { case _: IllegalArgumentException => () }
        } finally
          ApplicationManager.getApplication.runWriteAction(new Runnable {
            override def run(): Unit = file.setWritable(true)
          })
      }
    )
  }
}
