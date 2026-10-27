package bitlap.sbt.analyzer.model

/** A directed dependency edge from head to tail; label carries sbt selection or eviction information. */
final case class Relation(head: Int, tail: Int, label: String)
