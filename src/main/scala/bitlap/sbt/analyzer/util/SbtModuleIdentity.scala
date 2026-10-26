package bitlap.sbt.analyzer.util

/** Resolve published coordinates using imported sbt IDs and platform metadata, without IDEA name heuristics. */
object SbtModuleIdentity {

  def projectIdForArtifact(
    artifact: String,
    moduleNames: Map[String, String],
    platforms: Map[String, String]
  ): Option[String] = {
    val name       = DependencyUtils.getArtifactWithoutScalaVersion(artifact)
    val candidates = moduleNames.collect { case (id, moduleName) if moduleName == name => id }.toSeq
    val platform   =
      if (artifact.matches(".*_sjs[0-9].*")) "js"
      else if (artifact.matches(".*_native[0-9].*")) "native"
      else "jvm"
    val matchingPlatform = candidates.filter(id => platforms.get(id).contains(platform))
    val selected         = if (matchingPlatform.nonEmpty) matchingPlatform else candidates.filterNot(platforms.contains)
    selected match { case Seq(one) => Some(one); case _ => None }
  }
}
