package bitlap.sbt.analyzer.activity

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

import kotlin.coroutines.Continuation

abstract class BaseProjectActivity(private val runOnlyOnce: Boolean = false) extends ProjectActivity {
  private var veryFirstProjectOpening: Boolean = true

  override def execute(project: Project, continuation: Continuation[? >: kotlin.Unit]): AnyRef = {
    if (
      ApplicationManager.getApplication.isUnitTestMode || (runOnlyOnce && !veryFirstProjectOpening) || project.isDisposed
    ) {
      return continuation
    }

    veryFirstProjectOpening = false
    if (onBeforeRunActivity(project)) {
      // Note: onRunActivity is executed synchronously.
      // All current implementations are lightweight operations (version check, cache cleanup).
      // If async operations are needed in the future, use coroutine with continuation.
      onRunActivity(project)
    }
    continuation
  }

  private def onBeforeRunActivity(project: Project): Boolean = true

  protected def onRunActivity(project: Project): Unit

}
