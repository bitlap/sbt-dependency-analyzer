package bitlap.sbt.analyzer

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.AnalyzerSmokeStarterCore.*
import bitlap.sbt.analyzer.jbexternal.{ DependencyAnalyzerViewImpl, SbtDAArtifact }
import bitlap.sbt.analyzer.jbexternal.util.{ DependencyGroup, ScopeItem }

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.dependency.analyzer.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

/** IDE-runtime checks for action data, asynchronous models, cache invalidation and editor lifecycle. */
object AnalyzerSmokeStarterUiFlows {

  def run(project: Project, check: (String, () => Unit) => Unit): Unit = {
    check(
      "all disabled scope combinations control contributor analysis scopes",
      () => {
        val state = SettingsState.getSettings(project)
        val saved = new SettingsState()
        saved.loadState(state)
        val contributor = new SbtDependencyAnalyzerContributor(project)
        val method      = contributor.getClass.getDeclaredMethod("enabledScopes")
        method.setAccessible(true)
        try
          for (mask <- 0 until 8) {
            state.disableAnalyzeCompile = (mask & 1) != 0
            state.disableAnalyzeProvided = (mask & 2) != 0
            state.disableAnalyzeTest = (mask & 4) != 0
            val actual   = method.invoke(contributor).asInstanceOf[Seq[DependencyScopeEnum]].toSet
            val expected = Seq(
              DependencyScopeEnum.Compile  -> state.disableAnalyzeCompile,
              DependencyScopeEnum.Provided -> state.disableAnalyzeProvided,
              DependencyScopeEnum.Test     -> state.disableAnalyzeTest
            ).collect { case (scope, false) => scope }.toSet
            assert(actual == expected, s"Disabled scopes mask $mask")
          }
        finally state.loadState(saved)
      }
    )
    check(
      "search scope warnings and usages filtering retain the correct dependency paths",
      () => {
        val owner    = Disposer.newDisposable()
        val view     = new DependencyAnalyzerViewImpl(project, org.jetbrains.sbt.project.SbtProjectSystem.Id, owner)
        val compile  = new DAScope("compile")
        val test     = new DAScope("test")
        val root     = new DADependency(new DAModule("root"), compile, null, java.util.List.of())
        val artifact = new DAArtifact("org.example", "alpha", "1")
        val a        = new DADependency(artifact, compile, root, java.util.List.of())
        val at       = new DADependency(artifact, test, root, java.util.List.of())
        val b        = new DADependency(
          new DAArtifact("org.example", "beta", "2"),
          compile,
          a,
          java.util.List.of(new DAWarning("conflict"))
        )
        def groups = field[com.intellij.ui.CollectionListModel[DependencyGroup]](
          view,
          "dependencyListModel"
        ).getItems.asScala.toList
        def selected = groups.flatMap(_.getVariances.asScala).toSet
        try {
          eventually("initial view loading")(!loading(view))
          property[java.util.List[ScopeItem]](view, "dependencyScopeFilterProperty")
            .set(java.util.List.of(new ScopeItem(compile, true), new ScopeItem(test, true)))
          property[java.util.List[DependencyGroup]](view, "dependencyModelProperty")
            .set(
              java.util.List
                .of(new DependencyGroup(java.util.List.of(a, at)), new DependencyGroup(java.util.List.of(b)))
            )
          property[String](view, "dependencyDataFilterProperty").set("ALPHA")
          eventually("case insensitive query")(selected == Set(a, at))
          property[String](view, "dependencyDataFilterProperty").set("")
          property[java.util.List[ScopeItem]](view, "dependencyScopeFilterProperty")
            .set(java.util.List.of(new ScopeItem(compile, false), new ScopeItem(test, true)))
          eventually("test scope filter")(selected == Set(at))
          property[java.util.List[ScopeItem]](view, "dependencyScopeFilterProperty")
            .set(java.util.List.of(new ScopeItem(compile, true), new ScopeItem(test, true)))
          property[java.lang.Boolean](view, "showDependencyWarningsProperty").set(true)
          eventually("warning filter")(selected == Set(b))
          property[java.lang.Boolean](view, "showDependencyWarningsProperty").set(false)
          property[String](view, "dependencyDataFilterProperty").set("alpha")
          property[String](view, "dependencyDataFilterProperty").set("BETA")
          eventually("latest query wins")(selected == Set(b))
          val tree     = field[javax.swing.tree.DefaultTreeModel](view, "dependencyTreeModel")
          val treeRoot = tree.getRoot.asInstanceOf[javax.swing.tree.DefaultMutableTreeNode]
          assert(treeRoot.getUserObject.asInstanceOf[DependencyGroup].getData == root.getData)
          assert(
            treeRoot.getFirstChild
              .asInstanceOf[javax.swing.tree.DefaultMutableTreeNode]
              .getFirstChild
              .asInstanceOf[javax.swing.tree.DefaultMutableTreeNode]
              .getUserObject
              .asInstanceOf[DependencyGroup]
              .getData == b.getData
          )
          property[DependencyAnalyzerDependency](view, "dependencyProperty").set(a)
          val usages = field[javax.swing.tree.DefaultTreeModel](view, "usagesTreeModel")
          eventually("usage paths") {
            val n = usages.getRoot.asInstanceOf[javax.swing.tree.DefaultMutableTreeNode]
            n != null && n.getChildCount == 1 &&
              n.getFirstChild
                .asInstanceOf[javax.swing.tree.DefaultMutableTreeNode]
                .getUserObject
                .asInstanceOf[DependencyGroup]
                .getVariances
                .asScala
                .toSet == Set(a, at)
          }
        } finally Disposer.dispose(owner)
      }
    )

    check(
      "artifact sizes load once in the background without changing coordinate identity",
      () => {
        val own      = new AtomicInteger()
        val total    = new AtomicInteger()
        val callback = new AtomicInteger()
        val onEdt    = new AtomicBoolean()
        val artifact = new SbtDAArtifact(
          project,
          "g",
          "a",
          "1",
          () => { assert(!ApplicationManager.getApplication.isDispatchThread); own.incrementAndGet(); 128L },
          () => { total.incrementAndGet(); 512L }
        )
        val same         = new SbtDAArtifact(project, "g", "a", "1", () => 0L, () => 0L)
        val originalHash = artifact.hashCode()
        assert(artifact == same && artifact.getSize == 0 && artifact.getTotalSize == 0)
        for (_ <- 0 until 8) artifact.loadSize(() => {
          onEdt.set(ApplicationManager.getApplication.isDispatchThread)
          callback.incrementAndGet()
          kotlin.Unit.INSTANCE
        })
        eventually("artifact sizes and callback")(artifact.getTotalSize == 512 && callback.get() == 1)
        assert(artifact.getSize == 128 && own.get() == 1 && total.get() == 1 && onEdt.get())
        assert(artifact == same && artifact.hashCode() == originalHash)
        artifact.loadSize(() => { callback.incrementAndGet(); kotlin.Unit.INSTANCE })
        assert(own.get() == 1 && callback.get() == 1)
      }
    )

    check(
      "JAR size reads the real archive and closure totals deduplicate shared cycles and skip evictions",
      () => {
        val jar    = java.nio.file.Files.createTempFile("analyzer-size-", ".jar")
        val output = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))
        try {
          output.putNextEntry(new java.util.jar.JarEntry("fixture.txt"))
          output.write("jar size payload".getBytes(java.nio.charset.StandardCharsets.UTF_8))
          output.closeEntry()
        } finally output.close()
        val table = com.intellij.openapi.roots.libraries.LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        var library: com.intellij.openapi.roots.libraries.Library = null
        val cache = bitlap.sbt.analyzer.parsing.AnalyzerCacheService.getInstance(project)
        try {
          val root = com.intellij.openapi.vfs.JarFileSystem.getInstance().refreshAndFindFileByPath(jar.toString + "!/")
          assert(root != null)
          ApplicationManager.getApplication.runWriteAction(new Runnable {
            override def run(): Unit = {
              val tableModel = table.getModifiableModel
              library = tableModel.createLibrary("sbt: workflow.fixture:core:1:jar")
              val model = library.getModifiableModel
              model.addRoot(root, com.intellij.openapi.roots.OrderRootType.CLASSES)
              model.commit()
              tableModel.commit()
            }
          })
          val actual = bitlap.sbt.analyzer.util.SbtUtils.getLibrarySize(project, "workflow.fixture:core:1")
          assert(actual == java.nio.file.Files.size(jar))
          assert(bitlap.sbt.analyzer.util.SbtUtils.getLibrarySize(project, "workflow.fixture:missing:1") == 0)
          import com.intellij.openapi.externalSystem.model.project.dependencies.ArtifactDependencyNodeImpl
          val a       = new ArtifactDependencyNodeImpl(1, "workflow.fixture", "core", "1")
          val b       = new ArtifactDependencyNodeImpl(2, "workflow.fixture", "shared", "1")
          val evicted = new ArtifactDependencyNodeImpl(3, "workflow.fixture", "old", "1")
          evicted.setSelectionReason("Evicted By")
          a.getDependencies.add(b); a.getDependencies.add(evicted)
          b.getDependencies.add(a)
          cache.librarySizes.put("workflow.fixture:shared:1", 17L)
          cache.librarySizes.put("workflow.fixture:old:1", 999L)
          assert(bitlap.sbt.analyzer.util.SbtUtils.getLibraryTotalSize(project, List(a, b, a)) == actual + 17L)
        } finally {
          ApplicationManager.getApplication.runWriteAction(new Runnable {
            override def run(): Unit = if (library != null) {
              val model = table.getModifiableModel
              model.removeLibrary(library)
              model.commit()
            }
          })
          cache.librarySizes.clear()
          java.nio.file.Files.deleteIfExists(jar)
        }
      }
    )

    check(
      "refresh and settings events invalidate caches only for the owning project and detach on disposal",
      () => {
        val owner       = Disposer.newDisposable()
        val calls       = new AtomicInteger()
        val contributor = new SbtDependencyAnalyzerContributor(project)
        val cache       = bitlap.sbt.analyzer.parsing.AnalyzerCacheService.getInstance(project)
        contributor.whenDataChanged(() => { calls.incrementAndGet(); kotlin.Unit.INSTANCE }, owner)
        try {
          val loaded = contributor.getClass.getDeclaredField("loadedProjects")
          loaded.setAccessible(true)
          loaded.set(contributor, Some(Set(bitlap.sbt.analyzer.util.SbtProjectReference("file:/external/", "root"))))
          cache.librarySizes.put("fixture", 99L)
          SettingsState.SettingsChangePublisher.onConfigurationChanged(null, SettingsState.getSettings(project))
          assert(calls.get() == 0 && cache.librarySizes.get("fixture") == 99L)
          SettingsState.SettingsChangePublisher.onConfigurationChanged(project, SettingsState.getSettings(project))
          assert(calls.get() == 1 && cache.librarySizes.isEmpty)
          assert(loaded.get(contributor) == None, "Reload must invalidate the loaded-build inventory")
          cache.librarySizes.put("fixture", 88L)
          bitlap.sbt.analyzer.util.SbtReimportProject.ReimportProjectPublisher.onReimportProject(project)
          assert(calls.get() == 2 && cache.librarySizes.isEmpty)
          Disposer.dispose(owner)
          SettingsState.SettingsChangePublisher.onConfigurationChanged(project, SettingsState.getSettings(project))
          assert(calls.get() == 2)
        } finally if (!Disposer.isDisposed(owner)) Disposer.dispose(owner)
      }
    )
  }
}
