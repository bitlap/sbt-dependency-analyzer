package bitlap.sbt.analyzer

import bitlap.sbt.analyzer.util.DependencyUtils

import org.scalatest.flatspec.AnyFlatSpec

class SbtArtifactRegex extends AnyFlatSpec {

  "regex match" should "ok" in {

    val scala211 = "zio_2.11"
    val scala212 = "zio_2.12"
    val scala213 = "zio_2.13"
    val scala3   = "zio_3"

    val sjs0_213 = "distage-core_sjs0.6_2.13"
    val sjs0_212 = "distage-core_sjs0.6_2.12"
    val sjs0_211 = "distage-core_sjs0.6_2.11"
    val sjs0_3   = "distage-core_sjs0.6_3"
    val sjs1_211 = "distage-core_sjs1_2.11"
    val sjs1_212 = "distage-core_sjs1_2.12"
    val sjs1_213 = "distage-core_sjs1_2.13"
    val sjs1_3   = "distage-core_sjs1_3"

    val native0_213 = "zio_native0.5_2.13"
    val native0_212 = "zio_native0.5_2.12"
    val native0_211 = "zio_native0.5_2.11"
    val native0_3   = "zio_native0.5_3"

    val artifactPattern = DependencyUtils.SCALA_VERSION_PATTERN
    val res             = List(
      scala211,
      scala212,
      scala213,
      scala3,
      sjs0_213,
      sjs0_212,
      sjs0_211,
      sjs0_3,
      sjs1_211,
      sjs1_212,
      sjs1_213,
      sjs1_3,
      native0_213,
      native0_212,
      native0_211,
      native0_3
    ).map {
      case artifactPattern(name) => name
      case _                     => ""
    }
    assert(!res.exists(_.isEmpty))

  }

}
