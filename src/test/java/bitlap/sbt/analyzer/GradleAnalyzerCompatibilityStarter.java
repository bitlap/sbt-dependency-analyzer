package bitlap.sbt.analyzer;

import com.intellij.ide.plugins.DynamicPlugins;
import com.intellij.ide.plugins.PluginMainDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.util.registry.Registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Runs against real registered actions in an isolated IDE; does not depend on the analyzer plugin classloader. */
public final class GradleAnalyzerCompatibilityStarter implements ApplicationStarter {
  private static final String ACTION = "Gradle.ToolbarDependencyAnalyzer";
  private static final String WRAPPER = "bitlap.sbt.analyzer.jbexternal.GradleAnalyzerContextAction";
  private final StringBuilder results = new StringBuilder();

  private void check(String name, boolean condition) {
    if (!condition) throw new AssertionError(name);
    results.append("PASS ").append(name).append('\n');
  }

  private AnActionEvent event(String system) {
    DataContext context = system == null ? DataContext.EMPTY_CONTEXT : SimpleDataContext.builder()
        .add(ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID, new ProjectSystemId(system)).build();
    AnAction action = ActionManager.getInstance().getAction(ACTION);
    return AnActionEvent.createEvent(action, context, null, "ExternalSystemView", ActionUiKind.TOOLBAR, null);
  }

  private void contexts(ActionManager manager) {
    AnAction action = manager.getAction(ACTION);
    check("override is registered", action != null && action.getClass().getName().equals(WRAPPER + "$Toolbar"));
    for (String system : new String[]{"SBT", "GRADLE", "MAVEN", null}) {
      AnActionEvent e = event(system);
      action.update(e);
      check("visibility in " + system, e.getPresentation().isVisible() == !"SBT".equals(system));
      check("enabled state in " + system, e.getPresentation().isEnabled() == !"SBT".equals(system));
      check("presentation retained in " + system,
          e.getPresentation().getIcon() != null && e.getPresentation().getText() != null);
    }
    AnAction base = ((ActionManagerImpl) manager).getBaseAction((OverridingAction) action);
    check("base is the official Gradle action", base != null && (base instanceof ActionStub ? ((ActionStub) base).getClassName() : base.getClass().getName()).startsWith("org.jetbrains.plugins.gradle."));
    check("override uses compatibility plugin classloader",
        PluginManagerCore.getPlugin(PluginId.getId("org.bitlap.sbtDependencyAnalyzer")).getPluginClassLoader() == action.getClass().getClassLoader());
    // Registry gating remains part of the inherited platform action update.
    Registry.get("external.system.dependency.analyzer").setValue(false);
    try {
      AnActionEvent e = event("GRADLE");
      action.update(e);
      check("platform registry switch is preserved", !e.getPresentation().isVisible());
    } finally {
      Registry.get("external.system.dependency.analyzer").setValue(true);
    }
    DefaultActionGroup group = (DefaultActionGroup) manager.getAction("Gradle.View.ActionsToolbar.OtherActionsPanel");
    check("existing toolbar references use override",
        java.util.Arrays.stream(group.getChildren(manager)).anyMatch(child -> child == action));
  }

