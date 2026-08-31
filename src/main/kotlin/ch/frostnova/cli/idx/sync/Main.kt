package ch.frostnova.cli.idx.sync

import ch.frostnova.cli.idx.sync.cli.Demo
import ch.frostnova.cli.idx.sync.cli.Diff
import ch.frostnova.cli.idx.sync.cli.IdxSync
import ch.frostnova.cli.idx.sync.cli.Remove
import ch.frostnova.cli.idx.sync.cli.Restore
import ch.frostnova.cli.idx.sync.cli.Scan
import ch.frostnova.cli.idx.sync.cli.Source
import ch.frostnova.cli.idx.sync.cli.Sync
import ch.frostnova.cli.idx.sync.cli.SyncApplication
import ch.frostnova.cli.idx.sync.cli.Target
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import ch.frostnova.cli.idx.sync.ui.ConsoleUi
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) {
    val ui = ConsoleUi()
    val app = SyncApplication(ui)
    val repository = IdxSyncFileRepository()

    // No arguments (or an explicit help request) just prints the usage, as in the original tool.
    if (args.isEmpty() || args.first() in HELP_FLAGS) {
        ui.logo()
        ui.usage()
        return
    }

    val root = IdxSync(ui).subcommands(
        Scan(app),
        Diff(app),
        Sync(app),
        Source(ui, repository),
        Target(ui, repository),
        Remove(ui, repository),
        Restore(app),
        Demo(ui),
    )

    try {
        root.parse(args)
    } catch (e: PrintHelpMessage) {
        ui.logo()
        ui.usage()
    } catch (e: CliktError) {
        ui.logo()
        e.message?.takeIf { it.isNotBlank() }?.let { ui.error(it) }
        ui.usage()
    } catch (e: Throwable) {
        ui.error("${e.javaClass.simpleName}: ${e.message}")
    }
}

private val HELP_FLAGS = setOf("-h", "--help", "help")

/** Application version, resolved from the packaged manifest (falls back to a dev marker). */
fun appVersion(): String = object {}.javaClass.`package`?.implementationVersion ?: "dev"
