package bitlap.sbt.analyzer.jbexternal.util

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Load SBT Dependency Analyzer icon.
 * This function is designed to be callable from both Kotlin and Scala code.
 * 
 * In Scala, call it as: SbtDependencyAnalyzerIconKt.getSbtDependencyAnalyzerIcon()
 */
fun getIcon(): Icon {
    return IconLoader.getIcon(
        "/icons/sbt_dependency_analyzer.svg",
        SbtDependencyAnalyzerIconHelper::class.java
    )
}

// Helper class for icon loading reference
private class SbtDependencyAnalyzerIconHelper
