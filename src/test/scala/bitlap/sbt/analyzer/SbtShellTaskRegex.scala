package bitlap.sbt.analyzer

import bitlap.sbt.analyzer.task.SbtShellOutputAnalysisTask

import org.scalatest.flatspec.AnyFlatSpec

/** Checks compatibility parsing of sbt info lines and module-name headings. */
class SbtShellTaskRegex extends AnyFlatSpec {

  "regex match" should "ok" in {
    "[info] \torg.bitlap" match
      case SbtShellOutputAnalysisTask.SHELL_OUTPUT_RESULT_REGEX(_, _, org) =>
        assert(org.trim == "org.bitlap")
      case _ => assert(false)

    "[info] rolls-csv / moduleName" match
      case SbtShellOutputAnalysisTask.MODULE_NAME_INPUT_REGEX(_, _, moduleName, _, _) =>
        assert(moduleName.trim == "rolls-csv")
      case _ => assert(false)

    "[info] moduleName" match
      case SbtShellOutputAnalysisTask.ROOT_MODULE_NAME_INPUT_REGEX(_, _) =>
        assert(true)
      case _ => assert(false)
  }

}
