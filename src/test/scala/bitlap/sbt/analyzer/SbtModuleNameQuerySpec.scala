package bitlap.sbt.analyzer

import scala.collection.mutable.ListBuffer

import bitlap.sbt.analyzer.util.{ SbtModuleNameQuery, SbtProjectReference }

import org.scalatest.funsuite.AnyFunSuite

/** Exercises real sbt heading forms and records which shell queries are actually necessary. */
class SbtModuleNameQuerySpec extends AnyFunSuite {
  private val root                              = SbtProjectReference("file:/batch-main/", "root")
  private def local(id: String)                 = SbtProjectReference(root.buildUri.toString, id)
  private val other                             = SbtProjectReference("file:/batch-external%20build/", "root")
  private val coreJVM                           = local("coreJVM")
  private val coreJS                            = local("coreJS")
  private def command(ref: SbtProjectReference) = s"show ${ref.selector} / moduleName"

  test(
    "aggregated output retains every project even when publication names are equal and the queried value is not last"
  ) {
    val output = """[info] moduleName
      |[info]   root-published
      |[info] coreJVM / moduleName
      |[info]   circe-core
      |[info] coreJS / moduleName
      |[info]   circe-core
      |""".stripMargin
    val values = SbtModuleNameQuery.parse(output, root, Some(root))
    assert(
      values == Map(
        root.selector    -> "root-published",
        coreJVM.selector -> "circe-core",
        coreJS.selector  -> "circe-core"
      )
    )
  }

  test("local headings belong to the current build and ProjectRef headings preserve external same-ID projects") {
    val output = s"""[info] moduleName
      |[info]   local-root
      |[info] ProjectRef(uri("file:/batch-external%20build/"), "root") / moduleName
      |[info]   external-root
      |[info] ProjectRef(uri("file:///batch-external%20build/"), "root") / moduleName
      |[info]   external-root
      |[info] ${coreJS.selector} / moduleName
      |[info]   js-published
      |""".stripMargin
    val values = SbtModuleNameQuery.parse(output, other, Some(root))
    assert(
      values == Map(root.selector -> "local-root", other.selector -> "external-root", coreJS.selector -> "js-published")
    )
  }

  test("unscoped moduleName headings identify the current project even when it is not a build root") {
    val values = SbtModuleNameQuery.parse("[info] moduleName\n[info] browser", root, Some(coreJS))
    assert(values == Map(coreJS.selector -> "browser"))
  }

  test("single unlabelled value belongs only to its explicit query and ambiguous output is rejected") {
    assert(
      SbtModuleNameQuery.parse("[info] external-root", other, Some(root)) == Map(other.selector -> "external-root")
    )
    assert(SbtModuleNameQuery.parse("[info] one\n[info] two", other, Some(root)).isEmpty)
    assert(SbtModuleNameQuery.parse("[info] unknown / moduleName\n[info] value", root, None).isEmpty)
    assert(SbtModuleNameQuery.parse("[info] moduleName\n[info] value", root, None).isEmpty)
  }

  test("unknown headings never assign their values to the previous project or use the last line as a fallback") {
    val output = "[info] coreJS / moduleName\n[info] browser\n[info] ThisBuild / moduleName\n[info] wrong"
    assert(SbtModuleNameQuery.parse(output, root, Some(root)) == Map(coreJS.selector -> "browser"))
    assert(SbtModuleNameQuery.parse("[warn] warning\n[info] coreJS / moduleName\n", root, Some(root)).isEmpty)
    intercept[IllegalArgumentException] {
      SbtModuleNameQuery.parse(
        "[info] coreJS / moduleName\n[info] a\n[info] coreJS / moduleName\n[info] b",
        root,
        Some(root)
      )
    }
  }

  test("one root query fills platform projects and only an unaggregated module needs a supplemental query") {
    val detached = local("detached-tools")
    val calls    = ListBuffer.empty[String]
    val all      = Seq(coreJVM, detached, coreJS, root, coreJVM)
    val values   = SbtModuleNameQuery.collect(all, Some(root), Seq(root), Map.empty) { cmd =>
      calls += cmd
      if (cmd == command(root))
        "[info] coreJS / moduleName\n[info] core\n[info] moduleName\n[info] root\n[info] coreJVM / moduleName\n[info] core"
      else { assert(cmd == command(detached)); "[info] detached" }
    }
    assert(calls.toList == List(command(root), command(detached)))
    assert(
      values == Map(
        root.selector     -> "root",
        coreJS.selector   -> "core",
        coreJVM.selector  -> "core",
        detached.selector -> "detached"
      )
    )
  }

