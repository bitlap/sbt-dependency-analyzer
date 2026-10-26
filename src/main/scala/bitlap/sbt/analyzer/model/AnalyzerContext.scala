package bitlap.sbt.analyzer.model

import bitlap.sbt.analyzer.DependencyScopeEnum

final case class AnalyzerContext(
  analysisFile: String,
  currentModuleId: String,
  scope: DependencyScopeEnum,
  organization: String,
  moduleNamePathsCache: Map[String, String] = Map.empty,
  moduleIdArtifactIdsCache: Map[String, String] = Map.empty, // sbt module name == sbt artifact name
  isTest: Boolean = false,
  cacheTimeoutMillis: Long = bitlap.sbt.analyzer.Constants.CACHE_TIMEOUT,
  dotCache: bitlap.sbt.analyzer.parsing.DotFileCache = bitlap.sbt.analyzer.parsing.DotFileCache,
  modulePlatforms: Map[String, String] = Map.empty
)
