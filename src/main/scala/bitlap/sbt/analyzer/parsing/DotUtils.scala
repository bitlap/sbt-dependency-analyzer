package bitlap
package sbt
package analyzer
package parsing

import java.io.File
import java.nio.file.Path

import scala.util.Try
import scala.util.control.Breaks
import scala.util.control.Breaks.breakable

import org.jetbrains.plugins.scala.extensions.inReadAction
import org.jetbrains.plugins.scala.project.VirtualFileExt

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VfsUtil

import analyzer.util.Notifications
import guru.nidi.graphviz.attribute.validate.ValidatorEngine
import guru.nidi.graphviz.model.MutableGraph
import guru.nidi.graphviz.parse.Parser
import model.AnalyzerContext

object DotUtils {

  private val LOG = Logger.getInstance(getClass)

  private lazy val parser = (new Parser).forEngine(ValidatorEngine.DOT).notValidating()

  private def parseAsGraphTestOnly(file: String): MutableGraph = {
    Try(parser.read(new File(file))).getOrElse(null)
  }

  def parseAsGraph(context: AnalyzerContext): MutableGraph = {
    if (context.isTest) return parseAsGraphTestOnly(context.analysisFile)
    val file = context.analysisFile
    try {
      var vfsFile = VfsUtil.findFile(Path.of(file), true)
      val start   = System.currentTimeMillis()
      // TODO Tried all kinds of refreshes but nothing works.
      breakable {
        while (vfsFile == null) {
          vfsFile = VfsUtil.findFile(Path.of(file), true)
          if (vfsFile != null) {
            VfsUtil.markDirtyAndRefresh(true, true, true, vfsFile)
            Breaks.break()
          } else {
            if (System.currentTimeMillis() - start > Constants.TIMEOUT.toMillis) {
              Notifications.notifyParseFileError(file, "The file has expired")
              Breaks.break()
            }
          }
        }
      }
      inReadAction {
        if (vfsFile != null) {
          val f = vfsFile.findDocument.map(_.getImmutableCharSequence.toString).orNull
          parser.read(f)
        } else {
          Notifications.notifyParseFileError(file, "The file was not found")
          Breaks.break()
        }
      }
    } catch {
      case ignore: Throwable =>
        LOG.error(ignore)
        Notifications.notifyParseFileError(file, "The file parsing failed")
        null
    }
  }
}
