package bitlap.sbt.analyzer.action

import bitlap.sbt.analyzer.util.DependencyUtils

object DeclarationMatcher {

  /** Resolved/evicted versions may differ from requested versions; retain scope and artifact identity on fallback. */
  def select[A](declarations: List[A])(exact: A => Boolean, identity: A => Boolean): List[A] = {
    val preferred = declarations.filter(exact)
    if (preferred.nonEmpty) preferred else declarations.filter(identity)
  }

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
