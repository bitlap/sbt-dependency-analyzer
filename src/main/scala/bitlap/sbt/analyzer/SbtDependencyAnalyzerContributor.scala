package bitlap.sbt.analyzer

import java.nio.file.*
import java.util.{ Collections, List as JList }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

import bitlap.sbt.analyzer.jbexternal.SbtDAArtifact
import bitlap.sbt.analyzer.model.*
import bitlap.sbt.analyzer.parsing.*
import bitlap.sbt.analyzer.task.*
import bitlap.sbt.analyzer.util.*
import bitlap.sbt.analyzer.util.DependencyUtils.*

import org.jetbrains.plugins.scala.project.ModuleExt
import org.jetbrains.sbt.SbtUtil
import org.jetbrains.sbt.project.*
import org.jetbrains.sbt.project.SbtProjectSystem
import org.jetbrains.sbt.project.data.*
import org.jetbrains.sbt.project.module.*

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.dependency.analyzer.{ DependencyAnalyzerDependency as Dependency, * }
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerDependency.Data
import com.intellij.openapi.externalSystem.model.*
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.dependencies.*
import com.intellij.openapi.externalSystem.model.task.*
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil

import kotlin.jvm.functions

final class SbtDependencyAnalyzerContributor(project: Project) extends DependencyAnalyzerContributor {

  import SbtDependencyAnalyzerContributor.*

  @volatile
  private var organization: String = scala.compiletime.uninitialized

  @volatile
  private var moduleIdArtifactIds: Map[String, String] = Map.empty

  private lazy val projects: ConcurrentHashMap[DependencyAnalyzerProject, ModuleNode] =
    ConcurrentHashMap[DependencyAnalyzerProject, ModuleNode]()
  private lazy val dependencyMap: ConcurrentHashMap[Long, Dependency] = ConcurrentHashMap[Long, Dependency]()

  private lazy val configurationNodesMap: ConcurrentHashMap[String, JList[DependencyScopeNode]] =
    ConcurrentHashMap[String, JList[DependencyScopeNode]]()

  override def getDependencies(
    externalProject: DependencyAnalyzerProject
  ): JList[Dependency] = {
    val moduleData = projects.get(externalProject)
    if (moduleData == null) return Collections.emptyList()
    val scopeNodes = getOrRefreshData(moduleData)
    getDependencies(moduleData, scopeNodes)
  }

  override def getDependencyScopes(
    externalProject: DependencyAnalyzerProject
  ): JList[Dependency.Scope] = {
    val moduleData = projects.get(externalProject)
    if (moduleData == null) return Collections.emptyList()
    getOrRefreshData(moduleData).asScala.map(_.toScope).asJava
    ////    DependencyScope.values.toList.map(d => scope(d.toString.toLowerCase)).toList.asJava
  }

  override def getProjects: JList[DependencyAnalyzerProject] = {
    if (projects.isEmpty) {
      val projectDataManager = ProjectDataManager.getInstance()
      projectDataManager.getExternalProjectsData(project, SbtProjectSystem.Id).asScala.foreach { projectInfo =>
        if (projectInfo.getExternalProjectStructure != null) {
          val projectStructure = projectInfo.getExternalProjectStructure
          val childrenModules  = ExternalSystemApiUtil
            .findAll(projectStructure, ProjectKeys.MODULE)
            .asScala
            .toList
            .flatMap(_.getChildren.asScala)
          val dataNodes          = childrenModules.groupBy(_.getKey)
          val rootModuleDataList = dataNodes.getOrElse(SbtModuleData.Key, Seq.empty).map(_.getData(SbtModuleData.Key))
          rootModuleDataList.foreach { moduleData =>
            // maybe null if IDEA cache
            val module = findModule(project, moduleData.baseDirectory.getAbsolutePath)
            if (module != null) {
              val externalProject   = DAProject(module, moduleData.id)
              val moduleDataNodeOpt = SbtUtil.getSbtModuleDataNode(module)
              moduleDataNodeOpt.foreach { moduleDataNode =>
                projects.put(externalProject, new ModuleNode(moduleDataNode.getData))
              }
            }
          }
          val moduleDataList =
            Seq(SbtNestedModuleData.Key)
              .flatMap(k => dataNodes.getOrElse(k, Seq.empty))
              .map(_.getData(SbtNestedModuleData.Key))
          moduleDataList.foreach { moduleData =>
            val module = findModule(project, moduleData)
            if (module != null) {
              val externalProject = DAProject(module, moduleData.getModuleName)
              if (!DependencyUtils.canIgnoreModule(module)) {
                projects.put(externalProject, new ModuleNode(moduleData))
              }
            }
          }
        }

      }

    }

    // root -> top - 1
    // root is no longer turned on by default to make it easier for us to do caching and configuration.
    val (root, others) = projects.asScala.partition(_._2.getLinkedExternalProjectPath == project.getBasePath)
    val sortedOthers   = others.keys.toList.sortBy(_.getModule.getName)
    (sortedOthers ++ root.keys.toList).asJava
  }

