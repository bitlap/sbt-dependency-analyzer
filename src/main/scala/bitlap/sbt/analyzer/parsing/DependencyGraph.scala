package bitlap.sbt.analyzer.parsing

import scala.collection.mutable

final class DependencyGraph(size: Int) {
  private val graph                 = Array.fill(size)(mutable.ListBuffer.empty[Int])
  def addEdge(v: Int, w: Int): Unit = { graph(v) += w }

  def dfs(v: Int): mutable.ListBuffer[Int] = {
    val seen   = mutable.HashSet.empty[Int]
    val result = mutable.ListBuffer.empty[Int]
    val stack  = mutable.ArrayDeque(v)
    while (stack.nonEmpty) {
      val current = stack.removeLast()
      if (seen.add(current)) { result += current; graph(current).reverseIterator.foreach(stack.append) }
    }
    result
  }
}
