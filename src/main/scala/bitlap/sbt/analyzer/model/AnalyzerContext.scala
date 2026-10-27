package bitlap.sbt.analyzer.model

import bitlap.sbt.analyzer.DependencyScopeEnum

/** Inputs for parsing one project and scope, including identity maps and the owning DOT cache.
 *
 *  @param analysisFile
 *    path to the generated graph file
 *  @param scope
 *    sbt configuration represented by this graph
 *  @param dotCache
 *    project-owned parsed-file cache; standalone tests may supply an isolated cache
 *  @param currentModuleId
 *    build-qualified sbt project selector used as the key in the module maps
 *  @param organization
 *    effective organization used to recognize self and inter-project nodes
 *  @param moduleNamePathsCache
 *    project selector to imported project directory
 *  @param moduleIdArtifactIdsCache
 *    project selector to sbt moduleName, before cross-version suffixes
 *  @param isTest
 *    skips IDE progress checks for standalone parser tests
 *  @param cacheTimeoutMillis
 *    parsed-file cache lifetime in milliseconds; non-positive values disable reuse
 *  @param modulePlatforms
 *    project selector to imported platform (jvm, js or native), used to disambiguate artifacts
 */
final case class AnalyzerContext(
  analysisFile: String,
  currentModuleId: String,
  scope: DependencyScopeEnum,
  organization: String,
  moduleNamePathsCache: Map[String, String] = Map.empty,
  moduleIdArtifactIdsCache: Map[String, String] = Map.empty,
  isTest: Boolean = false,
  cacheTimeoutMillis: Long = bitlap.sbt.analyzer.Constants.CACHE_TIMEOUT,
  dotCache: bitlap.sbt.analyzer.parsing.DotFileCache = bitlap.sbt.analyzer.parsing.DotFileCache,
  modulePlatforms: Map[String, String] = Map.empty
)
