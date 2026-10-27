package bitlap.sbt.analyzer.parsing

/** Selects an implemented graph parser. GraphML is declared as a format but is not implemented. */
object DependencyGraphFactory {

  def getInstance(dependencyGraphType: DependencyGraphType): DependencyGraphParser = {
    dependencyGraphType match
      case DependencyGraphType.Dot => DotDependencyGraphParser.instance
      // Keep unsupported formats explicit instead of silently choosing the DOT parser.
      case DependencyGraphType.GraphML => throw new IllegalArgumentException("Parser type is not supported")
  }

}
