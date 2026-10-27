package bitlap.sbt.analyzer.jbexternal.util

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Loads the analyzer icon; the generated Kotlin facade also makes it available to Scala callers. */
fun getIcon(): Icon {
    return IconLoader.getIcon(
        "/icons/sbt_dependency_analyzer.svg",
        SbtDependencyAnalyzerIconHelper::class.java
    )
}

// Helper class for icon loading reference
private class SbtDependencyAnalyzerIconHelper
