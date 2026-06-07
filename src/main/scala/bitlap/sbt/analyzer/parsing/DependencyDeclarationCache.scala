package bitlap.sbt.analyzer.parsing

import java.util.concurrent.{ ConcurrentHashMap, ConcurrentMap }

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.Constants
import bitlap.sbt.analyzer.util.SbtDependencyUtils

import com.intellij.buildsystem.model.DeclaredDependency
import com.intellij.openapi.module.Module as OpenapiModule

object DependencyDeclarationCache {

  private val cache: ConcurrentMap[String, List[DeclaredDependency]] =
    new ConcurrentHashMap[String, List[DeclaredDependency]]()

  private case class CacheEntry(
    dependencies: List[DeclaredDependency],
    timestamp: Long
  )

  private val cacheWithTTL: ConcurrentMap[String, CacheEntry] = new ConcurrentHashMap[String, CacheEntry]()

  def getDeclaredDependencyWithCache(module: OpenapiModule): List[DeclaredDependency] = {
    val moduleKey = module.getName + "@" + module.getModuleFilePath

    Option(cacheWithTTL.get(moduleKey)) match {
      case Some(entry) if System.currentTimeMillis() - entry.timestamp < Constants.DECLARED_CACHE_TIMEOUT =>
        entry.dependencies
      case _ =>
        val dependencies = SbtDependencyUtils.declaredDependencies(module).asScala.toList
        if (dependencies.nonEmpty) {
          cacheWithTTL.put(moduleKey, CacheEntry(dependencies, System.currentTimeMillis()))
        }

        dependencies
    }
  }

  def invalidateCache(module: OpenapiModule): Unit = {
    val moduleKey = module.getName + "@" + module.getModuleFilePath
    cacheWithTTL.remove(moduleKey)
  }

  def clearAllCache(): Unit = {
    cacheWithTTL.clear()
  }
}
