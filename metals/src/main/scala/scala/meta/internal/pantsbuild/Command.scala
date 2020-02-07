package scala.meta.internal.pantsbuild

import java.nio.file.Path
import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import java.nio.file.Paths
import scala.meta.internal.metals.BuildInfo

sealed abstract class Command {
  def isHelp = this == Help
}
case object Help extends Command
case object ListProjects extends Command
case class Refresh(
    name: String
) extends Command
case object Edit extends Command
case object Open extends Command

/**
 * The command-line argument parser for BloopPants.
 */
case class Create(
    isCache: Boolean = false,
    isRegenerate: Boolean = false,
    isIntelliJ: Boolean = false,
    isVscode: Boolean = false,
    isLaunchIntelliJ: Boolean = false,
    isSources: Boolean = true,
    isMergeTargetsInSameDirectory: Boolean = true,
    maxFileCount: Int = 5000,
    projectName: Option[String] = None,
    workspace: Path = PathIO.workingDirectory.toNIO,
    out: Path = PathIO.workingDirectory.toNIO,
    targets: List[String] = Nil,
    token: CancelToken = EmptyCancelToken,
    onFilemap: Filemap => Unit = _ => Unit
) extends Command {
  def pants: AbsolutePath = AbsolutePath(workspace.resolve("pants"))
  def isWorkspaceAndOutputSameDirectory: Boolean =
    workspace == out
  def command: String = Option(System.getProperty("sun.java.command")) match {
    case Some(path) =>
      Try(Paths.get(path.split(" ").head).getFileName().toString())
        .getOrElse("pants-bloop")
    case _ => "pants-bloop"
  }
  def helpMessage: String =
    s"""$command ${BuildInfo.metalsVersion}
       |$command [option ..] <target ..>
       |
       |
       |Command-line tool to export a Pants build into Bloop JSON config files.
       |The <target ..> argument is a list of Pants targets to export,
       |for example "$command my-project:: another-project::".
       |
       |  --workspace <dir>
       |    The directory containing the pants build, defaults to the working directory.
       |  --out <dir>
       |    The directory containing the generated Bloop JSON files. Defaults to --workspace if not provided.
       |  --update
       |    Use this flag after updating $command to re-generate the Bloop JSON files.
       |  --max-file-count (default=$maxFileCount)
       |    The export process fails fast if the number of exported source files exceeds this threshold.
       |  --intellij
       |    Export Bloop project in empty sibling directory and open IntelliJ after export completes.
       |  --[no-]launch-intellij
       |    Launch IntelliJ after export completes. Default false unless --intellij is enabled.
       |  --project-name
       |    The name of the IntelliJ project to generate when using the  --intellij flag.
       |    Ignored when --intellij is not used. Defaults to the name of the directory
       |    containing the Pants build.
       |  --merge-targets-in-same-directory
       |    If enabled, automatically merge targets that are defined in the same directory.
       |    Disabled by default. This option may help improve the experience in IntelliJ,
       |    since IntelliJ only supports one module per base directory.
       |  --no-sources
       |    Do not download library sources of 3rd party dependencies.
       |
       |Example usage:
       |  $command myproject::                        # Export a single project
       |  $command myproject:: other-project::        # Export multiple projects
       |  $command --intellij myproject::             # Export a single project and launch IntelliJ
       |  $command --update myproject::               # Re-export after updating $command without re-calling Pants.
       |  $command --out subdirectory myproject::     # Generate Bloop JSON files in a subdirectory
       |  $command --max-file-count=10000 myproject:: # Increase the limit for number of files to export.
       |""".stripMargin
}
object Command {
  def parse(args: List[String]): Either[List[String], Command] =
    args match {
      case Nil => Right(Help)
      case ("help" | "-h" | "--help") :: tail =>
        Right(Help)
      case "list" :: tail =>
        Right(ListProjects)
      case "refresh" :: tail =>
        tail match {
          case Nil =>
            Left(List("missing argument: <name>"))
          case name :: Nil =>
            Right(Refresh(name))
          case _ =>
            Left(
              List(
                s"expected only one argument, obtained: ${tail.mkString(", ")}"
              )
            )
        }
      case "create" :: tail =>
        parseCreate(args, Create()).map {
          case parsed: Create =>
            if (parsed.isIntelliJ) {
              val projectName = parsed.projectName.getOrElse(
                parsed.workspace.getFileName().toString()
              )
              parsed.copy(
                out = parsed.workspace
                  .getParent()
                  .resolve("intellij-bsp")
                  .resolve(projectName)
                  .resolve(projectName)
              )
            } else {
              parsed
            }
          case c => c
        }
      case _ =>
        Right(Help)
    }

  def parseCreate(
      args: List[String],
      base: Create
  ): Either[List[String], Command] =
    args match {
      case Nil => Right(base)
      case ("help" | "-h" | "--help") :: tail =>
        Right(Help)
      case "--workspace" :: workspace :: tail =>
        val dir = AbsolutePath(workspace).toNIO
        val out =
          if (base.out == base.workspace) dir
          else base.out
        parseCreate(tail, base.copy(workspace = dir, out = out))
      case "--out" :: out :: tail =>
        parseCreate(
          tail,
          base.copy(out = AbsolutePath(out)(AbsolutePath(base.workspace)).toNIO)
        )
      case "--regenerate" :: tail =>
        parseCreate(tail, base.copy(isRegenerate = true))
      case "--merge-targets-in-same-directory" :: tail =>
        parseCreate(
          tail,
          base.copy(
            isMergeTargetsInSameDirectory = true
          )
        )
      case "--intellij" :: tail =>
        parseCreate(tail, base.copy(isIntelliJ = true, isLaunchIntelliJ = true))
      case "--vscode" :: tail =>
        parseCreate(tail, base.copy(isVscode = true))
      case "--no-sources" :: tail =>
        parseCreate(tail, base.copy(isSources = false))
      case "--launch-intellij" :: tail =>
        parseCreate(tail, base.copy(isLaunchIntelliJ = true))
      case "--no-launch-intellij" :: tail =>
        parseCreate(tail, base.copy(isLaunchIntelliJ = false))
      case ("--update" | "--cache") :: tail =>
        parseCreate(tail, base.copy(isCache = true))
      case "--project-name" :: name :: tail =>
        parseCreate(tail, base.copy(projectName = Some(name)))
      case "--max-file-count" :: count :: tail =>
        Try(count.toInt) match {
          case Failure(_) =>
            Left(
              List(
                s"type mismatch: --max-file-count expected a number, got '$count'"
              )
            )
          case Success(value) =>
            parseCreate(tail, base.copy(maxFileCount = value))
        }
      case tail =>
        tail.headOption match {
          case Some(flag) if flag.startsWith("-") =>
            Left(List(s"unknown flag: $flag\n" + base.helpMessage))
          case _ =>
            Right(base.copy(targets = base.targets ++ tail))
        }
    }
}
