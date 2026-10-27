package bitlap.sbt.analyzer.parsing

import scala.collection.mutable

import bitlap.sbt.analyzer.model.*

/** Reads the coordinate nodes and labeled edges emitted by sbt dependencyDot in a single token pass. Layout attributes
 *  are ignored. Subgraphs and ports request the Graphviz fallback; malformed coordinates fail parsing.
 */
private[analyzer] object SbtDotReader {

  def read(text: String): Dependencies = {
    val tokens                      = new Tokens(text)
    val nodes                       = mutable.LinkedHashMap.empty[String, ArtifactInfo]
    val edges                       = mutable.ListBuffer.empty[Relation]
    def artifact(name: String): Int = nodes
      .getOrElseUpdate(
        name, {
          val parts = name.split(":", -1)
          require(parts.length == 3 && !parts.exists(_.isEmpty), s"Invalid dependency coordinate: $name")
          ArtifactInfo(nodes.size, parts(0), parts(1), parts(2))
        }
      )
      .id
    def attributes(): String = {
      var label = ""
      while (tokens.peek.contains("[")) {
        tokens.next()
        while (!tokens.peek.contains("]")) {
          val key = tokens.next()
          if (tokens.peek.contains("=")) {
            tokens.next()
            val value = tokens.next()
            if (key == "label") label = value
          }
        }
        tokens.next()
      }
      label
    }
    if (tokens.peek.contains("strict")) tokens.next()
    require(tokens.next() == "digraph", "Expected a directed DOT graph")
    if (!tokens.peek.contains("{")) tokens.next()
    require(tokens.next() == "{", "Expected DOT graph body")
    while (!tokens.peek.contains("}")) {
      val name = tokens.next()
      name match {
        case ";" | ","        => ()
        case "subgraph" | "{" => throw new UnsupportedOperationException("DOT subgraphs require Graphviz")
        case "graph" | "node" | "edge" if tokens.peek.contains("[") => attributes()
        case _ if tokens.peek.contains("=")                         => tokens.next(); tokens.next()
        case _                                                      =>
          val from = artifact(name)
          if (tokens.peek.contains(":")) throw new UnsupportedOperationException("DOT ports require Graphviz")
          val chain    = mutable.ListBuffer.empty[(Int, Int)]
          var previous = from
          while (tokens.peek.contains("->")) {
            tokens.next()
            val next = artifact(tokens.next())
            chain += previous -> next
            previous = next
          }
          val label = attributes()
          chain.foreach { (head, tail) => edges += Relation(head, tail, label) }
      }
    }
    tokens.next()
    if (tokens.peek.contains(";")) tokens.next()
    require(tokens.peek.isEmpty, "Unexpected content after DOT graph")
    Dependencies(nodes.values.toList, edges.toList.distinct)
  }

  private final class Tokens(text: String) {
    private var position                 = 0
    private var buffered: Option[String] = None
    def peek: Option[String]             = { if (buffered.isEmpty) buffered = scan(); buffered }

    def next(): String = {
      val token = peek.getOrElse(throw new IllegalArgumentException("Unexpected end of DOT file"))
      buffered = None
      token
    }

    private def scan(): Option[String] = {
      var skipping = true
      while (skipping && position < text.length) {
        if (text(position).isWhitespace) position += 1
        else if (text.startsWith("//", position) || text(position) == '#') {
          while (position < text.length && text(position) != '\n') position += 1
        } else if (text.startsWith("/*", position)) {
          val end = text.indexOf("*/", position + 2)
          require(end >= 0, "Unclosed DOT comment")
          position = end + 2
        } else skipping = false
      }
      if (position == text.length) return None
      val start   = position
      val current = text(position)
      position += 1
      if (current == '"') {
        val value  = new StringBuilder()
        var closed = false
        while (position < text.length && !closed) {
          val ch = text(position); position += 1
          if (ch == '"') closed = true
          else if (ch == '\\') {
            require(position < text.length, "Unclosed DOT escape")
            val escaped = text(position); position += 1
            escaped match {
              case '\n'       => ()
              case '"' | '\\' => value.append(escaped)
              case _          => value.append('\\').append(escaped)
            }
          } else value.append(ch)
        }
        require(closed, "Unclosed DOT string")
        Some(value.toString)
      } else if (current == '<') {
        var depth = 1
        while (position < text.length && depth > 0) {
          if (text(position) == '<') depth += 1
          else if (text(position) == '>') depth -= 1
          position += 1
        }
        require(depth == 0, "Unclosed DOT HTML attribute")
        Some(text.substring(start, position))
      } else if (current == '-' && position < text.length && text(position) == '>') {
        position += 1; Some("->")
      } else if ("{}[]=;,:".contains(current)) Some(current.toString)
      else {
        while (
          position < text.length && !text(position).isWhitespace && !"{}[]=;,:\"<>".contains(text(position)) && !text
            .startsWith("->", position)
        ) position += 1
        Some(text.substring(start, position))
      }
    }
  }
}
