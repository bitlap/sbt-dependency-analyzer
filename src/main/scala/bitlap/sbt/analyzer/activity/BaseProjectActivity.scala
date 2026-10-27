package bitlap.sbt.analyzer.activity

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

import kotlin.coroutines.Continuation

/** Synchronous adapter for lightweight project startup work. Skips unit-test mode and disposed projects; runOnlyOnce
 *  limits execution to the first call on this instance.
 */
abstract class BaseProjectActivity(private val runOnlyOnce: Boolean = false) extends ProjectActivity {
  private var veryFirstProjectOpening: Boolean = true

  override def execute(project: Project, continuation: Continuation[? >: kotlin.Unit]): AnyRef = {
    if (
      ApplicationManager.getApplication.isUnitTestMode || (runOnlyOnce && !veryFirstProjectOpening) || project.isDisposed
    ) {
      return kotlin.Unit.INSTANCE
    }

    veryFirstProjectOpening = false
    if (onBeforeRunActivity(project)) {
      // Note: onRunActivity is executed synchronously.
      // All current implementations are lightweight operations (version check, cache cleanup).
      // If async operations are needed in the future, use coroutine with continuation.
      onRunActivity(project)
    }
    kotlin.Unit.INSTANCE
  }

  private def onBeforeRunActivity(project: Project): Boolean = true

  /** Performs lightweight synchronous startup work; implementations must not block on shell or network operations. */
  protected def onRunActivity(project: Project): Unit

}
