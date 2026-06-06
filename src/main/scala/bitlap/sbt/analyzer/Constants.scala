package bitlap.sbt.analyzer

import scala.concurrent.duration.*

object Constants:

  final val SEPARATOR: String      = "/"
  final val LINE_SEPARATOR: String = "\n"

  final val ARTIFACT_SEPARATOR: String = ":"
  final val EMPTY_STRING: String       = ""

  final val SINGLE_SBT_MODULE = "__SINGLE_MODULE__"
  final val ROOT_SBT_MODULE   = "__ROOT_MODULE__"

  final val PROJECT = "project"

  final val TIMEOUT = 10.minutes

  final val INTERVAL_TIMEOUT = 1010.milliseconds

  final val CACHE_TIMEOUT = 60 * 60 * 1000L

  final val CHANGE_NOTES_SEPARATOR = "<!-- @@ -->"

end Constants