  override def whenDataChanged(listener: functions.Function0[kotlin.Unit], parentDisposable: Disposable): Unit = {
    val progressManager = ExternalSystemProgressNotificationManager.getInstance()
    progressManager.addNotificationListener(
      new ExternalSystemTaskNotificationListener() {
        override def onEnd(id: ExternalSystemTaskId): Unit = {
          if (id.getType == ExternalSystemTaskType.RESOLVE_PROJECT && id.getProjectSystemId == SbtProjectSystem.Id) {
            // if dependencies have changed, we must delete all analysis files (.dot)
            // however, this can only be used to monitor whether the view is open
            projects
              .values()
              .asScala
              .map(d => d.getLinkedExternalProjectPath)
              .foreach(deleteExistAnalysisFiles)

            projects.clear()
            configurationNodesMap.clear()
            dependencyMap.clear()
            listener.invoke()
          }
        }
      },
      parentDisposable
    )
  }

  private def deleteExistAnalysisFiles(modulePath: String): Unit = {
    DependencyScopeEnum.values
      .map(scope => Path.of(modulePath + analysisFilePath(scope, summon[DependencyGraphType])))
      .foreach(p => Files.deleteIfExists(p))
  }

  private def getDependencies(
    moduleData: ModuleData,
    scopeNodes: JList[DependencyScopeNode]
  ): JList[Dependency] = {
    if (scopeNodes.isEmpty) return Collections.emptyList()
    val dependencies = ListBuffer[Dependency]()
    val root         = DAModule(moduleData.getModuleName)
    root.putUserData(Module_Data, moduleData)

    val rootDependency = DADependency(root, DEFAULT_CONFIGURATION, null, Collections.emptyList())
    dependencies.append(rootDependency)
    for (scopeNode <- scopeNodes.asScala) {
      val scope = scopeNode.toScope
      for (dependencyNode <- scopeNode.getDependencies.asScala) {
        addDependencies(rootDependency, scope, dependencyNode, dependencies, moduleData.getLinkedExternalProjectPath)
      }
    }
    dependencies.asJava
  }

  private def createDependency(
    dependencyNode: DependencyNode,
    scope: Dependency.Scope,
    usage: Dependency
  ): Dependency = {
    dependencyNode match
      case _: ReferenceNode =>
        val dependency = dependencyMap.get(dependencyNode.getId)
        if (dependency == null) null
        else {
          DADependency(dependency.getData, scope, usage, dependency.getStatus)
        }
      case _ =>
        val dependencyData = getDependencyData(dependencyNode, projects)
        if (dependencyData == null) null
        else {
          val status = dependencyNode.getStatus(usage, dependencyData)
          val dep    = DADependency(dependencyData, scope, usage, status)
          dependencyMap.put(dependencyNode.getId, dep)
          dep
        }
  }

  private def getDependencyData(
    node: DependencyNode,
    projects: ConcurrentHashMap[DependencyAnalyzerProject, ModuleNode]
  ): Dependency.Data =
    node match {
      case pdn: ProjectDependencyNode =>
        val data       = DAModule(pdn.getProjectName)
        val moduleData = pdn.getModuleData(projects)
        data.putUserData(Module_Data, moduleData)
        data
      case adn: ArtifactDependencyNode =>
        val size  = SbtUtils.getLibrarySize(project, adn.getDisplayName)
        val total = SbtUtils.getLibraryTotalSize(project, adn.getDependencies.asScala.toList)
        SbtDAArtifact(adn.getGroup, adn.getModule, adn.getVersion, size, size + total)
      case _ => null
    }
  end getDependencyData

