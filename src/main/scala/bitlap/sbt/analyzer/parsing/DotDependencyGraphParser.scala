package bitlap.sbt.analyzer.parsing

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.model.*
import bitlap.sbt.analyzer.util.DependencyUtils.*

import com.intellij.openapi.externalSystem.model.project.dependencies.*

object DotDependencyGraphParser {
  lazy val instance: DependencyGraphParser = new DotDependencyGraphParser
}

/** Builds a scope tree from indexed DOT edges using iterative expansion. Shared graph nodes may expand along multiple
 *  paths; cycles, depth over 512 and more than 100,000 expanded nodes fail explicitly. Matching self nodes are omitted
 *  from the displayed scope root.
 */
final class DotDependencyGraphParser extends DependencyGraphParser {
  override val dependencyGraphType: DependencyGraphType = DependencyGraphType.Dot

  override def buildDependencyTree(context: AnalyzerContext, root: DependencyScopeNode): DependencyScopeNode = {
    val data      = getDependencyRelations(context)
    val artifacts = data.dependencies.map(a => a.id -> a).toMap
    val adjacency = data.relations.groupMap(_.head)(identity)
    val self      = data.dependencies.filter(a => isSelfNode(node(a), context))
    if (data.relations.nonEmpty && self.isEmpty)
      throw new IllegalArgumentException(
        s"Cannot identify module '${context.currentModuleId}' in ${context.analysisFile}; check Organization and module names"
      )

    val starts = if (self.nonEmpty) {
      if (data.relations.isEmpty)
        data.dependencies.filterNot(a => self.exists(_.id == a.id)).map(a => Relation(-1, a.id, ""))
      else self.flatMap(a => adjacency.getOrElse(a.id, Nil))
    } else data.dependencies.map(a => Relation(-1, a.id, ""))

    // Post-order frames ensure child module conversion happens before its parent is attached.
    case class Frame(parent: DependencyNode, edges: List[Relation], path: Set[Int], depth: Int, finish: Boolean)
    val pending  = mutable.ArrayDeque(Frame(root, starts, self.map(_.id).toSet, 0, false))
    var expanded = 0
    while (pending.nonEmpty) {
      val frame = pending.removeLast()
      if (!context.isTest) com.intellij.openapi.progress.ProgressManager.checkCanceled()
      if (frame.finish) {
        val children = frame.parent.getDependencies.asScala.toList
        frame.parent.getDependencies.clear()
        appendChildrenAndFixProjectNodes(frame.parent, children, context)
      } else {
        if (frame.depth > 512)
          throw new IllegalArgumentException("Dependency path exceeds 512 levels; analysis stopped safely")
        pending.append(frame.copy(finish = true))
        val children = frame.edges.map { edge =>
          if (frame.path.contains(edge.tail))
            throw new IllegalArgumentException(s"Cyclic dependency involving ${artifacts(edge.tail)}")
          expanded += 1
          if (expanded > 100000)
            throw new IllegalArgumentException(
              "Dependency graph expands beyond 100,000 paths; disable unused scopes or analyze a smaller module"
            )
          val child = node(artifacts(edge.tail))
          child.setSelectionReason(Option(edge.label).getOrElse(""))
          frame.parent.getDependencies.add(child)
          Frame(child, adjacency.getOrElse(edge.tail, Nil), frame.path + edge.tail, frame.depth + 1, false)
        }
        children.reverseIterator.foreach(pending.append)
      }
    }
    root
  }

  private def node(a: ArtifactInfo): ArtifactDependencyNodeImpl = {
    val n = new ArtifactDependencyNodeImpl(a.id.toLong, a.group, a.artifact, a.version)
    n.setResolutionState(ResolutionState.RESOLVED)
    n
  }

  private[analyzer] def getDependencyRelations(context: AnalyzerContext): Dependencies = {
    val now = System.currentTimeMillis()
    context.dotCache.getCachedResult(context.analysisFile, now, context.cacheTimeoutMillis).flatten match {
      case Some(data) => data
      case None       =>
        val token  = context.dotCache.snapshot(context.analysisFile)
        val text   = java.nio.file.Files.readString(java.nio.file.Path.of(context.analysisFile))
        val result = try SbtDotReader.read(text)
        catch {
          case _: UnsupportedOperationException => readWithGraphviz(context)
        }
        if (context.cacheTimeoutMillis > 0) context.dotCache.cacheIfUnchanged(context.analysisFile, result, token, now)
        result
    }
  }

  private def readWithGraphviz(context: AnalyzerContext): Dependencies = {
    val graph = DotUtils.parseAsGraph(context)
    val nodes = graph
      .nodes()
      .asScala
      .toList
      .sortBy(_.name().value())
      .zipWithIndex
      .map { (n, id) =>
        val name  = n.name().value()
        val parts = name.split(":", -1)
        if (parts.length != 3 || parts.exists(_.isEmpty))
          throw new IllegalArgumentException(s"Invalid dependency coordinate: $name")
        name -> ArtifactInfo(id, parts(0), parts(1), parts(2))
      }
      .toMap
    val edges = graph
      .edges()
      .asScala
      .toList
      .map { link =>
        def endpoint(name: String): Int =
          nodes.getOrElse(name, throw new IllegalArgumentException(s"Unknown DOT endpoint: $name")).id
        Relation(
          endpoint(link.from().name().value()),
          endpoint(link.to().name().value()),
          Option(link.get("label")).fold("")(_.toString)
        )
      }
      .distinct
    val result = Dependencies(nodes.values.toList.sortBy(_.id), edges)

    result
  }

}
