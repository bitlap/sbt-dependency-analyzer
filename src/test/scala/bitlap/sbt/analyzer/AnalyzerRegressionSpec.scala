package bitlap.sbt.analyzer

import java.nio.file.{ Files, Path }
import java.nio.file.attribute.FileTime

import scala.jdk.CollectionConverters.*

import bitlap.sbt.analyzer.action.DeclarationMatcher
import bitlap.sbt.analyzer.model.*
import bitlap.sbt.analyzer.parsing.*
import bitlap.sbt.analyzer.task.RefreshSnapshotsTask

import org.scalatest.funsuite.AnyFunSuite

import com.intellij.openapi.externalSystem.model.project.dependencies.*

class AnalyzerRegressionSpec extends AnyFunSuite {

  private def fixture(text: String)(body: Path => Unit): Unit = {
    val file = Files.createTempFile("analyzer-regression-", ".dot")
    try { Files.writeString(file, s"digraph dependencies {$text}"); body(file) }
    finally { DotFileCache.invalidateCache(file.toString); Files.deleteIfExists(file) }
  }

  private def parse(
    file: Path,
    module: String = "root",
    cache: DotFileCache = new DotFileCache(),
    ttl: Long = 3600000
  ): DependencyScopeNode =
    new DotDependencyGraphParser().buildDependencyTree(
      AnalyzerContext(
        file.toString,
        module,
        DependencyScopeEnum.Compile,
        "g",
        isTest = true,
        cacheTimeoutMillis = ttl,
        dotCache = cache
      ),
      new DependencyScopeNode(-1, "compile", "compile", "compile")
    )

  private def coordinates(node: DependencyNode): List[String] =
    node.getDependencies.asScala.toList.map(_.getDisplayName)

  test("direct and transitive edges retain their exact parent") {
    fixture("\"g:root:1\" -> \"g:a:1\"; \"g:a:1\" -> \"g:b:1\";") { file =>
      val root = parse(file)
      assert(coordinates(root) == List("g:a:1"))
      assert(coordinates(root.getDependencies.get(0)) == List("g:b:1"))
    }
  }

  test("shared diamond dependencies remain visible on both paths") {
    fixture("\"g:root:1\" -> \"g:a:1\"; \"g:root:1\" -> \"g:b:1\"; \"g:a:1\" -> \"g:c:1\"; \"g:b:1\" -> \"g:c:1\";") {
      file =>
        val root = parse(file)
        assert(root.getDependencies.size == 2)
        assert(root.getDependencies.asScala.forall(n => coordinates(n) == List("g:c:1")))
    }
  }

  test("cycles stop with an actionable error") {
    fixture("\"g:root:1\" -> \"g:a:1\"; \"g:a:1\" -> \"g:root:1\";") { file =>
      assert(intercept[IllegalArgumentException](parse(file)).getMessage.contains("Cyclic"))
    }
  }

  test("missing module root is not reported as an empty successful analysis") {
    fixture("\"g:a:1\" -> \"g:b:1\";") { file =>
      assert(intercept[IllegalArgumentException](parse(file)).getMessage.contains("Cannot identify"))
    }
  }

  test("malformed coordinates cannot silently create edges to node zero") {
    fixture("\"g:root:1\" -> \"invalid\";") { file => intercept[IllegalArgumentException](parse(file)) }
  }
  test("empty graph is a valid empty result") { fixture("")(file => assert(parse(file).getDependencies.isEmpty)) }

  test("isolated artifacts without edges are retained") {
    fixture("\"g:root:1\"; \"g:a:1\";") { file => assert(coordinates(parse(file)) == List("g:a:1")) }
  }

  test("eviction labels survive tree expansion") {
    fixture("\"g:root:1\" -> \"g:a:1\" [label=\"Evicted By\"];") { file =>
      assert(parse(file).getDependencies.get(0).getSelectionReason == "Evicted By")
    }
  }

  test("node IDs are local, compact and stable across repeated cold parses") {
    fixture("\"g:root:1\" -> \"g:a:1\";") { file =>
      val ids = (0 until 20).map(_ => parse(file, ttl = 0).getDependencies.get(0).getId)
      assert(ids.distinct.size == 1 && ids.head < 2)
    }
  }

  test("regenerated DOT file immediately invalidates its parsed result") {
    fixture("\"g:root:1\" -> \"g:a:1\";") { file =>
      val cache = new DotFileCache()
      assert(coordinates(parse(file, cache = cache)) == List("g:a:1"))
      val modified = Files.getLastModifiedTime(file).toMillis
      Files.writeString(file, "digraph dependencies {\"g:root:1\" -> \"g:b:1\";}")
      Files.setLastModifiedTime(file, FileTime.fromMillis(modified + 1000))
      assert(coordinates(parse(file, cache = cache)) == List("g:b:1"))
    }
  }

  test("zero timeout disables parsed caching") {
    fixture("\"g:root:1\" -> \"g:a:1\";") { file =>
      val cache = new DotFileCache()
      parse(file, cache = cache, ttl = 0)
      assert(cache.size == 0)
    }
  }

  test("cache TTL respects caller-provided seconds converted to milliseconds") {
    fixture("") { file =>
      val cache = new DotFileCache()
      cache.cacheResult(file.toString, Some(Dependencies(Nil, Nil)), 0, 1000)
      assert(cache.getCachedResult(file.toString, 1999, 1000).nonEmpty)
      assert(cache.getCachedResult(file.toString, 2000, 1000).isEmpty)
    }
  }

