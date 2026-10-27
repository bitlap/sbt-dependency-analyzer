package bitlap.sbt.analyzer.parsing

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.util.SbtDependencyUtils

import com.intellij.buildsystem.model.DeclaredDependency
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.util.PsiModificationTracker

/** Caches successful declarations per module until PSI, module roots or indexing state changes. Empty lookups during
 *  import are retried instead of permanently disabling source actions. Callers must hold read access for resolution.
 */
final class DependencyDeclarationCache {
  private case class Stamp(psi: Long, roots: Long, indexing: Long)
  private case class Entry(stamp: Stamp, dependencies: List[DeclaredDependency])

  private def stamp(module: Module): Stamp = {
    val project = module.getProject
    Stamp(
      PsiModificationTracker.getInstance(project).getModificationCount,
      ProjectRootModificationTracker.getInstance(project).getModificationCount,
      DumbService.getInstance(project).getModificationTracker.getModificationCount
    )
  }
  private val entries = scala.collection.mutable.Map.empty[Module, Entry]

  def getDeclaredDependencyWithCache(module: Module): List[DeclaredDependency] = synchronized {
    if (module == null || module.isDisposed || DumbService.isDumb(module.getProject)) return Nil
    val current = stamp(module)
    entries.get(module) match {
      case Some(entry) if entry.stamp == current => entry.dependencies
      case _                                     =>
        val dependencies = SbtDependencyUtils.declaredDependencies(module).asScala.toList
        if (dependencies.nonEmpty && stamp(module) == current) entries.put(module, Entry(current, dependencies))
        else entries.remove(module)
        dependencies
    }
  }
  def invalidateCache(module: Module): Unit = synchronized { entries.remove(module); () }
  def clearAllCache(): Unit                 = synchronized { entries.clear() }
}
