package bitlap.sbt.analyzer.util

import scala.util.Try

/** Reuses every build-qualified result of an aggregated moduleName query before querying missing projects. Console
 *  order is not project identity: values are associated with their preceding scoped heading.
 */
object SbtModuleNameQuery {
  private val external = """ProjectRef\(uri\("([^"]+)"\),\s*"([^"]+)"\)""".r
  private val heading  = """(.*?)\s*/\s*moduleName""".r

  private def reference(text: String, current: Option[SbtProjectReference]): Option[SbtProjectReference] =
    text.trim match {
      case external(uri, id)                     => Try(SbtProjectReference(uri, id)).toOption
      case selector if selector.startsWith("{")  => SbtProjectReference.fromSelector(selector)
      case "ThisBuild" | "Global" | "Zero" | "*" => None
      case id => current.flatMap(ref => Try(SbtProjectReference(ref.buildUri.toString, id)).toOption)
    }

  /** Unlabelled output is accepted only when the command returned exactly one value and no scoped headings. */
  def parse(output: String, queried: SbtProjectReference, current: Option[SbtProjectReference]): Map[String, String] = {
    var pending = Option.empty[SbtProjectReference]
    var scoped  = false
    var result  = Map.empty[String, String]
    val plain   = Vector.newBuilder[String]
    output.linesIterator.filter(_.startsWith("[info]")).foreach { line =>
      val text = line.stripPrefix("[info]").trim
      text match {
        case "moduleName"            => pending = current; scoped = true
        case heading(project)        => pending = reference(project, current); scoped = true
        case value if value.nonEmpty =>
          pending match {
            case Some(ref) =>
              require(result.get(ref.selector).forall(_ == value), s"Conflicting moduleName values for ${ref.selector}")
              result = result.updated(ref.selector, value)
            case None if !scoped => plain += value
            case _               => ()
          }
          pending = None
        case _ => pending = None
      }
    }
    val values = plain.result()
    if (!scoped && values.size == 1) Map(queried.selector -> values.head) else result
  }

  /** Prefer build-directory entry projects, then the current project, and skip every identity already in the cache.
   *  Query callbacks must reject sbt errors. This method never changes session settings or project selection.
   */
  def collect(
    requested: Seq[SbtProjectReference],
    current: Option[SbtProjectReference],
    preferred: Seq[SbtProjectReference],
    cached: Map[String, String]
  )(query: String => String): Map[String, String] = {
    val wanted  = requested.map(_.selector).toSet
    val entries = preferred.sortBy(ref => (if (current.exists(_.buildUri == ref.buildUri)) 0 else 1, ref.selector))
    val order  = (entries ++ current.toSeq ++ requested.sortBy(_.selector)).distinct.filter(ref => wanted(ref.selector))
    var result = cached.filter((_, value) => value != null && value.trim.nonEmpty)
    order.foreach { ref =>
      if (!result.contains(ref.selector)) {
        val values = parse(query(s"show ${ref.selector} / moduleName"), ref, current)
          .filter((selector, _) => wanted(selector))
        require(values.contains(ref.selector), s"No moduleName returned for ${ref.selector}")
        result = result ++ values
      }
    }
    result
  }
}
