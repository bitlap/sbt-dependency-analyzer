package bitlap.sbt.analyzer.util

/** Maps a published artifact to an imported project selector using moduleName and platform metadata. Ambiguous matches
 *  return None; IDE display names are not used as a fallback.
 */
object SbtModuleIdentity {

  /** Aggregator modules have no platform libraries and appear as JVM. Preserve explicit JS/Native evidence from their
   *  source sets regardless of module enumeration order; conflicting explicit evidence remains ambiguous.
   */
  def platformsByProject(evidence: Seq[(String, String)]): Map[String, String] =
    evidence.groupMap(_._1)(_._2).flatMap { (id, values) =>
      values.filterNot(_ == "jvm").distinct match {
        case Seq(platform) => Some(id -> platform)
        case Seq()         => Some(id -> "jvm")
        case _             => Some(id -> "ambiguous")
      }
    }

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
