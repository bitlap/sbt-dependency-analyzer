package bitlap.sbt.analyzer

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.action.*
import bitlap.sbt.analyzer.parsing.DependencyDeclaration
import bitlap.sbt.analyzer.util.DependencyUtils

import org.jetbrains.sbt.SbtUtil
import org.jetbrains.sbt.project.SbtProjectSystem
import org.jetbrains.sbt.project.data.{ SbtBuildModuleData, SbtModuleData }

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.dependency.analyzer.*
import com.intellij.openapi.externalSystem.model.{ ExternalSystemDataKeys, ProjectKeys }
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.internal.InternalExternalProjectInfo
import com.intellij.openapi.externalSystem.model.project.{ ModuleData, ProjectData }
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem

/** Uses real module roots and imported sbt metadata, then invokes the production source lookup and action updates. */
object AnalyzerSmokeStarterSourceActions {

  def run(project: Project, check: (String, () => Unit) => Unit): Unit = {
    val coordinate = "\"g\" %% \"a\" % \"1\""
    for (
      (label, expression, scope, renamed) <- Seq(
        ("direct", coordinate, "compile", false),
        ("infix sources", coordinate + " withSources ()", "compile", false),
        ("postfix sources", "(" + coordinate + ").withSources()", "compile", false),
        ("inherited test scope", coordinate, "test", false),
        ("inherited runtime scope", coordinate, "runtime", false),
        ("shared ordered sequence", "shared", "compile", false),
        ("cross file shared sequence", "Dependencies.all", "compile", false),
        ("shared sequence in test scope", "shared", "test", false),
        ("renamed IDEA module", coordinate, "compile", true)
      )
    )
      check(
        s"imported sbt source actions: $label",
        () => {
          val base   = Files.createTempDirectory(Path.of(System.getProperty("idea.system.path")), "source-actions-")
          val build  = Files.createDirectories(base.resolve("project"))
          val shared = expression == "shared" || expression == "Dependencies.all"
          val prefix = if (expression == "shared") s"val shared = Seq($coordinate, \"g\" %% \"b\" % \"1\")\n" else ""
          val assignment = if (shared) s"libraryDependencies ++= $expression" else s"libraryDependencies += $expression"
          Files.writeString(
            base.resolve("build.sbt"),
            prefix + s"""lazy val actual = (project in file(".")).settings($assignment)"""
          )
          if (expression == "Dependencies.all")
            Files.writeString(
              build.resolve("Dependencies.scala"),
              s"object Dependencies { val all = Seq($coordinate, \"g\" %% \"b\" % \"1\") }"
            )
          LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)
          val system = SbtProjectSystem.Id
          val pd     = new ProjectData(system, "source-actions", base.toString, base.toString)
          val root   = new DataNode(ProjectKeys.PROJECT, pd, null)
          val md     = new ModuleData("actual", system, "JAVA_MODULE", "actual", base.toString, base.toString)
          val node   = root.createChild(ProjectKeys.MODULE, md)
          node.createChild(SbtModuleData.Key, SbtModuleData("actual", base.toUri, base))
          val bm = new ModuleData("build", system, "JAVA_MODULE", "fixture-build", base.toString, build.toString)
          root
            .createChild(ProjectKeys.MODULE, bm)
            .createChild(SbtBuildModuleData.Key, SbtBuildModuleData(Seq.empty, Set.empty, base.toUri))
          val modules = scala.collection.mutable.ListBuffer.empty[com.intellij.openapi.module.Module]
          try {
            ApplicationManager.getApplication.runWriteAction(new Runnable {
              override def run(): Unit = {
                for (data <- Seq(md, bm)) {
                  val module =
                    ModuleManager
                      .getInstance(project)
                      .newModule(
                        base.resolve((if (renamed && data == md) "renamed.actual" else data.getModuleName) + ".iml"),
                        "JAVA_MODULE"
                      )
                  modules += module
                  ExternalSystemModulePropertyManager.getInstance(module).setExternalOptions(system, data, pd)
                  ModuleRootModificationUtil.updateModel(
                    module,
                    model => {
                      val entry = model.addContentEntry(
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(data.getLinkedExternalProjectPath)
                      )
                      if (data == bm) entry.addSourceFolder(entry.getUrl, false)
                      ()
                    }
                  )
                }
              }
            })
            assert(
              DependencyUtils.getDeclaredDependency(modules.head).isEmpty,
              "Fixture unexpectedly has imported metadata"
            )
            ExternalProjectsManagerImpl
              .getInstance(project)
              .updateExternalProjectData(new InternalExternalProjectInfo(system, base.toString, root))
            if (expression == "Dependencies.all") {
              ApplicationManager.getApplication.runWriteAction(new Runnable {
                override def run(): Unit = ModuleRootModificationUtil.addDependency(modules.head, modules(1))
              })
              AnalyzerSmokeStarterCore.eventually("build sources indexed")(
                !com.intellij.openapi.project.DumbService.isDumb(project)
              )
            }
            val module = modules.head
            assert(SbtUtil.getSbtProjectUriAndId(module).exists(_.id == "actual"), "Imported sbt identity unavailable")
            assert(findModule(project, md) == module, "Analyzer module lookup failed")
            AnalyzerSmokeStarterCore.eventually("imported declarations recover after an early empty lookup") {
              DependencyUtils.getDeclaredDependency(module).nonEmpty
            }
            val declarations = DependencyUtils.getDeclaredDependency(module)
            assert(declarations.nonEmpty, "Production declaration lookup returned no declarations")
            val moduleData = new DAModule("actual")
            moduleData.putUserData(Module_Data, md)
            val rootDependency = new DADependency(moduleData, new DAScope(scope), null, java.util.List.of())
            val direct         = new DADependency(
              new DAArtifact("g", "a_3", "1"),
              new DAScope(scope),
              rootDependency,
              java.util.List.of()
            )
            val transitive =
              new DADependency(new DAArtifact("t", "child", "2"), new DAScope(scope), direct, java.util.List.of())
            var selected             = direct
            val context: DataContext = id =>
              if (CommonDataKeys.PROJECT.is(id)) project
              else if (ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID.is(id)) system
              else if (DependencyAnalyzerView.Companion.getDEPENDENCY.is(id)) selected
              else null
            def event(action: AnAction) = AnActionEvent.createFromDataContext(
              DependencyAnalyzerView.ACTION_PLACE,
              action.getTemplatePresentation.clone(),
              context
            )
            val goto   = new SbtDependencyAnalyzerGoToAction
            val remove = new SbtDependencyAnalyzerExcludeAction
            for (action <- Seq(goto, remove)) {
              val e = event(action)
              action.update(e)
              assert(
                e.getPresentation.isEnabled,
                s"${action.getClass.getSimpleName}: ${e.getPresentation.getDescription}"
              )
            }
            val target = SbtDependencyAnalyzerActionUtil.getModifiableDependency(event(remove))
            assert(target.navigationDeclarations.size == 1 && target.candidateDeclaredDependencies.size == 1)
            val expectedSource = target.navigationDeclarations.head.getDataContext
              .getData(DependencyDeclaration.Key)
              .expression
              .getElement
              .getContainingFile
              .getOriginalFile
              .getVirtualFile
            assert(goto.getNavigatable(event(goto)) != null, "Source navigation has no physical target")
            assert(expectedSource != null, "Resolved source has no original virtual file")
            goto.actionPerformed(event(goto))
            assert(com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).isFileOpen(expectedSource))
            selected = transitive
            val transitiveGoto = event(goto)
            goto.update(transitiveGoto)
            assert(transitiveGoto.getPresentation.isEnabled, "Transitive Goto did not find the introducing declaration")
            assert(goto.getNavigatable(transitiveGoto) != null)
            val e = event(remove)
            remove.update(e)
            assert(e.getPresentation.isEnabled, e.getPresentation.getDescription)
            assert(e.getPresentation.getText.contains("Exclude"), e.getPresentation.getText)
            val declaration =
              target.candidateDeclaredDependencies.head.getDataContext.getData(DependencyDeclaration.Key)
            val excludePlan = bitlap.sbt.analyzer.util.packagesearch.DependencyEditPlan.prepare(
              project,
              declaration,
              new com.intellij.buildsystem.model.unified.UnifiedCoordinates("t", "child", "2"),
              false
            )
            excludePlan.apply()
            val afterExclude = DependencyUtils.getDeclaredDependency(module)
            assert(
              afterExclude.exists(_.getCoordinates.getArtifactId == "a"),
              "Edited declarations must remain discoverable"
            )
            if (shared) assert(excludePlan.after.startsWith(prefix), "Shared definition changed")
            selected = direct
            val afterTarget = SbtDependencyAnalyzerActionUtil.getModifiableDependency(event(remove))
            assert(afterTarget != null && afterTarget.candidateDeclaredDependencies.nonEmpty)
            val afterDeclaration =
              afterTarget.candidateDeclaredDependencies.head.getDataContext.getData(DependencyDeclaration.Key)
            val removal = bitlap.sbt.analyzer.util.packagesearch.DependencyEditPlan
              .prepare(project, afterDeclaration, target.coordinates, true)
            removal.apply()
            val remaining = DependencyUtils.getDeclaredDependency(module)
            assert(
              !remaining.exists(_.getCoordinates.getArtifactId == "a"),
              "Removed declaration remains in source lookup"
            )
            if (shared) {
              assert(remaining.map(_.getCoordinates.getArtifactId) == List("b"))
              assert(removal.after.startsWith(prefix), "Shared definition changed")
              val provider = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance()
              val editor   =
                provider.createEditor(project, afterDeclaration.editExpression.get.getContainingFile.getVirtualFile)
              try {
                val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
                assert(undo.isUndoAvailable(editor))
                undo.undo(editor)
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
                assert(DependencyUtils.getDeclaredDependency(module).exists(_.getCoordinates.getArtifactId == "a"))
                undo.redo(editor)
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
                assert(!DependencyUtils.getDeclaredDependency(module).exists(_.getCoordinates.getArtifactId == "a"))
              } finally provider.disposeEditor(editor)
            }
          } finally {
            ExternalProjectsManagerImpl.getInstance(project).forgetExternalProjectData(system, base.toString)
            ApplicationManager.getApplication.runWriteAction(new Runnable {
              override def run(): Unit = modules.foreach(ModuleManager.getInstance(project).disposeModule)
            })
          }
        }
      )
  }
}