  test("aggregated external roots fill the cache by URI and ID without an extra external query") {
    val calls  = ListBuffer.empty[String]
    val values = SbtModuleNameQuery.collect(Seq(other, root), Some(root), Seq(other, root), Map.empty) { cmd =>
      calls += cmd
      assert(cmd == command(root))
      "[info] moduleName\n[info] local\n[info] ProjectRef(uri(\"file:/batch-external%20build/\"), \"root\") / moduleName\n[info] external"
    }
    assert(calls.size == 1)
    assert(values == Map(root.selector -> "local", other.selector -> "external"))
  }

  test("partial aggregate results are reused and each remaining identity is queried at most once") {
    val calls  = ListBuffer.empty[String]
    val values = SbtModuleNameQuery.collect(Seq(root, coreJVM, coreJS), Some(root), Seq(root), Map.empty) { cmd =>
      calls += cmd
      if (cmd == command(root)) "[info] moduleName\n[info] root\n[info] coreJS / moduleName\n[info] browser"
      else { assert(cmd == command(coreJVM)); "[info] server" }
    }
    assert(calls.toList == List(command(root), command(coreJVM)))
    assert(values(coreJS.selector) == "browser" && values(coreJVM.selector) == "server")
  }

  test("cached identities need no shell calls and newly imported projects alone are queried") {
    val cached = Map(root.selector -> "root", coreJS.selector -> "browser")
    assert(
      SbtModuleNameQuery.collect(Seq(root, coreJS), Some(root), Seq(root), cached)(_ =>
        fail("Unexpected query")
      ) == cached
    )
    val calls  = ListBuffer.empty[String]
    val values = SbtModuleNameQuery.collect(Seq(root, coreJS, coreJVM), Some(root), Seq(root), cached) { cmd =>
      calls += cmd; "[info] server"
    }
    assert(calls.toList == List(command(coreJVM)) && values(coreJVM.selector) == "server")
  }

  test("missing results and shell failures do not guess names or mutate the caller cache") {
    val cached = Map(coreJS.selector -> "browser")
    intercept[IllegalArgumentException] {
      SbtModuleNameQuery.collect(Seq(root, coreJS), Some(root), Seq(root), cached)(_ =>
        "[info] coreJS / moduleName\n[info] browser"
      )
    }
    val failure = new IllegalStateException("shell failed")
    assert(intercept[IllegalStateException] {
      SbtModuleNameQuery.collect(Seq(root), Some(root), Seq(root), cached)(_ => throw failure)
    } eq failure)
    assert(cached == Map(coreJS.selector -> "browser"))
  }

  test("recorded 48-query aggregate trace reuses all results without losing platform identity") {
    val source     = scala.io.Source.fromResource("module-name-aggregate.txt")
    val transcript = try source.mkString
    finally source.close()
    val sections = transcript
      .split("\\[IJ\\] ")
      .toSeq
      .drop(1)
      .map { section =>
        val lines = section.linesIterator.toList
        lines.head -> lines.tail.mkString("\n")
      }
      .toMap
    val inventory = SbtProjectReference.inventory(sections("projects"))
    val refs      = sections.keys
      .filter(_.endsWith(" / moduleName"))
      .flatMap(key => SbtProjectReference.fromSelector(key.stripSuffix(" / moduleName")))
      .toSeq
    assert(refs.size == 48)
    val current = inventory.current.get
    val calls   = ListBuffer.empty[String]
    val names   = SbtModuleNameQuery.collect(refs, Some(current), Seq(current), Map.empty) { command =>
      calls += command
      sections(command.stripPrefix("show "))
    }
    assert(names.keySet == refs.map(_.selector).toSet)
    assert(calls.size < 10, calls.mkString("\n"))
    assert(names(SbtProjectReference(current.buildUri.toString, "coreJVM").selector) == "circe-core")
    assert(names(SbtProjectReference(current.buildUri.toString, "coreJS").selector) == "circe-core")
    assert(names(SbtProjectReference(current.buildUri.toString, "coreNative").selector) == "circe-core")
    assert(names(current.selector) == "root")
    info(s"moduleName commands: 48 -> ${calls.size}, retained identities: ${names.size}")
  }

  test("inventory distinguishes the current project from an external root and preserves build identity") {
    val inventory = SbtProjectReference.inventory("""[info] In file:/batch-main/
      |[info]    root
      |[info]  * coreJS
      |[info] In file:/batch-external%20build/
      |[info]  (*)root
      |""".stripMargin)
    assert(inventory.current.contains(coreJS))
    assert(inventory.projects == Set(root, coreJS, other))
  }
}