  private def addDependencies(
    usage: Dependency,
    scope: Dependency.Scope,
    dependencyNode: DependencyNode,
    dependencies: ListBuffer[Dependency],
    projectDir: String
  ): Unit = {
    val dependency = createDependency(dependencyNode, scope, usage)
    if (dependency == null) {}
    else {
      dependencies.append(dependency)
      for (node <- dependencyNode.getDependencies.asScala) {
        addDependencies(dependency, scope, node, dependencies, projectDir)
      }
    }

  }

  private def getOrganization(project: Project): String =
    if (organization != null) return organization

    // we don't actively delete configurations
    val settingsState = SettingsState.getSettings(project)
    val org           = settingsState.organization
    if (org != null && org != Constants.EMPTY_STRING) {
      return org
    }

    organization = SbtShellOutputAnalysisTask.organizationTask.executeCommand(project)
    settingsState.organization = organization
    organization
  end getOrganization

  private def getModuleIdArtifactIds(project: Project): Map[String, String] =
    if (moduleIdArtifactIds.nonEmpty) return moduleIdArtifactIds

    // we don't actively delete configurations
    val settingsState = SettingsState.getSettings(project)
    if (!settingsState.sbtModules.isEmpty) return settingsState.sbtModules.asScala.toMap

    moduleIdArtifactIds = SbtShellOutputAnalysisTask.sbtModuleNamesTask.executeCommand(project)
    settingsState.sbtModules = moduleIdArtifactIds.asJava
    moduleIdArtifactIds
  end getModuleIdArtifactIds

  private def getOrRefreshData(moduleData: ModuleData): JList[DependencyScopeNode] =
    // use to link dependencies between modules.
    // obtain the mapping of module name to file path.
    if (moduleData.getModuleName == Constants.PROJECT) return Collections.emptyList()

    val result = configurationNodesMap.computeIfAbsent(
      moduleData.getLinkedExternalProjectPath,
      _ =>
        moduleData.loadDependencies(
          project,
          getOrganization(project),
          projects.values().asScala.map(d => d.getModuleName -> d.getLinkedExternalProjectPath).toMap,
          getModuleIdArtifactIds(project)
        )
    )
    Option(result).getOrElse(Collections.emptyList())
  end getOrRefreshData

}

