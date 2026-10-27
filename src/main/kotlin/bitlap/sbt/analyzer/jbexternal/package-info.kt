package bitlap.sbt.analyzer.jbexternal

/**
 * Kotlin UI adapters adapted from JetBrains intellij-community.
 * This build compiles Kotlin before Scala: Scala may call these adapters, while this source set must not depend
 * on project classes defined under src/main/scala. Keep shared contracts in the IntelliJ APIs or this source set.
 */
