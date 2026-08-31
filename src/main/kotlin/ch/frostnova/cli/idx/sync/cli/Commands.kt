package ch.frostnova.cli.idx.sync.cli

import ch.frostnova.cli.idx.sync.config.IdxSyncFile
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.ui.ConsoleUi
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isWritable

/** Default exclude patterns seeded into a newly-marked source folder. */
private val DEFAULT_SOURCE_EXCLUDES = setOf("node_modules", ".git", "target", "build")

/** Root command: prints the logo (runs for every invocation), then delegates to a subcommand. */
class IdxSync(private val ui: ConsoleUi) : CliktCommand() {
    override fun run() = ui.logo()
}

class Sync(private val app: SyncApplication) : CliktCommand() {
    override fun help(context: Context) = "Scan, compare and synchronize all matching folder pairs"
    override fun run() = app.run(SyncMode.SYNC)
}

class Restore(private val app: SyncApplication) : CliktCommand() {
    override fun help(context: Context) = "Reverse sync: restore files from target back to source (never deletes)"
    override fun run() = app.run(SyncMode.RESTORE)
}

class Scan(private val app: SyncApplication) : CliktCommand() {
    override fun help(context: Context) = "Scan the file system for markers and list matching sync pairs"
    override fun run() { app.scan() }
}

class Diff(private val app: SyncApplication) : CliktCommand() {
    override fun help(context: Context) = "Scan and compare pairs, reporting pending changes without applying them"
    override fun run() { app.diff(SyncMode.SYNC) }
}

class Source(private val ui: ConsoleUi, private val repository: IdxSyncFileRepository) : CliktCommand() {
    override fun help(context: Context) = "Mark a folder as a synchronization source"
    private val path by argument(help = "the folder to mark as a source").path()
    private val name by argument(help = "a display name for the folder")

    override fun run() {
        requireWritableDir(ui, path) ?: return
        val marker = IdxSyncFile(
            folderId = UUID.randomUUID().toString(),
            folderName = name,
            excludePatterns = DEFAULT_SOURCE_EXCLUDES,
            includeHidden = true,
        )
        repository.write(path, marker)
        ui.success("Marked source '$name' in $path")
        ui.info("folder-id: ${marker.folderId}")
    }
}

class Target(private val ui: ConsoleUi, private val repository: IdxSyncFileRepository) : CliktCommand() {
    override fun help(context: Context) = "Mark a folder as a target that mirrors a given source"
    private val path by argument(help = "the folder to mark as a target").path()
    private val sourceId by argument(help = "the folder-id of the source to mirror")

    override fun run() {
        requireWritableDir(ui, path) ?: return
        val marker = IdxSyncFile(folderId = UUID.randomUUID().toString(), sourceFolderId = sourceId)
        repository.write(path, marker)
        ui.success("Marked target in $path (mirrors source $sourceId)")
    }
}

class Remove(private val ui: ConsoleUi, private val repository: IdxSyncFileRepository) : CliktCommand() {
    override fun help(context: Context) = "Remove the .idxsync marker from a folder"
    private val path by argument(help = "the folder whose marker to remove").path()

    override fun run() {
        if (repository.remove(path)) ui.success("Removed marker from $path")
        else ui.warn("No marker found in $path")
    }
}

class Demo(private val ui: ConsoleUi) : CliktCommand() {
    override fun help(context: Context) = "Simulate a backup run to showcase the UI (no files touched)"
    private val duration by argument(help = "how long the demo should run, e.g. 15s, 2m, 500ms").default("12s")

    override fun run() = DemoRunner(ui).run(parseDurationMillis(duration))
}

private fun requireWritableDir(ui: ConsoleUi, path: Path): Unit? {
    return when {
        !path.exists() -> { ui.error("$path does not exist"); null }
        !path.isDirectory() -> { ui.error("$path is not a directory"); null }
        !path.isWritable() -> { ui.error("$path is not writable"); null }
        else -> Unit
    }
}

/** Parse a human duration like `15s`, `2m`, `500ms`, `1h`, or a bare number (seconds). */
fun parseDurationMillis(text: String): Long {
    val match = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)?\\s*$").find(text.lowercase())
        ?: throw IllegalArgumentException("invalid duration: '$text' (try 15s, 2m, 500ms)")
    val value = match.groupValues[1].toDouble()
    val factor = when (match.groupValues[2]) {
        "ms" -> 1.0
        "m" -> 60_000.0
        "h" -> 3_600_000.0
        else -> 1_000.0 // "s" or bare number
    }
    return (value * factor).toLong().coerceAtLeast(1)
}
