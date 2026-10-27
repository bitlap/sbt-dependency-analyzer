package bitlap.sbt.analyzer.parsing

/** Associates a graph format with its sbt generation task and file extension. Declaring a format does not imply a
 *  parser is implemented.
 *
 *  @param cmd
 *    sbt task name, without a project or configuration scope
 *  @param suffix
 *    output extension without the leading dot
 */
enum DependencyGraphType(val cmd: String, val suffix: String) {
  case Dot     extends DependencyGraphType("dependencyDot", "dot")
  case GraphML extends DependencyGraphType("dependencyGraphML", "graphml")
}
