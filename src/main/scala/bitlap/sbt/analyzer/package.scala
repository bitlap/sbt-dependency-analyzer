package bitlap.sbt.analyzer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.parsing.DependencyGraphType

import org.jetbrains.sbt.project.*

import com.intellij.buildsystem.model.unified.UnifiedCoordinates
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerDependency
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerDependency.Data
import com.intellij.openapi.externalSystem.model.project.*
import com.intellij.openapi.externalSystem.service.project.IdeModelsProviderImpl
import com.intellij.openapi.externalSystem.util.*
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

lazy val Module_Data: Key[ModuleData] = Key.create[ModuleData]("SbtDependencyAnalyzerContributor.ModuleData")

given ExecutionContext = ExecutionContext.Implicits.global

given DependencyGraphType = DependencyGraphType.Dot

def getUnifiedCoordinates(dependency: DependencyAnalyzerDependency): UnifiedCoordinates =
  dependency.getData match {
    case data: DependencyAnalyzerDependency.Data.Artifact => getUnifiedCoordinates(data)
    case data: DependencyAnalyzerDependency.Data.Module   => getUnifiedCoordinates(data)
  }

def getUnifiedCoordinates(data: DependencyAnalyzerDependency.Data.Artifact): UnifiedCoordinates =
  UnifiedCoordinates(data.getGroupId, data.getArtifactId, data.getVersion)

def getUnifiedCoordinates(data: DependencyAnalyzerDependency.Data.Module): UnifiedCoordinates = {
  val moduleData = data.getUserData(Module_Data)
  if (moduleData == null) return null
  UnifiedCoordinates(moduleData.getGroup, moduleData.getExternalName, moduleData.getVersion)
}

/** Walks toward the nearest module ancestor and returns its direct dependency child with the imported IDE module.
 *  Returns null if no usable module ancestor exists.
 */
def getParentModule(
  project: Project,
  dependency: DependencyAnalyzerDependency
): (DependencyAnalyzerDependency, Module) = {
  if (dependency == null) return null
  var direct = dependency
  var parent = dependency.getParent
  while (parent != null) {
    parent.getData match {
      case data: Data.Module =>
        val module = getModule(project, data)
        return if (module == null) null else direct -> module
      case _ => direct = parent; parent = parent.getParent
    }
  }
  null
}

def getModule(project: Project, data: DependencyAnalyzerDependency.Data.Module): Module = {
  val moduleData: ModuleData = data.getUserData(Module_Data)
  if (moduleData == null) return null
  findModule(project, moduleData)
}

def findDependsModules(module: Module): List[Module] = {
  val modelsProvider = new IdeModelsProviderImpl(module.getProject)
  modelsProvider.getAllDependentModules(module).asScala.toList
}

/** Uses the imported external ID and path before the platform name generator, so renamed and qualified IDE display
 *  names do not determine module identity. Ambiguous imported identities never select an arbitrary module.
 */
def findModule(project: Project, moduleData: ModuleData): Module = {
  val imported = com.intellij.openapi.module.ModuleManager.getInstance(project).getModules.toList.filter { module =>
    val properties = com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager.getInstance(module)
    !module.isDisposed && properties.getExternalSystemId == moduleData.getOwner.getId &&
      properties.getLinkedProjectId == moduleData.getId &&
      com.intellij.openapi.util.io.FileUtil
        .pathsEqual(properties.getLinkedProjectPath, moduleData.getLinkedExternalProjectPath)
  }
  imported match {
    case List(module) => module
    case Nil          => new IdeModelsProviderImpl(project).findIdeModule(moduleData)
    case _            => null
  }
}

def findModule(project: Project, projectData: ProjectData): Module =
  findModule(project, projectData.getLinkedExternalProjectPath)

def findModule(project: Project, projectPath: String): Module = {
  val moduleNode = ExternalSystemApiUtil.findModuleNode(project, SbtProjectSystem.Id, projectPath)
  if (moduleNode == null) return null
  findModule(project, moduleNode.getData)
}

/** Returns the imported build-qualified sbt selector, without inferring identity from display or directory names.
 *  Throws if the module has no imported sbt identity.
 */
def sbtProjectSelector(project: Project, data: ModuleData): String = {
  val module = findModule(project, data)
  Option(module)
    .flatMap(org.jetbrains.sbt.SbtUtil.getSbtProjectUriAndId)
    .map(ref => bitlap.sbt.analyzer.util.SbtProjectReference(ref.uri, ref.id).selector)
    .getOrElse(throw new IllegalArgumentException(s"No imported sbt project identity for ${data.getModuleName}"))
}

/** Pauses a polling worker after checking cancellation; interruption restores the thread flag and cancels the
 *  operation.
 */
def waitInterval(sleep: Duration = Constants.INTERVAL_TIMEOUT): Unit = {
  com.intellij.openapi.progress.ProgressManager.checkCanceled()
  try Thread.sleep(sleep.toMillis)
  catch {
    case e: InterruptedException =>
      Thread.currentThread().interrupt(); throw new com.intellij.openapi.progress.ProcessCanceledException(e)
  }
}
