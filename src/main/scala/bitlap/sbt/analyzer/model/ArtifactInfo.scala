package bitlap.sbt.analyzer.model

import bitlap.sbt.analyzer.Constants

/** A graph-local node ID and its resolved artifact coordinate. The artifact name retains any Scala or platform suffix
 *  emitted by sbt.
 */
final case class ArtifactInfo(id: Int, group: String, artifact: String, version: String) {

  override def toString: String = {
    s"$group${Constants.ARTIFACT_SEPARATOR}$artifact${Constants.ARTIFACT_SEPARATOR}$version"

  }
}