  test("failed parse results are never cached") {
    fixture("") { file =>
      val cache = new DotFileCache()
      cache.cacheResult(file.toString, None, 0, System.currentTimeMillis())
      assert(cache.size == 0)
    }
  }

  test("project caches are independent and explicitly clearable") {
    fixture("") { file =>
      val first = new DotFileCache(); val second = new DotFileCache()
      first.cacheResult(file.toString, Some(Dependencies(Nil, Nil)), 0, System.currentTimeMillis())
      assert(first.size == 1 && second.size == 0)
      first.clearAllCache()
      assert(first.size == 0)
    }
  }

  test("cache is bounded") {
    val cache = new DotFileCache(2)
    val files = (0 until 3).map(_ => Files.createTempFile("cache-bound-", ".dot"))
    try {
      files.foreach(f => cache.cacheResult(f.toString, Some(Dependencies(Nil, Nil)), 0, System.currentTimeMillis()))
      assert(cache.size == 2)
      assert(cache.getCachedResult(files.head.toString, System.currentTimeMillis()).isEmpty)
    } finally files.foreach(Files.deleteIfExists)
  }

  test("deep paths fail within a resource budget rather than overflowing the stack") {
    val edges = (0 until 600).map(i => s"\"g:n$i:1\" -> \"g:n${i + 1}:1\";").mkString
    fixture(edges) { file => assert(intercept[IllegalArgumentException](parse(file, "n0")).getMessage.contains("512")) }
  }

  test("declaration matching distinguishes scopes and versions") {
    assert(DeclarationMatcher.matches("g", "a_3", "1", "Test", "g", "a_3", "1", "test"))
    assert(!DeclarationMatcher.matches("g", "a_3", "1", "Test", "g", "a_3", "1", "compile"))
    assert(!DeclarationMatcher.matches("g", "a_3", "1", "Test", "g", "a_3", "2", "test"))
    assert(!DeclarationMatcher.matches("other", "a_3", "1", "Test", "g", "a_3", "1", "test"))
  }

  test("default declarations belong to Compile and mapped scopes are normalized") {
    assert(DeclarationMatcher.matches("g", "a", "1", "", "g", "a", "1", "compile"))
    assert(DeclarationMatcher.matches("g", "a", "1", "compile;test->test", "g", "a", "1", "test"))
  }

  test("cache settings reject negative, fractional, overflowing and blank input") {
    Seq("-1", "1.5", "", "abc", "2147483648").foreach(s => assert(SettingsValidation.cacheTimeout(s).isEmpty))
    assert(SettingsValidation.cacheTimeout(" 0 ").contains(0))
    assert(SettingsValidation.cacheTimeout("3600").contains(3600))
  }

  test("session settings parser retains user setting indices for targeted cleanup") {
    val settings = RefreshSnapshotsTask.sessionSettings(
      "[info] Session settings:\n  1. name := \"user\"\n  2. core / update / skip := false\n[info] finished"
    )
    assert(settings == Map(1 -> "name := \"user\"", 2 -> "core / update / skip := false"))
  }

  test("fast reader matches the real sbt fixture's nodes and labeled edges") {
    val text = Files.readString(Path.of(getClass.getClassLoader.getResource("test.dot").toURI))
    val fast = SbtDotReader.read(text)
    assert(fast.dependencies.size == 69 && fast.relations.size == 146)
    val byId    = fast.dependencies.map(a => a.id -> a.toString).toMap
    val actual  = fast.relations.map(r => (byId(r.head), byId(r.tail), r.label)).toSet
    val context = AnalyzerContext(
      getClass.getClassLoader.getResource("test.dot").getFile,
      "root",
      DependencyScopeEnum.Compile,
      "g",
      isTest = true
    )
    val graph    = DotUtils.parseAsGraph(context)
    val expected = graph
      .edges()
      .asScala
      .map(e => (e.from().name().value(), e.to().name().value(), Option(e.get("label")).fold("")(_.toString)))
      .toSet
    assert(actual == expected)
  }

  test("fast reader supports comments, edge chains, attributes and escaped strings") {
    val data = SbtDotReader.read(
      """strict digraph "x" { /* comment */ graph[rankdir="LR"]; "g:root:1" -> "g:a:1" -> "g:b:1" [label="Evicted By"]; // comment
      |}""".stripMargin
    )
    assert(data.dependencies.size == 3)
    assert(data.relations.size == 2 && data.relations.forall(_.label == "Evicted By"))
  }

  test("fast reader rejects incomplete files instead of caching partial results") {
    Seq("digraph {", "digraph { \"g:root:1\" ->", "digraph {} garbage").foreach { text =>
      intercept[IllegalArgumentException](SbtDotReader.read(text))
    }
  }

  test("an invalidated in-flight parse cannot repopulate the cache") {
    fixture("") { file =>
      val cache = new DotFileCache()
      val token = cache.snapshot(file.toString)
      cache.invalidateCache(file.toString)
      cache.cacheIfUnchanged(file.toString, Dependencies(Nil, Nil), token, System.currentTimeMillis())
      assert(cache.size == 0)
    }
  }

}
