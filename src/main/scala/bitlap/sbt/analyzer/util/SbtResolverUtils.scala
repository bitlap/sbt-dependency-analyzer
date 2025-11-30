package bitlap.sbt.analyzer.util

import org.jetbrains.sbt.project.module.SbtModule
import org.jetbrains.sbt.resolvers.SbtResolver

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

object SbtResolverUtils {

  def projectResolvers(project: Project): Set[SbtResolver] = {
    ModuleManager.getInstance(project).getModules.toSet.flatMap(SbtModule.Resolvers.apply)
  }

}
