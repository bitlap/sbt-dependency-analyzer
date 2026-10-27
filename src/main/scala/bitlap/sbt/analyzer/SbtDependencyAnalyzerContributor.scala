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

import org.jetbrains.sbt.SbtUtil
import org.jetbrains.sbt.project.*
import org.jetbrains.sbt.project.SbtProjectSystem
import org.jetbrains.sbt.project.data.*
import org.jetbrains.sbt.project.module.*

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
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

import kotlin.jvm.functions

/** Adapts imported sbt projects and scoped DOT graphs to the IDE dependency analyzer. Project selectors include the
 *  build URI. Model generations prevent an invalidated load from repopulating scope caches; sbt queries are performed
 *  by the background loading path.
 */
final class SbtDependencyAnalyzerContributor(project: Project) extends DependencyAnalyzerContributor {

  import SbtDependencyAnalyzerContributor.*

  private val logger = Logger.getInstance(this.getClass)

  @volatile
  private var organization: Map[String, String] = Map.empty

  @volatile
  private var moduleIdArtifactIds: Map[String, String] = Map.empty
  @volatile private var loadedProjects                 = Option.empty[SbtProjectReference.Inventory]

  private lazy val projects: ConcurrentHashMap[DependencyAnalyzerProject, ModuleNode] =
    ConcurrentHashMap[DependencyAnalyzerProject, ModuleNode]()
  private lazy val dependencyMap: ConcurrentHashMap[Long, Dependency] = ConcurrentHashMap[Long, Dependency]()

  private case class ScopeCache(nodes: JList[DependencyScopeNode], created: Long)
  private val configurationNodesMap = new ConcurrentHashMap[String, ScopeCache]()
  private val generation            = new java.util.concurrent.atomic.AtomicLong()
  private val hasNotified           = new AtomicBoolean(false)

  private def enabledScopes: Seq[DependencyScopeEnum] = {
    val settings = SettingsState.getSettings(project)
    DependencyScopeEnum.values.toSeq.filter {
      case DependencyScopeEnum.Compile  => !settings.disableAnalyzeCompile
      case DependencyScopeEnum.Test     => !settings.disableAnalyzeTest
      case DependencyScopeEnum.Provided => !settings.disableAnalyzeProvided
    }
  }

  private def invalidate(deleteFiles: Boolean): Unit = {
    generation.incrementAndGet()
    if (deleteFiles)
      projects.values().asScala.map(_.getLinkedExternalProjectPath).toSet.foreach(deleteExistAnalysisFiles)
    AnalyzerCacheService.getInstance(project).clear()
    projects.clear()
    configurationNodesMap.clear()
    dependencyMap.clear()
    organization = Map.empty
    moduleIdArtifactIds = Map.empty
    loadedProjects = None
  }

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
    enabledScopes.map(scope => toDAScope(scope.toString)).asJava
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
            val module = com.intellij.openapi.module.ModuleManager
              .getInstance(project)
              .getModules
              .filterNot(DependencyUtils.canIgnoreModule)
              .find(m =>
                SbtUtil.getSbtModuleData(m).exists(d => d.id == moduleData.id && d.buildURI == moduleData.buildURI)
              )
              .getOrElse(null)
            if (module != null) {
              val externalProject   = DAProject(module, moduleData.id)
              val moduleDataNodeOpt = SbtUtil.getSbtModuleDataNode(module)
              moduleDataNodeOpt.foreach { moduleDataNode =>
                projects.put(externalProject, new ModuleNode(moduleDataNode.getData))
              }
            } else {
              logger.warn(f"Can not find root moduleData: ${project.getBasePath}")
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
    val connection = ApplicationManager.getApplication.getMessageBus.connect(parentDisposable)
    connection.subscribe(
      SettingsState._Topic,
      new SettingsState.SettingsChangeListener {
        override def onConfigurationChanged(changedProject: Project, settings: SettingsState): Unit = {
          if (changedProject == project) { invalidate(deleteFiles = false); listener.invoke() }
        }
      }
    )
    connection.subscribe(
      SbtReimportProject._Topic,
      new SbtReimportProject.ReimportProjectListener {
        override def onReimportProject(changedProject: Project): Unit = {
          if (changedProject == project) { invalidate(deleteFiles = true); listener.invoke() }
        }
      }
    )
    AnalyzerCacheService
      .getInstance(project)
      .whenReimported(
        parentDisposable,
        new Runnable {
          override def run(): Unit = {
            invalidate(deleteFiles = false)
            listener.invoke()
          }
        }
      )
  }

  private def deleteExistAnalysisFiles(modulePath: String): Unit = {
    DependencyScopeEnum.values
      .map(scope => Path.of(modulePath + analysisFilePath(scope, summon[DependencyGraphType])))
      .foreach { p =>
        try Files.deleteIfExists(p)
        catch { case e: java.io.IOException => logger.warn(s"Cannot remove stale analysis file $p", e) }
      }
  }

