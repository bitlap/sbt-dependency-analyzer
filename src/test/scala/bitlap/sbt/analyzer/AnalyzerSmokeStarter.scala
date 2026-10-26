package bitlap.sbt.analyzer

import java.nio.file.{ Files, Path }
import java.util.List as JList
import javax.swing.*

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.parsing.DependencyDeclaration
import bitlap.sbt.analyzer.util.packagesearch.DependencyEditPlan

import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScInfixExpr

import com.intellij.buildsystem.model.unified.UnifiedCoordinates
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.{ ApplicationManager, ApplicationStarter }
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.{ PsiDocumentManager, PsiFileFactory }
import com.intellij.psi.util.PsiTreeUtil

/** Test-only IDE entry point that opens an isolated fixture, runs PSI and Swing checks, and records results under
 *  target. This validates real IDE components without claiming a full manual import or Marketplace installation
 *  workflow.
 */
class AnalyzerSmokeStarter extends ApplicationStarter {

  override def main(args: JList[String]): Unit = {
    val output = Path.of(System.getProperty("analyzer.smoke.output"))
    Files.createDirectories(output)
    val results                                  = new StringBuilder()
    def check(name: String)(body: => Unit): Unit = {
      val start = System.nanoTime()
      body
      results.append(f"PASS $name ${(System.nanoTime() - start) / 1000000.0}%.2f ms\n")
    }
    try {
      ScalaOptionCompatibilityChecks.run((name, passed) => check(name) { assert(passed) })
      val projectPath =
        Files.createTempDirectory(Path.of(System.getProperty("idea.system.path")), "regression-project-")
      val project = com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx.openProject(
        projectPath,
        com.intellij.ide.impl.OpenProjectTask.build().asNewProject().withProjectName("Analyzer regression")
      )
      require(project != null, "Cannot create isolated regression project")
      check("What's New retries after failure and acknowledges success only once") {
        val properties = com.intellij.ide.util.PropertiesComponent.getInstance()
        val key        = activity.PluginUpdateActivity.VersionProperty
        val previous   = properties.getValue(key)
        try {
          properties.unsetValue(key)
          activity.PluginUpdateActivity.checkUpdate(project, (_, _) => false)
          assert(properties.getValue(key) == null, "Failed opening must not acknowledge the version")
          var attempts                                                                                             = 0
          val open: (org.jetbrains.plugins.scala.project.Version, com.intellij.openapi.project.Project) => Boolean =
            (_, _) => { attempts += 1; true }
          activity.PluginUpdateActivity.checkUpdate(project, open)
          assert(properties.getValue(key) == SbtDependencyAnalyzerPlugin.descriptor.getVersion)
          activity.PluginUpdateActivity.checkUpdate(project, open)
          assert(attempts == 1, "Repeated startup must not reopen an acknowledged version")
        } finally {
          if (previous == null) properties.unsetValue(key) else properties.setValue(key, previous)
        }
      }
      check("registered What's New editor renders bundled HTML without JCEF or a network request") {
        val file = new activity.WhatsNewFile("What's New", "<html><body><h1>Offline release notes</h1></body></html>")
        val provider =
          com.intellij.openapi.fileEditor.FileEditorProvider.EP_FILE_EDITOR_PROVIDER.getExtensionList.asScala
            .find(_.isInstanceOf[activity.WhatsNewEditorProvider])
            .get
        assert(provider.accept(project, file))
        val editor = provider.createEditor(project, file)
        try {
          val pane = editor.getPreferredFocusedComponent.asInstanceOf[JEditorPane]
          assert(!pane.isEditable)
          assert(pane.getText.contains("Offline release notes"))
          assert(editor.isValid && !editor.isModified)
          assert(!file.isWritable)
        } finally { provider.disposeEditor(editor) }
        assert(!editor.isValid)
      }
      check("registered menus") {
        Seq(
          "Sbt.ViewDependencyAnalyzer",
          "Sbt.ToolbarDependencyAnalyzer",
          "Sbt.ProjectViewDependencyAnalyzer",
          "Sbt.DependencyAnalyzer.OpenConfig",
          "Sbt.DependencyAnalyzer.ExcludeDependency",
          "Sbt.DependencyAnalyzer.GoTo",
          "Sbt.DependencyAnalyzer.RefreshDependencies",
          "Sbt.DependencyAnalyzer.RefreshSnapshotDependencies"
        ).foreach { id =>
          assert(ActionManager.getInstance().getAction(id) != null, id)
        }
      }
      check("settings validation and screenshot") {
        val panel = new SbtDependencyAnalyzerPanel(project)
        assert(!panel.isModified)
        panel.fileCache.setText("-1")
        try { panel.apply(); throw new AssertionError("negative timeout accepted") }
        catch { case _: com.intellij.openapi.options.ConfigurationException => () }
        assert(SettingsState.getSettings(project).fileCacheTimeout == 3600)
        panel.reset()
        paint(panel.mainPanel, output.resolve("settings.png"), 850, 260)
      }
      check("project cache invalidation deletes tracked DOT files without an editor") {
        val file = Files.createTempFile(Path.of(System.getProperty("idea.system.path")), "closed-editor-cache-", ".dot")
        Files.writeString(file, "digraph {}")
        val cache = bitlap.sbt.analyzer.parsing.AnalyzerCacheService.getInstance(project)
        cache.trackAnalysisFile(file.toString)
        cache.invalidateAnalysisFiles()
        assert(!Files.exists(file))
      }
      check("analyzer view constructs and disposes its menus and background lifecycle") {
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        try {
          val view = new bitlap.sbt.analyzer.jbexternal.DependencyAnalyzerViewImpl(
            project,
            org.jetbrains.sbt.project.SbtProjectSystem.Id,
            disposable
          )
          paint(view.createComponent(), output.resolve("analyzer.png"), 1200, 650)
        } finally com.intellij.openapi.util.Disposer.dispose(disposable)
      }
      val first  = "\"g\" % \"a\" % \"1\" % Test"
      val second = "\"g\" % \"a\" % \"1\""
      val source = s"libraryDependencies ++= Seq(\n  $first,\n  $second\n)"
      def declaration(text: String, target: String): DependencyDeclaration = {
        val directory = Path.of(System.getProperty("idea.system.path"), "fixtures")
        Files.createDirectories(directory)
        val path = Files.createTempFile(directory, "dependency-edit-", ".sbt")
        Files.writeString(path, text)
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        val file        = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
        val expr = PsiTreeUtil.findChildrenOfType(file, classOf[ScInfixExpr]).asScala.find(_.getText == target).get
        DependencyDeclaration((expr, "Test", expr))
      }
      check("Go to remains visible for multiple declarations and recovers across context changes") {
        import com.intellij.openapi.actionSystem.*
        import com.intellij.openapi.externalSystem.dependency.analyzer.*
        import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
        val declarations = List(declaration(source, first), declaration(source, first))
        var choices      = declarations
        val action       = new bitlap.sbt.analyzer.action.SbtDependencyAnalyzerGoToAction {
          override protected def candidates(e: AnActionEvent): List[DependencyDeclaration] = choices
        }
        val dependency = new DADependency(new DAArtifact("g", "a", "1"), new DAScope("test"), null, java.util.List.of())
        var sbtContext = true
        val context: DataContext = dataId =>
          if (CommonDataKeys.PROJECT.is(dataId)) project
          else if (ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID.is(dataId) && sbtContext)
            org.jetbrains.sbt.project.SbtProjectSystem.Id
          else if (DependencyAnalyzerView.Companion.getDEPENDENCY.is(dataId)) dependency
          else null
        val event = AnActionEvent.createFromDataContext(
          DependencyAnalyzerView.ACTION_PLACE,
          action.getTemplatePresentation.clone(),
          context
        )
        action.update(event)
        assert(
          event.getPresentation.isVisible,
          "Multiple declarations must offer a chooser instead of hiding the action"
        )
        assert(event.getPresentation.isEnabled == !com.intellij.openapi.project.DumbService.isDumb(project))
        assert(action.getNavigatable(event) == null, "No arbitrary declaration should be selected")
        choices = Nil
        action.update(event)
        assert(
          event.getPresentation.isVisible && !event.getPresentation.isEnabled,
          "Missing declaration should disable rather than hide"
        )
        sbtContext = false
        action.update(event)
        assert(!event.getPresentation.isVisible && !event.getPresentation.isEnabled)
        sbtContext = true
        choices = declarations
        action.update(event)
        assert(event.getPresentation.isVisible, "Hidden presentation must recover")
        val exclude = new bitlap.sbt.analyzer.action.SbtDependencyAnalyzerExcludeAction
        exclude.update(event)
        assert(
          event.getPresentation.isVisible && !event.getPresentation.isEnabled,
          "Exclude must remain visible when no modifiable declaration exists"
        )
      }
      check("tree and usages popup selection follows the clicked row on press and release") {
        import bitlap.sbt.analyzer.jbexternal.util.*
        import com.intellij.openapi.externalSystem.dependency.analyzer.*
        import com.intellij.openapi.observable.properties.AtomicProperty
        import javax.swing.tree.*
        val a = new DADependency(new DAArtifact("g", "a", "1"), new DAScope("compile"), null, java.util.List.of())
        val b = new DADependency(new DAArtifact("g", "b", "1"), new DAScope("test"), null, java.util.List.of())
        def snapshot(provider: com.intellij.openapi.actionSystem.UiDataProvider): Map[String, Any] = {
          val values = scala.collection.mutable.Map.empty[String, Any]
          val sink   = java.lang.reflect.Proxy
            .newProxyInstance(
              classOf[com.intellij.openapi.actionSystem.DataSink].getClassLoader,
              Array(classOf[com.intellij.openapi.actionSystem.DataSink]),
              (_, method, args) => {
                if (method.getName == "set")
                  values.put(args(0).asInstanceOf[com.intellij.openapi.actionSystem.DataKey[?]].getName, args(1))
                else if (method.getName == "setNull")
                  values.put(args(0).asInstanceOf[com.intellij.openapi.actionSystem.DataKey[?]].getName, null)
                null
              }
            )
            .asInstanceOf[com.intellij.openapi.actionSystem.DataSink]
          provider.uiDataSnapshot(sink)
          values.toMap
        }
        for (usages <- Seq(false, true)) {
          val root = new DefaultMutableTreeNode("root")
          root.add(new DefaultMutableTreeNode(new DependencyGroup(java.util.List.of(a))))
          root.add(new DefaultMutableTreeNode(new DependencyGroup(java.util.List.of(b))))
          val model                        = new DefaultTreeModel(root)
          val tree: AbstractDependencyTree =
            if (usages) new UsagesTree(model, new AtomicProperty(false), new AtomicProperty(false))
            else new DependencyTree(model, new AtomicProperty(false), new AtomicProperty(false))
          tree.setRootVisible(false)
          tree.setSize(600, 400)
          tree.expandPath(new TreePath(root))
          tree.setSelectionRow(0)
          val listener =
            tree.getMouseListeners.find(_.getClass.getEnclosingClass == classOf[AbstractDependencyTree]).get
          val row   = tree.getRowBounds(1)
          val press = new java.awt.event.MouseEvent(
            tree,
            java.awt.event.MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            java.awt.event.InputEvent.BUTTON3_DOWN_MASK,
            row.x + 2,
            row.y + 2,
            1,
            true,
            java.awt.event.MouseEvent.BUTTON3
          )
          listener.mousePressed(press)
          assert(tree.getSelectionRows.toSeq == Seq(1))
          assert(snapshot(tree)(DependencyAnalyzerView.Companion.getDEPENDENCY.getName) == b)
          tree.setSelectionRow(0)
          val release = new java.awt.event.MouseEvent(
            tree,
            java.awt.event.MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(),
            0,
            row.x + 2,
            row.y + 2,
            1,
            true,
            java.awt.event.MouseEvent.BUTTON3
          )
          listener.mouseReleased(release)
          assert(snapshot(tree)(DependencyAnalyzerView.Companion.getDEPENDENCY.getName) == b)
          val blank = new java.awt.event.MouseEvent(
            tree,
            java.awt.event.MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            0,
            500,
            350,
            1,
            true,
            java.awt.event.MouseEvent.BUTTON3
          )
          listener.mousePressed(blank)
          assert(tree.isSelectionEmpty)
          assert(snapshot(tree)(DependencyAnalyzerView.Companion.getDEPENDENCY.getName) == null)
        }
        val model = new DefaultListModel[DependencyGroup]()
        model.addElement(new DependencyGroup(java.util.List.of(a)))
        model.addElement(new DependencyGroup(java.util.List.of(b)))
        val list = new DependencyList(model, new AtomicProperty(false), new AtomicProperty(false))
        list.setSelectedIndex(1)
        assert(snapshot(list)(DependencyAnalyzerView.Companion.getDEPENDENCY.getName) == b)
        list.clearSelection()
        assert(snapshot(list)(DependencyAnalyzerView.Companion.getDEPENDENCY.getName) == null)
      }
      check("removal targets one exact scope and keeps valid comma syntax") {
        val plan =
          DependencyEditPlan.prepare(project, declaration(source, first), new UnifiedCoordinates("g", "a", "1"), true)
        assert(plan.after.contains(second) && !plan.after.contains("Test") && !plan.after.contains("Seq(,"))
        plan.apply()
        assert(
          plan.declaration.expression.getElement == null || !plan.declaration.expression.getElement.getText
            .contains("Test")
        )
      }
      check("exclusion changes only the selected declaration") {
        val plan = DependencyEditPlan.prepare(
          project,
          declaration(source, first),
          new UnifiedCoordinates("transitive", "child", "2"),
          false
        )
        assert(plan.after.contains(s"($first).exclude(\"transitive\", \"child\")"))
        assert(plan.after.contains(second))
        paint(DependencyEditPlan.previewComponent(plan), output.resolve("dependency-edit.png"), 1200, 300)
      }
      check("stale edit preview cannot overwrite a newer document") {
        val d        = declaration(source, first)
        val plan     = DependencyEditPlan.prepare(project, d, new UnifiedCoordinates("g", "a", "1"), true)
        val document = PsiDocumentManager.getInstance(project).getDocument(d.expression.getElement.getContainingFile)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
          project,
          new Runnable {
            override def run(): Unit = document.insertString(0, "// concurrent edit\n")
          }
        )
        try { plan.apply(); throw new AssertionError("stale preview applied") }
        catch { case _: IllegalArgumentException => () }
      }
      // PSI regression cases execute in the real Scala plugin, rather than mocked syntax trees.
      import bitlap.sbt.analyzer.parsing.{ SbtDeclarationResolver, SbtProjectResolver }
      import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
      import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition
      def fixture(text: String): ScalaFile = {
        val directory = Path.of(System.getProperty("idea.system.path"), "fixtures")
        Files.createDirectories(directory)
        val path = Files.createTempFile(directory, "declaration-", ".sbt")
        Files.writeString(path, text)
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        com.intellij.psi.PsiManager.getInstance(project).findFile(vf).asInstanceOf[ScalaFile]
      }
      def entries(text: String): Seq[SbtDeclarationResolver.Entry] = {
        val file = fixture(text)
        file.getChildren.toSeq.collect { case i: ScInfixExpr => i }.flatMap(SbtDeclarationResolver.dependencies(_))
      }
      def pointer(entry: SbtDeclarationResolver.Entry): DependencyDeclaration = {
        val manager = com.intellij.psi.SmartPointerManager.getInstance(project)
        DependencyDeclaration(
          manager.createSmartPsiElementPointer(entry.core),
          entry.scope,
          manager.createSmartPsiElementPointer(entry.site),
          entry.editable,
          entry.cross
        )
      }
      Seq("%", "%%", "%%%").foreach { cross =>
        check(s"PSI coordinates $cross retain percent characters in literals") {
          val found = entries(s"libraryDependencies += \"group%name\" $cross \"lib_core\" % \"1%2\"")
          assert(found.size == 1, found.toString)
          assert(found.head.group == "group%name" && found.head.artifact == "lib_core" && found.head.version == "1%2")
          assert(found.head.cross == cross)
        }
      }
      check("PSI resolves separate group artifact version and configuration vals") {
        val found = entries("""val group = "g"
          |val artifact = "a"
          |val version = "1" + ".2"
          |val scope = "test->test"
          |libraryDependencies += group %% artifact % version % scope""".stripMargin)
        assert(found.size == 1, found.toString)
        assert(
          found.head.group == "g" && found.head.artifact == "a" && found.head.version == "1.2" && found.head.scope == "test->test"
        )
      }
      check("PSI modifier arguments do not leak into coordinates") {
        val found = entries(
          """libraryDependencies += ("g" %% "a" % "1" % Test).exclude("other", "child").classifier("tests").cross(CrossVersion.for3Use2_13)"""
        )
        assert(found.size == 1 && found.head.group == "g" && found.head.version == "1" && found.head.scope == "test")
        assert(found.head.site.getText.endsWith("cross(CrossVersion.for3Use2_13)"))
      }
      check("PSI traverses typed sequences concatenation and result blocks") {
        val found = entries(
          """libraryDependencies ++= Seq[ModuleID]("g" % "a" % "1") ++ List(("g" % "b" % "2")) ++ { val v = "3"; Seq("g" % "c" % v) }"""
        )
        assert(found.map(_.artifact) == Seq("a", "b", "c"), found.toString)
      }
      check("PSI scoped libraryDependencies inherit the configuration") {
        val found = entries("""Test / libraryDependencies += "g" % "a" % "1"""")
        assert(found.size == 1 && found.head.scope == "test", found.toString)
      }
      check("PSI alias removal edits the use rather than shared ModuleID definition") {
        val text = """val dep = "g" %% "a" % "1"
          |libraryDependencies ++= Seq(dep % Test, dep)""".stripMargin
        val found = entries(text)
        assert(found.size == 2 && found.map(_.scope) == Seq("test", "compile"), found.toString)
        val plan =
          DependencyEditPlan.prepare(project, pointer(found.head), new UnifiedCoordinates("g", "a_3", "1"), true)
        assert(
          plan.after.contains("val dep =") && !plan.after.contains("dep % Test") && plan.after.contains("Seq( dep)")
        )
      }
      check("PSI shared dependency sequences remain navigable and cannot be accidentally edited") {
        val found = entries("""val shared = Seq("g" % "a" % "1")
          |libraryDependencies ++= shared""".stripMargin)
        assert(found.size == 1 && !found.head.editable)
        try {
          DependencyEditPlan.prepare(project, pointer(found.head), new UnifiedCoordinates("g", "a", "1"), false)
          throw new AssertionError("shared sequence was edited")
        } catch { case _: IllegalArgumentException => () }
      }
      check("PSI cyclic references terminate and computed expressions are not guessed") {
        assert(entries("""val a = b
          |val b = a
          |libraryDependencies ++= a""".stripMargin).isEmpty)
        assert(entries("""libraryDependencies += custom("g", "a", "1")""").isEmpty)
      }
      check("PSI exclusion preserves configuration and chained modifiers") {
        val found = entries("""libraryDependencies += ("g" % "a" % "1" % Test).classifier("tests")""").head
        val plan  =
          DependencyEditPlan.prepare(project, pointer(found), new UnifiedCoordinates("t", "child_sjs1_3", "2"), false)
        assert(plan.after.contains(".classifier(\"tests\")).exclude(\"t\", \"child_sjs1_3\")"))
        assert(!PsiTreeUtil.hasErrorElements(fixture(plan.after)))
      }
      check("PSI removal skips comments to remove the correct comma") {
        val found = entries("""libraryDependencies ++= Seq(("g" % "a" % "1"), /* keep comment */ "g" % "b" % "2")""")
        val plan = DependencyEditPlan.prepare(project, pointer(found.head), new UnifiedCoordinates("g", "a", "1"), true)
        assert(!PsiTreeUtil.hasErrorElements(fixture(plan.after)), plan.after)
        assert(plan.after.contains("keep comment") && plan.after.contains("\"b\""))
      }
      check("PSI direct removal in settings removes its separator") {
        Seq(
          "libraryDependencies += \"g\" % \"a\" % \"1\", name := \"keep\"",
          "name := \"keep\", libraryDependencies += \"g\" % \"a\" % \"1\""
        ).foreach { settings =>
          val file  = fixture("lazy val root = project.settings(" + settings + ")")
          val root  = file.getChildren.toSeq.collect { case p: ScPatternDefinition => p }.head
          val found = SbtProjectResolver.settings(root, "jvm").flatMap(SbtDeclarationResolver.dependencies(_)).head
          val plan  = DependencyEditPlan.prepare(project, pointer(found), new UnifiedCoordinates("g", "a", "1"), true)
          assert(!PsiTreeUtil.hasErrorElements(fixture(plan.after)), plan.after)
          assert(plan.after.contains("keep") && !plan.after.contains("libraryDependencies"))
        }
      }
      check("PSI legacy in scope and custom configurations are resolved") {
        assert(entries("""libraryDependencies in Test += "g" % "a" % "1"""").head.scope == "test")
        val found = entries("""val It = config("integration-test")
          |libraryDependencies += "g" % "a" % "1" % It""".stripMargin)
        assert(found.head.scope == "integration-test")
        val scoped = entries("""val It = config("integration-test")
          |It / libraryDependencies += "g" % "a" % "1"""".stripMargin)
        assert(scoped.head.scope == "integration-test")
      }
      check("PSI project matching uses explicit ID and ignores names paths comments and dependency strings") {
        val file = fixture("""lazy val alias = Project(id = "actual-id", base = file("other-directory"))
          |  .settings(name := "published-name", moduleName := "artifact-name", libraryDependencies += "g" % "unrelated" % "1") // fake-id
          |lazy val `another-project` = project.in(file("different-directory"))""".stripMargin)
        val definitions = file.getChildren.toSeq.collect { case p: ScPatternDefinition => p }
        assert(SbtProjectResolver.matches(definitions.head, "actual-id"))
        Seq("alias", "other-directory", "published-name", "artifact-name", "unrelated", "fake-id").foreach(id =>
          assert(!SbtProjectResolver.matches(definitions.head, id), id)
        )
        assert(
          SbtProjectResolver.matches(definitions(1), "another-project"),
          SbtProjectResolver.identity(definitions(1)).toString
        )
      }
      check("PSI project scoped settings resolve project aliases and ThisBuild separately") {
        val file = fixture("""lazy val alias = Project("actual-id", file("folder"))
          |alias / Test / libraryDependencies += "g" % "a" % "1"
          |ThisBuild / libraryDependencies += "g" % "global" % "1"
          |libraryDependencies += "g" % "local" % "1"""".stripMargin)
        val keys = file.getChildren.toSeq.collect { case i: ScInfixExpr => i.left }
        assert(SbtProjectResolver.appliesTo(keys.head, "actual-id", false))
        assert(!SbtProjectResolver.appliesTo(keys.head, "other", true))
        assert(SbtProjectResolver.appliesTo(keys(1), "other", false))
        assert(!SbtProjectResolver.appliesTo(keys(2), "other", false))
        assert(SbtProjectResolver.appliesTo(keys(2), "other", true))
        val assignment = file.getChildren.toSeq.collect { case i: ScInfixExpr => i }.head
        assert(SbtDeclarationResolver.dependencies(assignment, Some("other")).isEmpty)
        assert(SbtDeclarationResolver.dependencies(assignment, Some("actual-id")).head.scope == "test")
      }
      check("PSI platform settings include shared and only the selected platform") {
        val file =
          fixture("""lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform).in(file("components/core"))
          | .settings(libraryDependencies += "g" % "shared" % "1")
          | .jvmSettings(libraryDependencies += "g" % "jvm" % "1")
          | .jsSettings(libraryDependencies += "g" % "js" % "1")
          | .nativeSettings(libraryDependencies += "g" % "native" % "1")
          | .platformsSettings(JSPlatform, NativePlatform)(libraryDependencies += "g" % "multi" % "1")
          |lazy val renamed = core.jvm.settings(libraryDependencies += "g" % "extra" % "1")""".stripMargin)
        val definitions = file.getChildren.toSeq.collect { case p: ScPatternDefinition => p }
        assert(!SbtProjectResolver.matches(definitions(1), "coreJS"))
        assert(SbtProjectResolver.matches(definitions(1), "coreJVM"))
        Seq("JVM", "JS", "Native").foreach(s => assert(SbtProjectResolver.matches(definitions.head, "core" + s)))
        assert(!SbtProjectResolver.matches(definitions.head, "coreOther"))
        Seq("jvm", "js", "native").foreach { platform =>
          val found =
            SbtProjectResolver.settings(definitions.head, platform).flatMap(SbtDeclarationResolver.dependencies(_))
          val expected = Set("shared", platform) ++ (if (platform == "jvm") Set.empty[String] else Set("multi"))
          assert(found.map(_.artifact).toSet == expected, found.toString)
        }
        val alias = SbtProjectResolver.settings(definitions(1), "jvm").flatMap(SbtDeclarationResolver.dependencies(_))
        assert(alias.map(_.artifact).toSet == Set("shared", "jvm", "extra"), alias.toString)
      }
      check("PSI aggregate project arguments do not leak settings into the selected project") {
        val file = fixture(
          """lazy val other = project.settings(libraryDependencies += "g" % "other" % "1")
          |lazy val root = project.aggregate(other).dependsOn(other).settings(libraryDependencies += "g" % "root" % "1")""".stripMargin
        )
        val root  = file.getChildren.toSeq.collect { case p: ScPatternDefinition => p }.last
        val found = SbtProjectResolver.settings(root, "jvm").flatMap(SbtDeclarationResolver.dependencies(_))
        assert(found.map(_.artifact) == Seq("root"), found.toString)
      }
      AnalyzerSmokeStarterCore.run(project, (name, body) => check(name)(body()), declaration)
      AnalyzerSmokeStarterUiFlows.run(project, (name, body) => check(name)(body()))
      Files.writeString(output.resolve("smoke-results.txt"), results.toString)
      println(results.toString)
      System.exit(0)
    } catch {
      case error: Throwable =>
        val stack = new java.io.StringWriter()
        error.printStackTrace(new java.io.PrintWriter(stack))
        Files.writeString(output.resolve("smoke-results.txt"), results.toString + stack.toString)
        error.printStackTrace()
        System.exit(1)
    }
  }

  private def paint(component: JComponent, path: Path, width: Int, height: Int): Unit = {
    component.setSize(width, height)
    def layout(container: java.awt.Container): Unit = {
      container.doLayout()
      container.getComponents.collect { case c: java.awt.Container => c }.foreach(layout)
    }
    layout(component)
    val image    = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    component.printAll(graphics)
    graphics.dispose()
    javax.imageio.ImageIO.write(image, "png", path.toFile)
  }
}
