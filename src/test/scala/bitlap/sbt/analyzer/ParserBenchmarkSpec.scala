package bitlap.sbt.analyzer

import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.model.AnalyzerContext
import bitlap.sbt.analyzer.parsing.{ DotDependencyGraphParser, DotFileCache, LegacyDotDependencyGraphParser }

import org.scalatest.flatspec.AnyFlatSpec

import com.intellij.openapi.externalSystem.model.project.dependencies.DependencyScopeNode

/** Opt in with ANALYZER_BENCHMARK=true; timings exclude fixture generation and SDK startup. */
class ParserBenchmarkSpec extends AnyFlatSpec {

  "DOT parser" should "report reproducible cold parse and tree construction timings" in {
    if (sys.env.get("ANALYZER_BENCHMARK").contains("true")) {
      for (
        (shape, size) <- Seq("chain" -> 100, "chain" -> 300, "star" -> 1000, "star" -> 10000);
        legacy        <- Seq(true, false)
      ) {
        val file = Files.createTempFile("analyzer-benchmark-", ".dot")
        try {
          val nodes = (0 until size).map(i => s"\"bench:node$i:1\";").mkString("\n")
          val edges = (1 until size).map { i =>
            val parent = if (shape == "star") 0 else i - 1
            s"\"bench:node$parent:1\" -> \"bench:node$i:1\";"
          }.mkString("\n")
          Files.writeString(file, s"digraph dependencies {\n$nodes\n$edges\n}")
          val repetitions = if (size >= 10000 && legacy) 1 else 6
          val times       = (0 until repetitions).map { _ =>
            DotFileCache.invalidateCache(file.toString)
            val start  = System.nanoTime()
            val parser = if (legacy) new LegacyDotDependencyGraphParser() else new DotDependencyGraphParser()
            val root   = parser.buildDependencyTree(
              AnalyzerContext(file.toString, "node0", DependencyScopeEnum.Compile, "bench", isTest = true),
              new DependencyScopeNode(-1, "compile", "compile", "compile")
            )
            assert(!root.getDependencies.isEmpty)
            (System.nanoTime() - start) / 1000000.0
          }.drop(if (repetitions == 1) 0 else 1).sorted
          println(
            f"BENCHMARK legacy=$legacy shape=$shape nodes=$size samples=${times.size} medianMs=${times(times.size / 2)}%.3f minMs=${times.head}%.3f maxMs=${times.last}%.3f"
          )
        } finally {
          DotFileCache.invalidateCache(file.toString)
          Files.deleteIfExists(file)
        }
      }
    }
  }
}
