package bitlap.sbt.analyzer.jbexternal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.StandardProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture

/** Isolates shell submission from inherited ANY modality. The Scala shell may synchronously save documents on the EDT
 * while starting, so its default modality must allow model writes. Command submission and shell startup stay off the EDT.
 */
object SbtShellCommandExecutor {
    @JvmStatic
    fun <T> submit(project: Project, command: Callable<T>): CompletableFuture<T> {
        val app = ApplicationManager.getApplication()
        require(!app.isDispatchThread && !app.isReadAccessAllowed) {
            "sbt commands must be submitted outside the EDT and read actions"
        }
        val progress = ProgressManager.getInstance()
        val caller = progress.progressIndicator
        val local = EmptyProgressIndicator(ModalityState.nonModal())
        // A separate lifecycle avoids stopping or mutating the caller's progress indicator.
        val indicator = object : ProgressIndicator by local, StandardProgressIndicator {
            override fun isCanceled(): Boolean = local.isCanceled || caller?.isCanceled == true || project.isDisposed
            override fun checkCanceled() {
                local.checkCanceled()
                caller?.checkCanceled()
                if (project.isDisposed) throw ProcessCanceledException()
            }
        }
        val result = CompletableFuture<T>()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                progress.runProcess(Runnable {
                    indicator.checkCanceled()
                    result.complete(command.call())
                }, indicator)
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result
    }
}
