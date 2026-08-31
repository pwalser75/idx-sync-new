package ch.frostnova.cli.idx.sync

import ch.frostnova.cli.idx.sync.cli.Demo
import ch.frostnova.cli.idx.sync.cli.Diff
import ch.frostnova.cli.idx.sync.cli.IdxSync
import ch.frostnova.cli.idx.sync.cli.Remove
import ch.frostnova.cli.idx.sync.cli.Restore
import ch.frostnova.cli.idx.sync.cli.Run
import ch.frostnova.cli.idx.sync.cli.Scan
import ch.frostnova.cli.idx.sync.cli.Source
import ch.frostnova.cli.idx.sync.cli.SyncApplication
import ch.frostnova.cli.idx.sync.cli.Target
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import ch.frostnova.cli.idx.sync.ui.ConsoleUi
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) {
    val ui = ConsoleUi()
    val app = SyncApplication(ui)
    val repository = IdxSyncFileRepository()

    // With no arguments, default to a full sync run.
    val effectiveArgs = if (args.isEmpty()) arrayOf("run") else args

    IdxSync(ui)
        .subcommands(
            Run(app),
            Restore(app),
            Scan(app),
            Diff(app),
            Source(ui, repository),
            Target(ui, repository),
            Remove(ui, repository),
            Demo(ui),
        )
        .main(effectiveArgs)
}

/** Application version, resolved from the packaged manifest (falls back to a dev marker). */
fun appVersion(): String = object {}.javaClass.`package`?.implementationVersion ?: "dev"
