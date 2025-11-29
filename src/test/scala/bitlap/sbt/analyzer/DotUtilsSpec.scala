package bitlap.sbt.analyzer

import java.util

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.model.*
import bitlap.sbt.analyzer.parsing.DotUtils
import bitlap.sbt.analyzer.util.DependencyUtils

import org.scalatest.flatspec.AnyFlatSpec

import guru.nidi.graphviz.model.*

class DotUtilsSpec extends AnyFlatSpec {

  "parse file as MutableNode" should "ok" in {
    val start = System.currentTimeMillis()
    val file  = getClass.getClassLoader.getResource("test.dot").getFile
    val ctx   =
      AnalyzerContext(
        file,
        "star-authority-protocol",
        DependencyScopeEnum.Compile,
        "fc.xuanwu.star",
        moduleNamePathsCache = Map.empty,
        moduleIdArtifactIdsCache = Map.empty,
        isTest = true
      )

    val mutableGraph: MutableGraph               = DotUtils.parseAsGraph(ctx)
    val graphNodes: util.Collection[MutableNode] = mutableGraph.nodes()
    val links: util.Collection[Link]             = mutableGraph.edges()

    val nodes = graphNodes.asScala.map { graphNode =>
      graphNode.name().value() -> DependencyUtils.getArtifactInfoFromDisplayName(graphNode.name().value())
    }.collect { case (name, Some(value)) =>
      name -> value
    }.toMap
    val idMapping: Map[String, Int] = nodes.map(kv => kv._2.toString -> kv._2.id)

    val edges = links.asScala.map { l =>
      val label = l.get("label").asInstanceOf[String]
      Relation(
        idMapping.getOrElse(l.from().name().value(), 0),
        idMapping.getOrElse(l.to().name().value(), 0),
        label
      )
    }

    println(s"parse dot cost:${System.currentTimeMillis() - start}ms")
    assert(nodes.size == 69)
    assert(edges.size == 146)

  }

}
