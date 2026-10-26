package bitlap.sbt.analyzer

import javax.swing.Icon

import bitlap.sbt.analyzer.jbexternal.util.SbtDependencyAnalyzerIconKt

/** Exposes the shared icon through the Kotlin bridge used by both source sets. */
object SbtDependencyAnalyzerIcons:

  val ICON: Icon = SbtDependencyAnalyzerIconKt.getIcon()

end SbtDependencyAnalyzerIcons
