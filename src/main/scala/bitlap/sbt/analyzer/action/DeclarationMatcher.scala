package bitlap.sbt.analyzer.action

import bitlap.sbt.analyzer.util.DependencyUtils

/** Matches declared coordinates to resolved artifacts while respecting scope and the original cross-version operator.
 */
object DeclarationMatcher {

  /** Prefers exact matches; if none exist, retains identity matches so resolved or evicted version changes do not
   *  prevent source navigation. The caller defines the scope and identity predicates.
   */
  def select[A](declarations: List[A])(exact: A => Boolean, identity: A => Boolean): List[A] = {
    val preferred = declarations.filter(exact)
    if (preferred.nonEmpty) preferred else declarations.filter(identity)
  }

  /** Compares group, requested version and intersecting configuration scopes. Only %% and %%% strip their corresponding
   *  Scala/platform suffixes; literal % coordinates remain literal.
   */
  def matches(
    group: String,
    artifact: String,
    version: String,
    scope: String,
    targetGroup: String,
    targetArtifact: String,
    targetVersion: String,
    targetScope: String,
    cross: String = "%"
  ): Boolean = {
    def normalized(value: String): String  = Option(value).getOrElse("").trim.toLowerCase
    def scopes(value: String): Set[String] = normalized(value) match {
      case "" | "default" => Set("compile")
      case other          => other.split("[;,]").map(_.split("->", 2).head.trim).filter(_.nonEmpty).toSet
    }
    def artifactKey(value: String): String = cross match {
      case "%%%" => DependencyUtils.getArtifactWithoutScalaVersion(value)
      case "%%"  => value.replaceFirst("_(?:2\\.\\d+(?:\\.\\d+)?|3(?:\\.\\d+)*)$", "")
      case _     => value
    }
    group == targetGroup && artifact == artifactKey(targetArtifact) &&
      version == targetVersion && scopes(scope).intersect(scopes(targetScope)).nonEmpty
  }
}
