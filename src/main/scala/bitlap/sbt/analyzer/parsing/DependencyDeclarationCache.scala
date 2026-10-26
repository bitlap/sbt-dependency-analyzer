package bitlap.sbt.analyzer.parsing

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.util.SbtDependencyUtils

import com.intellij.buildsystem.model.DeclaredDependency
import com.intellij.openapi.module.Module
import com.intellij.psi.util.PsiModificationTracker

/** Caches declarations per IDE module and project PSI modification count. Any PSI modification makes existing entries
 *  stale on the next lookup. Callers must hold read access for resolution.
 */
final class DependencyDeclarationCache {
  private case class Entry(modification: Long, dependencies: List[DeclaredDependency])
  private val entries = scala.collection.mutable.Map.empty[Module, Entry]

  def getDeclaredDependencyWithCache(module: Module): List[DeclaredDependency] = synchronized {
    if (module == null || module.isDisposed) return Nil
    val modification = PsiModificationTracker.getInstance(module.getProject).getModificationCount
    entries.get(module) match {
      case Some(entry) if entry.modification == modification => entry.dependencies
      case _                                                 =>
        val dependencies = SbtDependencyUtils.declaredDependencies(module).asScala.toList
        entries.put(module, Entry(modification, dependencies))
        dependencies
    }
  }
  def invalidateCache(module: Module): Unit = synchronized { entries.remove(module); () }
  def clearAllCache(): Unit                 = synchronized { entries.clear() }
}
