package ch.frostnova.cli.idx.sync

import ch.frostnova.cli.idx.sync.cli.Demo
import ch.frostnova.cli.idx.sync.cli.Diff
import ch.frostnova.cli.idx.sync.cli.IdxSync
import ch.frostnova.cli.idx.sync.cli.PairCommand
import ch.frostnova.cli.idx.sync.cli.Remove
import ch.frostnova.cli.idx.sync.cli.Restore
import ch.frostnova.cli.idx.sync.cli.Scan
import ch.frostnova.cli.idx.sync.cli.Source
import ch.frostnova.cli.idx.sync.cli.Sync
import ch.frostnova.cli.idx.sync.cli.SyncApplication
import ch.frostnova.cli.idx.sync.cli.Target
import ch.frostnova.cli.idx.sync.cli.Version
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import ch.frostnova.cli.idx.sync.ui.ConsoleEncoding
import ch.frostnova.cli.idx.sync.ui.ConsoleUi
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ContextCliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Ensure the console can render our Unicode output (UTF-8 + Windows code page) before anything prints.
    ConsoleEncoding.configure()

    // `--ascii` / `--no-unicode` (or NO_UNICODE / IDX_SYNC_ASCII) select plain-ASCII output. Handle it here,
    // before the terminal is built, and strip it from the args Clikt sees.
    val ascii = args.any { it in ASCII_FLAGS } ||
        System.getenv("NO_UNICODE") != null || System.getenv("IDX_SYNC_ASCII") != null
    val cliArgs = args.filterNot { it in ASCII_FLAGS }

    val ui = ConsoleUi(ascii = ascii)
    val app = SyncApplication(ui)
    val repository = IdxSyncFileRepository()

    // No arguments (or an explicit help request) just prints the usage, as in the original tool.
    if (cliArgs.isEmpty() || cliArgs.first() in HELP_FLAGS) {
        ui.logo()
        ui.usage()
        return
    }

    // The logo would pollute machine-readable output, so suppress it for `version`.
    val quiet = cliArgs.first() in QUIET_COMMANDS
    val root = IdxSync(ui, printLogo = !quiet)
        .subcommands(
            Scan(app),
            Diff(app),
            Sync(app),
            Source(ui, repository),
            Target(ui, repository),
            PairCommand(ui, repository),
            Remove(ui, repository),
            Restore(app),
            Version(ui),
            Demo(ui),
        )
        .versionOption(appVersion(), names = setOf("--version", "-V")) { "idx-sync $it" }

    val exitCode = try {
        root.parse(cliArgs)
        0
    } catch (e: PrintHelpMessage) {
        ui.logo()
        ui.usage()
        0
    } catch (e: PrintMessage) {
        // e.g. `--version` output.
        e.message?.let { println(it) }
        0
    } catch (e: CliktError) {
        // ProgramResult (a failed sync/restore) carries no message and printError=false — the command
        // already printed its own report, so just adopt its status code. Genuine usage errors do print.
        if (e.printError) {
            ui.logo()
            // A UsageError (e.g. a missing argument) carries no `message` — its text is only available
            // formatted against the context of the (sub)command that failed, which also gives us that
            // command's own usage line rather than the generic top-level one.
            val formatted = (e as? ContextCliktError)?.context?.command?.getFormattedHelp(e)
            when {
                !formatted.isNullOrBlank() -> println(formatted)
                !e.message.isNullOrBlank() -> {
                    ui.error(e.message!!)
                    ui.usage()
                }
                else -> ui.usage()
            }
        }
        e.statusCode.takeIf { it != 0 } ?: 1
    } catch (e: Throwable) {
        ui.error("${e.javaClass.simpleName}: ${e.message}")
        1
    }
    if (exitCode != 0) exitProcess(exitCode)
}

private val HELP_FLAGS = setOf("-h", "--help", "help")
private val ASCII_FLAGS = setOf("--ascii", "--no-unicode")
private val QUIET_COMMANDS = setOf("version")

/** Application version, resolved from the packaged manifest (falls back to a dev marker). */
fun appVersion(): String = object {}.javaClass.`package`?.implementationVersion ?: "dev"