object SbtDependencyAnalyzerContributor
    extends SettingsState.SettingsChangeListener,
      SbtReimportProject.ReimportProjectListener:
  import com.intellij.openapi.observable.properties.AtomicProperty
  private final val dependencyIsAvailable = new AtomicProperty[Boolean](true)

  // if data change
  override def onConfigurationChanged(project: Project, settingsState: SettingsState): Unit = {
    SbtReimportProject.ReimportProjectPublisher.onReimportProject(project)
  }

  override def onReimportProject(project: Project): Unit = {
    SbtUtils.forceRefreshProject(project)
  }

  ApplicationManager.getApplication.getMessageBus.connect().subscribe(SettingsState._Topic, this)
  ApplicationManager.getApplication.getMessageBus.connect().subscribe(SbtReimportProject._Topic, this)

  private final val hasNotified = new AtomicBoolean(false)

  private def isValidFile(project: Project, file: String): Boolean = {
    if (dependencyIsAvailable.get()) {
      val lastModified = VfsUtil.findFile(Path.of(file), true).getTimeStamp
      val upToDate     =
        System.currentTimeMillis() <= lastModified + SettingsState.getSettings(project).fileCacheTimeout * 1000
      if (!upToDate) {
        dependencyIsAvailable.set(false)
      }
      dependencyIsAvailable.get()
    } else {
      dependencyIsAvailable.get()
    }
  }

  // ===========================================extensions==============================================================
  extension (projectDependencyNode: ProjectDependencyNode)

    def getModuleData(projects: ConcurrentHashMap[DependencyAnalyzerProject, ModuleNode]): ModuleData =
      projects.values.asScala
        .map(_.data)
        .find(_.getLinkedExternalProjectPath == projectDependencyNode.getProjectPath)
        .orNull
    end getModuleData

  end extension

  extension (node: DependencyNode)

    def getStatus(usage: Dependency, data: Dependency.Data): JList[Dependency.Status] =
      val status = ListBuffer[Dependency.Status]()
      if (node.getResolutionState == ResolutionState.UNRESOLVED) {
        val message = ExternalSystemBundle.message("external.system.dependency.analyzer.warning.unresolved")
        status.append(DAWarning(message))
      }
      val selectionReason = node.getSelectionReason
      data match
        case dataArtifact: Data.Artifact if selectionReason == "Evicted By" =>
          status.append(DAOmitted.INSTANCE)
          val conflictedVersion = usage.getData match
            case artifact: Data.Artifact =>
              if (artifact.getArtifactId == dataArtifact.getArtifactId) {
                artifact.getVersion
              } else null
            case _ => null

          if (conflictedVersion != null) {
            val message = ExternalSystemBundle.message(
              "external.system.dependency.analyzer.warning.version.conflict",
              conflictedVersion
            )
            status.append(DAWarning(message))
          }
        case _ =>
      status.asJava

    end getStatus

  end extension

  extension (dependencyScopeNode: DependencyScopeNode) def toScope: DAScope = toDAScope(dependencyScopeNode.getScope)
  end extension

  extension (moduleData: ModuleData)

    def loadDependencies(
      project: Project,
      organization: String,
      moduleNamePaths: Map[String, String],
      moduleIdArtifactIds: Map[String, String]
    ): JList[DependencyScopeNode] =
      val module   = findModule(project, moduleData)
      val moduleId = moduleData.getId.split(" ")(0)

      if (DependencyUtils.canIgnoreModule(module)) return Collections.emptyList()

      if (hasNotified.get() && SbtUtils.untilProjectReady(project)) {
        // must reload project to enable it
        SbtShellOutputAnalysisTask.reloadTask.executeCommand(project)
        hasNotified.compareAndSet(true, false)
      }

      // if the analysis files already exist (.dot), use it directly.
      def executeCommandOrReadExistsFile(
        scope: DependencyScopeEnum
      ): DependencyScopeNode =
        val file     = moduleData.getLinkedExternalProjectPath + analysisFilePath(scope, summon[DependencyGraphType])
        val vfsFile  = VfsUtil.findFile(Path.of(file), true)
        val useCache = vfsFile != null && isValidFile(project, file)
        // File cache for one hour
        if (useCache) {
          DependencyGraphFactory
            .getInstance(summon[DependencyGraphType])
            .buildDependencyTree(
              AnalyzerContext(
                file,
                moduleId,
                scope,
                organization,
                moduleNamePaths,
                if (moduleIdArtifactIds.isEmpty) Map(moduleId -> module.getName)
                else moduleIdArtifactIds
              ),
              createRootScopeNode(scope, project)
            )
        } else {
          dependencyIsAvailable.set(true)
          SbtShellDependencyAnalysisTask.dependencyDotTask.executeCommand(
            project,
            moduleData,
            scope,
            organization,
            moduleNamePaths,
            moduleIdArtifactIds
          )
        }
      end executeCommandOrReadExistsFile

      val result = ListBuffer[DependencyScopeNode]()
      import scala.util.control.Breaks.*
      // break, no more commands will be executed
      breakable {
        val settings = SettingsState.getSettings(project)
        for (scope <- DependencyScopeEnum.values) {
          var node: DependencyScopeNode = null
          try {

            if (settings.disableAnalyzeProvided && scope == DependencyScopeEnum.Provided) {}
            else if (settings.disableAnalyzeTest && scope == DependencyScopeEnum.Test) {}
            else if (settings.disableAnalyzeCompile && scope == DependencyScopeEnum.Compile) {}
            else {
              node = executeCommandOrReadExistsFile(scope)
            }
            if (node != null) {
              result.append(node)
            }
          } catch {
            case _: AnalyzerCommandNotFoundException =>
              if (hasNotified.compareAndSet(false, true)) {
                Notifications.notifyAndCreateSdapFile(project)
              }
              break()
            case ue: AnalyzerCommandUnknownException =>
              Notifications.notifyUnknownError(project, ue.command, ue.moduleId, ue.scope)
              break()
            case e =>
              throw e
          }
        }
      }

      result.toList.asJava
    end loadDependencies
  end extension

end SbtDependencyAnalyzerContributor
