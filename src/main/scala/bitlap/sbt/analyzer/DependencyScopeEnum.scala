package bitlap.sbt.analyzer

/** Scopes queried separately when generating dependency graphs; Runtime-only dependencies are visible through Test. */
enum DependencyScopeEnum:
  // see https://github.com/JetBrains/intellij-scala/blob/idea232.x/sbt/sbt-impl/src/org/jetbrains/sbt/language/utils/SbtDependencyCommon.scala
  case Compile, Provided, Test