  private void sbtContexts(ActionManager manager) throws Exception {
    var project = com.intellij.openapi.project.ProjectManager.getInstance().getDefaultProject();
    ClassLoader loader = PluginManagerCore.getPlugin(PluginId.getId("org.bitlap.sbtDependencyAnalyzer")).getPluginClassLoader();
    Class<?> settingsType = loader.loadClass("org.jetbrains.sbt.settings.SbtSettings");
    Object settings = settingsType.getMethod("getInstance", com.intellij.openapi.project.Project.class).invoke(null, project);
    var getLinked = settingsType.getMethod("getLinkedProjectsSettings");
    var setLinked = settingsType.getMethod("setLinkedProjectsSettings", java.util.Collection.class);
    var previous = new java.util.ArrayList<>((java.util.Collection<?>) getLinked.invoke(settings));
    Object linked = loader.loadClass("org.jetbrains.sbt.project.settings.SbtProjectSettings").getConstructor().newInstance();
    linked.getClass().getMethod("setExternalProjectPath", String.class).invoke(linked, System.getProperty("idea.system.path"));
    try {
      setLinked.invoke(settings, java.util.List.of(linked));
      AnAction action = manager.getAction("Sbt.ToolbarDependencyAnalyzer");
      for (String system : new String[]{"SBT", "GRADLE", "MAVEN", null}) {
        var builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project);
        if (system != null) builder.add(ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID, new ProjectSystemId(system));
        AnActionEvent e = AnActionEvent.createEvent(action, builder.build(), null, "ExternalSystemView", ActionUiKind.TOOLBAR, null);
        action.update(e);
        check("sbt entry in linked-project context " + system,
            e.getPresentation().isVisible() == (system == null || system.equals("SBT")));
      }
      setLinked.invoke(settings, java.util.List.of());
      AnActionEvent e = AnActionEvent.createEvent(action,
          SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build(), null,
          "ExternalSystemView", ActionUiKind.TOOLBAR, null);
      action.update(e);
      check("sbt entry absent without linked build", !e.getPresentation().isVisible());
    } finally {
      setLinked.invoke(settings, previous);
    }
  }

  private AnAction official(ActionManager manager, AnAction wrapper) {
    AnAction base = ((ActionManagerImpl) manager).getBaseAction((OverridingAction) wrapper);
    if (base instanceof ActionStub stub) {
      base = com.intellij.openapi.application.ApplicationManager.getApplication()
          .instantiateClass(stub.getClassName(), stub.getPlugin());
      stub.initAction(base);
    }
    return base;
  }

  private void popupContexts(ActionManager manager) throws Exception {
    Path directory = Files.createTempDirectory(Path.of(System.getProperty("idea.system.path")), "popup-project-");
    var project = com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().openProject(directory,
        com.intellij.ide.impl.OpenProjectTask.build().asNewProject().withProjectName("Popup compatibility"));
    if (project == null) throw new AssertionError("Cannot create popup project");
    try {
      var owner = new ProjectSystemId("SBT");
      var pd = new com.intellij.openapi.externalSystem.model.project.ProjectData(owner, "popup-root", directory.toString(), directory.toString());
      var md = new com.intellij.openapi.externalSystem.model.project.ModuleData("popup-module", owner, "JAVA_MODULE", "popup-module", directory.toString(), directory.toString());
      com.intellij.openapi.module.Module[] holder = new com.intellij.openapi.module.Module[1];
      com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() -> {
        var module = com.intellij.openapi.module.ModuleManager.getInstance(project).newModule(directory.resolve("popup-module.iml"), "JAVA_MODULE");
        com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager.getInstance(module).setExternalOptions(owner, md, pd);
        holder[0] = module;
      });
      var module = holder[0];
      var structure = new com.intellij.openapi.externalSystem.view.ExternalProjectsStructure(project, new com.intellij.ui.treeStructure.Tree());
      com.intellij.openapi.util.Disposer.register(project, structure);
      var view = (com.intellij.openapi.externalSystem.view.ExternalProjectsView) java.lang.reflect.Proxy.newProxyInstance(
          getClass().getClassLoader(), new Class[]{com.intellij.openapi.externalSystem.view.ExternalProjectsView.class},
          (proxy, method, args) -> switch (method.getName()) {
            case "getProject" -> project;
            case "getStructure" -> structure;
            case "getSystemId" -> owner;
            default -> method.getReturnType() == boolean.class ? false : null;
          });
      structure.init(view);
      var node = new com.intellij.openapi.externalSystem.view.ModuleNode(view,
          new com.intellij.openapi.externalSystem.model.DataNode<>(com.intellij.openapi.externalSystem.model.ProjectKeys.MODULE, md, null), null, true);
      var child = new com.intellij.openapi.externalSystem.view.ExternalSystemNode<Object>(view, node) {};
      var projectNode = new com.intellij.openapi.externalSystem.view.ProjectNode(view,
          new com.intellij.openapi.externalSystem.model.DataNode<>(com.intellij.openapi.externalSystem.model.ProjectKeys.PROJECT, pd, null));
      for (String actionId : new String[]{"Gradle.ViewDependencyAnalyzer", "Gradle.ProjectViewDependencyAnalyzer"}) {
        AnAction action = manager.getAction(actionId);
        check(actionId + " popup override registered", action != null && action.getClass().getName().equals(WRAPPER + (actionId.equals("Gradle.ViewDependencyAnalyzer") ? "$View" : "$ProjectView")));
        AnAction base = official(manager, action);
        for (var selected : java.util.Arrays.asList(node, child, projectNode, null)) {
          for (String system : new String[]{"SBT", "GRADLE", "MAVEN", null}) {
            var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
                .add(LangDataKeys.MODULE, module).add(LangDataKeys.MODULE_CONTEXT_ARRAY, new com.intellij.openapi.module.Module[]{module});
            if (selected != null) context.add(ExternalSystemDataKeys.SELECTED_NODES, java.util.List.of(selected));
            if (system != null) context.add(ExternalSystemDataKeys.EXTERNAL_SYSTEM_ID, new ProjectSystemId(system));
            var data = context.build();
            var event = AnActionEvent.createEvent(action, data, null, "ExternalSystemView", ActionUiKind.POPUP, null);
            var originalEvent = AnActionEvent.createEvent(base, data, null, "ExternalSystemView", ActionUiKind.POPUP, null);
            base.update(originalEvent);
            action.update(event);
            String label = actionId + " " + (selected == null ? "IDE module" : selected.getClass().getSimpleName()) + " " + system;
            if (system == null || system.equals("SBT")) {
              if (actionId.equals("Gradle.ViewDependencyAnalyzer") && (selected == node || selected == child))
                check(label + " original exposes the erroneous entry", originalEvent.getPresentation().isEnabledAndVisible());
              check(label + " sbt popup hidden", !event.getPresentation().isVisible() && !event.getPresentation().isEnabled());
              action.actionPerformed(event);
              check(label + " invocation safely ignored", true);
            } else {
              check(label + " official visibility preserved", event.getPresentation().isVisible() == originalEvent.getPresentation().isVisible());
              check(label + " official enabled state preserved", event.getPresentation().isEnabled() == originalEvent.getPresentation().isEnabled());
            }
            if ("GRADLE".equals(system)) {
              var wrappedAction = (com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerAction) action;
              var originalAction = (com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerAction) base;
              check(label + " platform system hook preserved", java.util.Objects.equals(
                  wrappedAction.getSystemId(event), originalAction.getSystemId(originalEvent)));
              var calls = new java.util.ArrayList<Object>();
              var viewProxy = (com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerView) java.lang.reflect.Proxy.newProxyInstance(
                  getClass().getClassLoader(), new Class[]{com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerView.class},
                  (proxy, method, args) -> {
                    calls.add(java.util.List.of(method.getName(), args == null ? java.util.List.of() : java.util.Arrays.asList(args)));
                    return null;
                  });
              originalAction.setSelectedState(viewProxy, originalEvent);
              var expected = java.util.List.copyOf(calls);
              calls.clear();
              wrappedAction.setSelectedState(viewProxy, event);
              check(label + " platform selection hook preserved", calls.equals(expected));
            }
            check(label + " text and icon preserved", event.getPresentation().getText().equals(originalEvent.getPresentation().getText()) &&
                event.getPresentation().getIcon().equals(originalEvent.getPresentation().getIcon()));
          }
        }
        for (String groupId : actionId.equals("Gradle.ViewDependencyAnalyzer")
            ? new String[]{"Gradle.View.ProjectMenu", "Gradle.View.ModuleMenu", "Gradle.View.DependencyMenu"}
            : new String[]{"ProjectViewPopupMenu"}) {
          var group = (DefaultActionGroup) manager.getAction(groupId);
          check(groupId + " uses popup override", java.util.Arrays.asList(group.getChildren(manager)).contains(action));
        }
      }
    } finally {
      com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().forceCloseProject(project);
    }
  }

  @Override
  public void main(List<String> args) {
    Path output = Path.of(System.getProperty("analyzer.compat.output"));
    int exitCode = 0;
    try {
      ActionManager manager = ActionManager.getInstance();
      boolean withoutGradle = Boolean.getBoolean("analyzer.compat.withoutGradle");
      if (withoutGradle) {
        check("Gradle disabled: official action absent", manager.getAction(ACTION) == null);
        check("Gradle disabled: view popup action absent", manager.getAction("Gradle.ViewDependencyAnalyzer") == null);
        check("Gradle disabled: project popup action absent", manager.getAction("Gradle.ProjectViewDependencyAnalyzer") == null);
        check("Gradle disabled: sbt toolbar action remains registered", manager.getAction("Sbt.ToolbarDependencyAnalyzer") != null);
        check("Gradle disabled: sbt plugin loaded", PluginManagerCore.getPlugin(PluginId.getId("org.bitlap.sbtDependencyAnalyzer")) != null);
      } else {
        boolean previous = Registry.is("external.system.dependency.analyzer");
        Registry.get("external.system.dependency.analyzer").setValue(true);
        try {
          contexts(manager);
          sbtContexts(manager);
          popupContexts(manager);
          for (int cycle = 0; cycle < 2; cycle++) {
            PluginMainDescriptor descriptor = (PluginMainDescriptor) PluginManagerCore.getPlugin(PluginId.getId("org.bitlap.sbtDependencyAnalyzer"));
            check("dynamic unload " + cycle,
                DynamicPlugins.INSTANCE.unloadPlugin(descriptor, new DynamicPlugins.UnloadPluginOptions().withDisable(false)));
            AnAction restored = manager.getAction(ACTION);
            check("official action restored " + cycle,
                restored != null && restored.getClass().getName().startsWith("org.jetbrains.plugins.gradle."));
            AnActionEvent e = event("SBT");
            restored.update(e);
            check("unload restores official visibility " + cycle, e.getPresentation().isVisible());
            for (String popup : new String[]{"Gradle.ViewDependencyAnalyzer", "Gradle.ProjectViewDependencyAnalyzer"}) {
              check(popup + " official restored " + cycle, manager.getAction(popup).getClass().getName().startsWith("org.jetbrains.plugins.gradle."));
            }
            check("dynamic reload " + cycle, DynamicPlugins.INSTANCE.loadPlugin(descriptor));
            popupContexts(manager);
            contexts(manager);
          }
        } finally {
          Registry.get("external.system.dependency.analyzer").setValue(previous);
        }
      }
    } catch (Throwable error) {
      exitCode = 1;
      error.printStackTrace();
      results.append("FAIL ").append(error).append('\n');
    }
    try {
      Files.createDirectories(output.getParent());
      Files.writeString(output, results);
      System.out.print(results);
    } catch (Exception error) {
      error.printStackTrace();
      exitCode = 1;
    }
    System.exit(exitCode);
  }
}
