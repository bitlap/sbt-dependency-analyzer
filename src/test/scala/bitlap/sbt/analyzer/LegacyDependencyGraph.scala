package bitlap.sbt.analyzer.parsing;

import scala.collection.mutable.ListBuffer

final class LegacyDependencyGraph(size: Int) {

  private val graph = Array.fill(size)(ListBuffer[Int]())

  def addEdge(v: Int, w: Int): Unit = {
    graph(v) += w
  }

  def dfs(v: Int): ListBuffer[Int] = {
    val visited = Array.fill(size + 1)(false)
    val res     = ListBuffer[Int]()
    walk(v, visited, res)
    res
  }

  private def walk(v: Int, visited: Array[Boolean], res: ListBuffer[Int]): Unit = {
    visited(v) = true

    res += v

    graph(v).foreach(n => {
      if (!visited(n))
        walk(n, visited, res)
    })
  }
}
