package bitlap.sbt.analyzer

import org.scalatest.funsuite.AnyFunSuite

import com.intellij.openapi.application.ApplicationManager

/** Plugin identity must be usable before the IDE application and its services exist. */
class PluginInitializationSpec extends AnyFunSuite {

  test("initializing plugin identity does not request application services") {
    assert(ApplicationManager.getApplication == null, "Run this check outside the IDE application")
    Class.forName("bitlap.sbt.analyzer.SbtDependencyAnalyzerPlugin$", true, getClass.getClassLoader)
    assert(SbtDependencyAnalyzerPlugin.PLUGIN_ID == "org.bitlap.sbtDependencyAnalyzer")
  }
}
