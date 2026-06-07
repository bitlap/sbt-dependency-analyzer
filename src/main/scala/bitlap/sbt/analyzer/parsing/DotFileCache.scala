package bitlap.sbt.analyzer.parsing

import java.util.concurrent.ConcurrentHashMap

import bitlap.sbt.analyzer.Constants
import bitlap.sbt.analyzer.model.Dependencies

object DotFileCache {

  private case class CacheEntry(
    dependencies: Option[Dependencies],
    timestamp: Long,
    fileSize: Long
  )

  private val cache: ConcurrentHashMap[String, CacheEntry] = new ConcurrentHashMap[String, CacheEntry]()

  def getCachedResult(filePath: String, currentTime: Long): Option[Option[Dependencies]] = {
    Option(cache.get(filePath)) match {
      case Some(entry) if currentTime - entry.timestamp < Constants.CACHE_TIMEOUT =>
        Some(entry.dependencies)
      case _ => None
    }
  }

  def cacheResult(filePath: String, result: Option[Dependencies], fileSize: Long, currentTime: Long): Unit = {
    cache.put(filePath, CacheEntry(result, currentTime, fileSize))
  }

  def invalidateCache(filePath: String): Unit = {
    cache.remove(filePath)
  }

  def clearAllCache(): Unit = {
    cache.clear()
  }

  def cleanupExpired(currentTime: Long): Unit = {
    val iterator = cache.entrySet().iterator()
    while (iterator.hasNext) {
      val entry = iterator.next()
      if (currentTime - entry.getValue.timestamp >= Constants.CACHE_TIMEOUT) {
        iterator.remove()
      }
    }
  }
}
