package bitlap.sbt.analyzer;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerView;
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys;
import com.intellij.openapi.externalSystem.view.ExternalProjectsView;
import com.intellij.openapi.externalSystem.view.ExternalSystemNode;
import com.intellij.openapi.project.ProjectManager;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.BiConsumer;

/** Exercises the reported Scala selection path using the Scala runtime actually loaded by the IDE. */
public final class ScalaOptionCompatibilityChecks {
  public static void run(BiConsumer<String, Boolean> check) throws Exception {
    ActionManager manager = ActionManager.getInstance();
    AnAction viewAction = manager.getAction("Sbt.ViewDependencyAnalyzer");
    var selectedData = viewAction.getClass().getMethod("getSelectedData", AnActionEvent.class);
    check.accept("missing selection returns null without Scala linkage error",
        selectedData.invoke(viewAction, event(viewAction, null)) == null);
    check.accept("empty selection returns null without Scala linkage error",
        selectedData.invoke(viewAction, event(viewAction, List.of())) == null);

    ExternalProjectsView projects = (ExternalProjectsView) Proxy.newProxyInstance(
        ExternalProjectsView.class.getClassLoader(), new Class<?>[]{ExternalProjectsView.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getProject")) return ProjectManager.getInstance().getDefaultProject();
          throw new UnsupportedOperationException(method.getName());
        });
    ExternalSystemNode<?> first = new ExternalSystemNode<Object>(projects, null) {};
    ExternalSystemNode<?> second = new ExternalSystemNode<Object>(projects, null) {};
    check.accept("populated selection retains the first source node",
        selectedData.invoke(viewAction, event(viewAction, List.of(first, second))) == first);

    AnAction toolbar = manager.getAction("Sbt.ToolbarDependencyAnalyzer");
    DependencyAnalyzerView unusedView = (DependencyAnalyzerView) Proxy.newProxyInstance(
        DependencyAnalyzerView.class.getClassLoader(), new Class<?>[]{DependencyAnalyzerView.class},
        (proxy, method, args) -> { throw new AssertionError("Empty selection must not update the view"); });
    toolbar.getClass().getMethod("setSelectedState", DependencyAnalyzerView.class, AnActionEvent.class)
        .invoke(toolbar, unusedView, event(toolbar, List.of()));
    check.accept("toolbar selection transfer accepts an empty source selection", true);

    // Loading from the plugin classloader uses the same dependency resolution as the production action above.
    ClassLoader loader = PluginManagerCore.getPlugin(PluginId.getId("org.bitlap.sbtDependencyAnalyzer")).getPluginClassLoader();
    Class<?> utils = loader.loadClass("bitlap.sbt.analyzer.util.DependencyUtils$");
    Object instance = utils.getField("MODULE$").get(null);
    Object info = utils.getMethod("getArtifactInfoFromDisplayName", String.class).invoke(instance, "group:artifact:1.0");
    check.accept("coordinate parsing uses the IDE Scala Option runtime",
        (boolean) info.getClass().getMethod("nonEmpty").invoke(info));
  }

  // The platform SELECTED_NODES key uses a raw ExternalSystemNode element type.
  @SuppressWarnings("rawtypes")
  private static AnActionEvent event(AnAction action, List<ExternalSystemNode> nodes) {
    DataContext context = nodes == null ? DataContext.EMPTY_CONTEXT : SimpleDataContext.builder()
        .add(ExternalSystemDataKeys.SELECTED_NODES, nodes).build();
    return AnActionEvent.createEvent(action, context, null, "ExternalSystemView", ActionUiKind.TOOLBAR, null);
  }
}
