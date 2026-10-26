package bitlap.sbt.analyzer.util

import org.jetbrains.sbt.project.module.SbtModule
import org.jetbrains.sbt.resolvers.SbtResolver

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

/** Collects the distinct resolver definitions imported for all project modules. */
object SbtResolverUtils {

  def projectResolvers(project: Project): Set[SbtResolver] = {
    ModuleManager.getInstance(project).getModules.toSet.flatMap(SbtModule.Resolvers.apply)
  }

}
