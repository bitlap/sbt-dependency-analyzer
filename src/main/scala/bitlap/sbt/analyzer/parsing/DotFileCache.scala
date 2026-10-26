package bitlap.sbt.analyzer.parsing

import java.nio.file.{ Files, Path }
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.util.Try

import bitlap.sbt.analyzer.Constants
import bitlap.sbt.analyzer.model.Dependencies

/** Bounded cache. Failed parses are never cached; regenerated files invalidate entries immediately. */
class DotFileCache(maxEntries: Int = 128) {
  private case class Entry(value: Dependencies, fingerprint: String, created: Long)
  private var revision = 0L
  private val entries  = mutable.LinkedHashMap.empty[String, Entry]

  private def fingerprint(path: String): Option[String] = Try {
    val a = Files.readAttributes(Path.of(path), classOf[BasicFileAttributes])
    s"${a.lastModifiedTime.to(TimeUnit.NANOSECONDS)}:${a.size}:${a.fileKey}"
  }.toOption

  def getCachedResult(
    path: String,
    now: Long,
    ttlMillis: Long = Constants.CACHE_TIMEOUT
  ): Option[Option[Dependencies]] = synchronized {
    entries.get(path) match {
      case Some(e) if ttlMillis > 0 && now - e.created < ttlMillis && fingerprint(path).contains(e.fingerprint) =>
        Some(Some(e.value))
      case _ => entries.remove(path); None
    }
  }

  def cacheResult(path: String, result: Option[Dependencies], fileSize: Long, now: Long): Unit = synchronized {
    for (value <- result; stamp <- fingerprint(path)) {
      entries.remove(path)
      entries.put(path, Entry(value, stamp, now))
      while (entries.size > maxEntries) entries.remove(entries.head._1)
    }
  }
  def snapshot(path: String): (Long, Option[String]) = synchronized { revision -> fingerprint(path) }

  def cacheIfUnchanged(path: String, value: Dependencies, token: (Long, Option[String]), now: Long): Unit =
    synchronized {
      if (snapshot(path) == token) token._2.foreach { stamp =>
        entries.remove(path)
        entries.put(path, Entry(value, stamp, now))
        while (entries.size > maxEntries) entries.remove(entries.head._1)
      }
    }
  def invalidateCache(path: String): Unit = synchronized { revision += 1; entries.remove(path); () }
  def clearAllCache(): Unit               = synchronized { revision += 1; entries.clear() }

  def cleanupExpired(now: Long): Unit = synchronized {
    entries.filterInPlace((_, e) => now - e.created < Constants.CACHE_TIMEOUT)
  }
  private[analyzer] def size: Int = synchronized { entries.size }
}

/** Standalone parser tests have no IntelliJ project. Production uses AnalyzerCacheService.dotFiles. */
object DotFileCache extends DotFileCache(128)
