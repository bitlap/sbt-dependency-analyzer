package bitlap.sbt.analyzer.util

import java.net.URI
import java.nio.file.Path

import scala.util.Try

/** Build URI and project ID identifying one sbt project, including projects loaded through RootProject. Normalization
 *  and validation are centralized in the companion; IDs alone are not unique across builds.
 */
final case class SbtProjectReference private (buildUri: URI, id: String) {

  /** Task-key selector in sbt shell syntax: {build-uri}project-id. */
  def selector: String = s"{${buildUri.toASCIIString}}$id"

  /** Scala ProjectRef expression for set commands, with quoted and escaped URI and ID. */
  def settingReference: String =
    s"ProjectRef(uri(${SbtProjectReference.quote(buildUri.toASCIIString)}), ${SbtProjectReference.quote(id)})"
}

object SbtProjectReference {

  private def validId(id: String): Boolean =
    id != null && id.nonEmpty && id.forall(c => Character.isLetterOrDigit(c) || "_.-".contains(c))

  private def quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** Validates the project ID and absolute build URI. File URIs are normalized to absolute directory URIs with a
   *  trailing slash; non-file URIs retain their scheme.
   */
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

  /** Builds a Scala setting scope for set commands. An empty selector uses the current project, a local ID uses
   *  LocalProject and a qualified selector uses ProjectRef.
   */
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

  /** Parses build headings and project rows from sbt projects output. Accepts * for the current project and (*) for a
   *  build root; project IDs are paired with their build URI.
   */
  final case class Inventory(projects: Set[SbtProjectReference], current: Option[SbtProjectReference])

  def loadedProjects(output: String): Set[SbtProjectReference] = inventory(output).projects

  /** The * marker identifies the current project; (*) only marks a build root. Keeping this context lets shell output
   *  with local project IDs be distinguished from build-qualified external ProjectRef headings.
   */
  def inventory(output: String): Inventory = {
    var build   = Option.empty[String]
    var current = Option.empty[SbtProjectReference]
    val result  = Set.newBuilder[SbtProjectReference]
    output.linesIterator.foreach { line =>
      val text = line.trim.stripPrefix("[info]").trim
      if (text.startsWith("In "))
        build = Try(URI.create(text.stripPrefix("In ").trim)).toOption.filter(_.isAbsolute).map(_.toString)
      else {
        val id = text.stripPrefix("(*)").stripPrefix("*").trim
        if (validId(id)) build.foreach { uri =>
          val ref = apply(uri, id)
          result += ref
          if (text.startsWith("*")) current = Some(ref)
        }
      }
    }
    Inventory(result.result(), current)
  }
}
