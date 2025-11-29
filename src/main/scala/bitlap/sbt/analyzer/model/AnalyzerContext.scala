package bitlap.sbt.analyzer.model

import bitlap.sbt.analyzer.DependencyScopeEnum

final case class AnalyzerContext(
  analysisFile: String,
  currentModuleId: String,
  scope: DependencyScopeEnum,
  organization: String,
  moduleNamePathsCache: Map[String, String] = Map.empty,
  moduleIdArtifactIdsCache: Map[String, String] = Map.empty, // sbt module name == sbt artifact name
  isTest: Boolean = false
)
