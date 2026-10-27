package bitlap.sbt.analyzer.model

/** Parsed graph nodes and directed relations; relation endpoints refer to ArtifactInfo IDs. */
final case class Dependencies(dependencies: List[ArtifactInfo], relations: List[Relation])
