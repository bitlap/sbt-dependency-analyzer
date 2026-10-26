package bitlap.sbt.analyzer

import java.nio.file.{ Files, Path }
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*

import org.scalatest.funsuite.AnyFunSuite

/** Checks that the packaged plugin contains the current compiled production classes without obsolete entries. */
class PackagingRegressionSpec extends AnyFunSuite {

  test("the production jar contains exactly the compiled classes, including new inner classes") {
    val classes = Option(System.getProperty("analyzer.classes.dir"))
      .map(Path.of(_))
      .getOrElse(Path.of(getClass.getProtectionDomain.getCodeSource.getLocation.toURI).getParent.resolve("classes"))
    val artifact = Path.of("target/plugin/SbtDependencyAnalyzer/lib/sbt-dependency-analyzer.jar")
    assert(Files.isDirectory(classes) && Files.isRegularFile(artifact))
    val stream = Files.walk(classes)
    val files  = try stream.iterator().asScala.filter(p => p.toString.endsWith(".class")).toList
    finally stream.close()
    val jar = new ZipFile(artifact.toFile)
    try {
      val actual   = jar.entries().asScala.map(_.getName).filter(_.endsWith(".class")).toSet
      val expected = files.map(p => classes.relativize(p).toString.replace('\\', '/')).toSet
      assert(actual == expected, s"Missing: ${expected -- actual}; stale: ${actual -- expected}")
      files.foreach { file =>
        val entry    = jar.getEntry(classes.relativize(file).toString.replace('\\', '/'))
        val input    = jar.getInputStream(entry)
        val contents = try input.readAllBytes()
        finally input.close()
        assert(java.util.Arrays.equals(contents, Files.readAllBytes(file)), s"Stale class bytes: $file")
      }
    } finally jar.close()
  }
}
