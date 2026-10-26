import sbt.Command

/** Build command aliases for formatting Scala/sbt sources and checking their formatting without rewriting files. */
object Commands {

  val FmtSbtCommand = Command.command("fmt")(state => "scalafmtSbt" :: "scalafmtAll" :: state)

  val FmtSbtCheckCommand =
    Command.command("check")(state => "scalafmtSbtCheck" :: "scalafmtCheckAll" :: state)

  val value = Seq(
    FmtSbtCommand,
    FmtSbtCheckCommand
  )

}
