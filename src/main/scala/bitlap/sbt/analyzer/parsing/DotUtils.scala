package bitlap.sbt.analyzer.parsing

import java.io.File

import bitlap.sbt.analyzer.model.AnalyzerContext

import guru.nidi.graphviz.attribute.validate.ValidatorEngine
import guru.nidi.graphviz.model.MutableGraph
import guru.nidi.graphviz.parse.Parser

object DotUtils {

  def parseAsGraph(context: AnalyzerContext): MutableGraph = {
    // A parser contains mutable parse state. Never share one between project executors.
    new Parser().forEngine(ValidatorEngine.DOT).notValidating().read(new File(context.analysisFile))
  }
}