  private def getDependencies(
    moduleData: ModuleData,
    scopeNodes: JList[DependencyScopeNode]
  ): JList[Dependency] = {
    if (scopeNodes.isEmpty) return Collections.emptyList()
    dependencyMap.clear()
    val dependencies = ListBuffer[Dependency]()
    val root         = DAModule(moduleData.getModuleName)
    root.putUserData(Module_Data, moduleData)

    val rootDependency = DADependency(root, DEFAULT_CONFIGURATION, null, Collections.emptyList())
    dependencies.append(rootDependency)
    for (scopeNode <- scopeNodes.asScala) {
      dependencyMap.clear()
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
        SbtDAArtifact(
          project,
          adn.getGroup,
          adn.getModule,
          adn.getVersion,
          () => SbtUtils.getLibrarySize(project, adn.getDisplayName),
          () => SbtUtils.getLibraryTotalSize(project, List(adn))
        )
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

  private def getOrganization(moduleId: String): String = {
    val configured = Option(SettingsState.getSettings(project).organization).map(_.trim).filter(_.nonEmpty)
    configured.getOrElse {
      organization.getOrElse(
        moduleId, {
          val value = new OrganizationTask().executeForModule(project, moduleId)
          organization = organization.updated(moduleId, value)
          value
        }
      )
    }
  }

  private def getOrRefreshData(moduleData: ModuleData): JList[DependencyScopeNode] = {
    if (moduleData.getModuleName == Constants.PROJECT) return Collections.emptyList()
    val module = findModule(project, moduleData)
    if (module == null || module.isDisposed) return Collections.emptyList()
    val revision  = generation.get()
    val moduleId  = sbtProjectSelector(project, moduleData)
    val inventory = loadedProjects.filter(_ => moduleIdArtifactIds.nonEmpty).getOrElse {
      val result = new bitlap.sbt.analyzer.task.LoadedSbtProjectsTask().executeInventory(project)
      if (revision == generation.get()) loadedProjects = Some(result)
      result
    }
    val accessible = projects
      .values()
      .asScala
      .filter(d =>
        bitlap.sbt.analyzer.util.SbtProjectReference
          .fromSelector(sbtProjectSelector(project, d))
          .exists(inventory.projects.contains)
      )
      .toList
    if (!bitlap.sbt.analyzer.util.SbtProjectReference.fromSelector(moduleId).exists(inventory.projects.contains))
      throw new IllegalArgumentException(
        "This linked build is not loaded by the active sbt shell; open it in a separate IDE project to analyze it"
      )

    val key    = moduleId
    val ttl    = SettingsState.getSettings(project).fileCacheTimeout.toLong * 1000
    val cached = configurationNodesMap.get(key)
    if (cached != null && ttl > 0 && System.currentTimeMillis() - cached.created < ttl) return cached.nodes
    // Query imported projects only when their build is actually loaded by this shell.
    val references = accessible.flatMap(d => SbtProjectReference.fromSelector(sbtProjectSelector(project, d)))
    val preferred  = accessible.flatMap { data =>
      SbtProjectReference.fromSelector(sbtProjectSelector(project, data)).filter { ref =>
        ref.buildUri.getScheme == "file" && scala.util
          .Try(
            Path.of(ref.buildUri).toAbsolutePath.normalize() ==
              Path.of(data.getLinkedExternalProjectPath).toAbsolutePath.normalize()
          )
          .getOrElse(false)
      }
    }.sortBy(_.selector)
    val names = new ModuleNameTask().executeForProjects(
      project,
      references,
      inventory.current,
      preferred,
      moduleIdArtifactIds
    )
    if (revision == generation.get()) moduleIdArtifactIds = names
    val nodes = moduleData.loadDependencies(
      project,
      getOrganization(moduleId),
      accessible.map(d => sbtProjectSelector(project, d) -> d.getLinkedExternalProjectPath).toMap,
      names,
      hasNotified
    )
    if (revision == generation.get() && ttl > 0 && !nodes.isEmpty)
      configurationNodesMap.put(key, ScopeCache(nodes, System.currentTimeMillis()))
    nodes
  }

}

object SbtDependencyAnalyzerContributor:

  // ===========================================extensions==============================================================
  extension (projectDependencyNode: ProjectDependencyNode)

    def getModuleData(projects: ConcurrentHashMap[DependencyAnalyzerProject, ModuleNode]): ModuleData =
      projects.values.asScala
        .map(_.data)
        .find(_.getLinkedExternalProjectPath == projectDependencyNode.getProjectPath)
        .getOrElse(null)
    end getModuleData

  end extension

  extension (node: DependencyNode)

    def getStatus(usage: Dependency, data: Dependency.Data): JList[Dependency.Status] =
      val status = ListBuffer[Dependency.Status]()
      if (node.getResolutionState == ResolutionState.UNRESOLVED) {
        val title   = ExternalSystemBundle.message("external.system.dependency.analyzer.warning.unresolved.title")
        val message = ExternalSystemBundle.message("external.system.dependency.analyzer.warning.unresolved")
        status.append(DAWarning(title, message))
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
            val title = ExternalSystemBundle.message(
              "external.system.dependency.analyzer.warning.version.conflict.title",
              conflictedVersion
            )
            val message = ExternalSystemBundle.message(
              "external.system.dependency.analyzer.warning.version.conflict",
              conflictedVersion
            )
            status.append(DAWarning(title, message))
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
      moduleIdArtifactIds: Map[String, String],
      hasNotified: AtomicBoolean
    ): JList[DependencyScopeNode] =
      val module   = findModule(project, moduleData)
      val moduleId = sbtProjectSelector(project, moduleData)

      if (DependencyUtils.canIgnoreModule(module)) return Collections.emptyList()

      if (hasNotified.get() && SbtUtils.untilProjectReady(project)) {
        // must reload project to enable it
        SbtShellOutputAnalysisTask.reloadTask.executeCommand(project)
        hasNotified.compareAndSet(true, false)
      }

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
              node = SbtShellDependencyAnalysisTask.dependencyDotTask.executeCommand(
                project,
                moduleData,
                scope,
                organization,
                moduleNamePaths,
                moduleIdArtifactIds
              )
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
