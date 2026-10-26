package bitlap.sbt.analyzer

import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.model.AnalyzerContext
import bitlap.sbt.analyzer.parsing.{ DotDependencyGraphParser, DotFileCache, SbtDotReader }

import org.scalatest.funsuite.AnyFunSuite

import com.intellij.openapi.externalSystem.model.project.dependencies.{ DependencyNode, DependencyScopeNode }

/** Checks Graphviz fallback, generated dependency paths and cache invalidation after file deletion or replacement. */
class CoreFlowSpec extends AnyFunSuite {

  private def parse(text: String)(check: DependencyScopeNode => Unit): Unit = {
    val file = Files.createTempFile("analyzer-core-flow-", ".dot")
    try {
      Files.writeString(file, text)
      check(
        new DotDependencyGraphParser().buildDependencyTree(
          AnalyzerContext(
            file.toString,
            "root",
            DependencyScopeEnum.Compile,
            "g",
            isTest = true,
            dotCache = new DotFileCache()
          ),
          new DependencyScopeNode(-1, "compile", "compile", "compile")
        )
      )
    } finally Files.deleteIfExists(file)
  }

  test("unsupported DOT subgraphs fall back to Graphviz and retain transitive dependencies") {
    val text = """digraph dependencies { subgraph cluster_dependencies {
      |"g:root:1" -> "g:direct:1"; "g:direct:1" -> "g:transitive:2";
      |} }""".stripMargin
    intercept[UnsupportedOperationException](SbtDotReader.read(text))
    parse(text) { root =>
      assert(root.getDependencies.asScala.map(_.getDisplayName).toList == List("g:direct:1"))
      assert(root.getDependencies.get(0).getDependencies.get(0).getDisplayName == "g:transitive:2")
    }
  }

  test("generated acyclic graphs preserve every expected dependency path after parse and expansion") {
    for (seed <- 0 until 12) {
      val random = new scala.util.Random(seed)
      val edges  =
        (for (a <- 0 until 9; b <- a + 1 until 10 if b == a + 1 || random.nextInt(4) == 0) yield a -> b).toList
      def coordinate(i: Int): String       = if (i == 0) "g:root:1" else s"g:n$i:1"
      def paths(i: Int): Set[List[String]] = edges.collect { case (`i`, b) => b }.flatMap { b =>
        List(List(coordinate(b))) ++ paths(b).map(coordinate(b) :: _)
      }.toSet
      def actual(node: DependencyNode): Set[List[String]] = node.getDependencies.asScala.flatMap { child =>
        List(List(child.getDisplayName)) ++ actual(child).map(child.getDisplayName :: _)
      }.toSet
      val text =
        "digraph dependencies {" + edges.map((a, b) => s"\"${coordinate(a)}\" -> \"${coordinate(b)}\";").mkString + "}"
      parse(text)(root => assert(actual(root) == paths(0), s"Seed $seed lost or duplicated a path"))
    }
  }

  test("file deletion and file replacement prevent stale cache reuse even within TTL") {
    val file  = Files.createTempFile("analyzer-cache-file-", ".dot")
    val cache = new DotFileCache()
    try {
      Files.writeString(file, "digraph {\"g:root:1\" -> \"g:a:1\"}")
      val first = SbtDotReader.read(Files.readString(file))
      val token = cache.snapshot(file.toString)
      cache.cacheIfUnchanged(file.toString, first, token, 100)
      assert(cache.getCachedResult(file.toString, 101).nonEmpty)
      Files.delete(file)
      assert(cache.getCachedResult(file.toString, 102).isEmpty)
      Files.writeString(file, "digraph {\"g:root:1\" -> \"g:b:1\"}")
      cache.cacheIfUnchanged(file.toString, first, token, 103)
      assert(cache.getCachedResult(file.toString, 104).isEmpty)
    } finally Files.deleteIfExists(file)
  }
}
