package bitlap.sbt.analyzer.util

import java.net.URI
import java.nio.file.Path

import scala.util.Try

/** An sbt project ID is unique only within its build, including builds loaded through RootProject. */
final case class SbtProjectReference private (buildUri: URI, id: String) {
  def selector: String = s"{${buildUri.toASCIIString}}$id"

  def settingReference: String =
    s"ProjectRef(uri(${SbtProjectReference.quote(buildUri.toASCIIString)}), ${SbtProjectReference.quote(id)})"
}

object SbtProjectReference {

  private def validId(id: String): Boolean =
    id != null && id.nonEmpty && id.forall(c => Character.isLetterOrDigit(c) || "_.-".contains(c))

  private def quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  def apply(uri: String, id: String): SbtProjectReference = {
    require(validId(id), s"Invalid sbt project ID: $id")
    val parsed = URI.create(uri).normalize()
    require(parsed.isAbsolute, s"Expected an absolute sbt build URI: $uri")
    val normalized = if (parsed.getScheme.equalsIgnoreCase("file")) {
      val pathUri = Path.of(parsed).toAbsolutePath.normalize().toUri.toASCIIString
      URI.create(pathUri.stripSuffix("/") + "/")
    } else parsed
    new SbtProjectReference(normalized, id)
  }

  def fromSelector(selector: String): Option[SbtProjectReference] = {
    val close = selector.indexOf('}')
    if (!selector.startsWith("{") || close < 0) None
    else Try(apply(selector.substring(1, close), selector.substring(close + 1))).toOption
  }

  /** `set` accepts Scala ProjectRef syntax, whereas task keys accept {build-uri}project-id syntax. */
  def settingScope(selector: String): String = {
    if (selector.isEmpty) ""
    else if (selector.startsWith("{"))
      fromSelector(selector)
        .getOrElse(throw new IllegalArgumentException(s"Invalid sbt project selector: $selector"))
        .settingReference + " / "
    else {
      require(validId(selector), s"Invalid sbt project ID: $selector")
      s"LocalProject(${quote(selector)}) / "
    }
  }

  /** `projects` lists loaded builds; (*) marks a build's root and * marks the current project. */
  def loadedProjects(output: String): Set[SbtProjectReference] = {
    var build  = Option.empty[String]
    val result = Set.newBuilder[SbtProjectReference]
    output.linesIterator.foreach { line =>
      val text = line.trim.stripPrefix("[info]").trim
      if (text.startsWith("In "))
        build = Try(URI.create(text.stripPrefix("In ").trim)).toOption.filter(_.isAbsolute).map(_.toString)
      else {
        val id = text.stripPrefix("(*)").stripPrefix("*").trim
        if (validId(id)) build.foreach(uri => result += apply(uri, id))
      }
    }
    result.result()
  }
}
