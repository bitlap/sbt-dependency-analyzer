package bitlap.sbt.analyzer.activity

import bitlap.sbt.analyzer.parsing.{ DependencyDeclarationCache, DotFileCache }

import com.intellij.openapi.project.Project

class CacheCleanupActivity extends BaseProjectActivity {

  override def onRunActivity(project: com.intellij.openapi.project.Project): Unit = {
    clearAllCaches()
  }

  private def clearAllCaches(): Unit = {
    DependencyDeclarationCache.clearAllCache()
    DotFileCache.clearAllCache()
  }
}
