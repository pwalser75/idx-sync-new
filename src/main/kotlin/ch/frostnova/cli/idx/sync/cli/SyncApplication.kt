package ch.frostnova.cli.idx.sync.cli

import ch.frostnova.cli.idx.sync.core.FileChange
import ch.frostnova.cli.idx.sync.core.SyncAction
import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncPair
import ch.frostnova.cli.idx.sync.core.SyncResult
import ch.frostnova.cli.idx.sync.diff.DiffEngine
import ch.frostnova.cli.idx.sync.scan.SyncFolderScanner
import ch.frostnova.cli.idx.sync.scan.SyncPairResolver
import ch.frostnova.cli.idx.sync.sync.FileSynchronizer
import ch.frostnova.cli.idx.sync.ui.ConsoleUi
import ch.frostnova.cli.idx.sync.ui.formatBytes

/**
 * Orchestrates the scan → compare → synchronize pipeline, delegating all rendering to [ConsoleUi] and all
 * work to the engines. This is the seam the CLI commands and the demo share.
 */
class SyncApplication(
    private val ui: ConsoleUi = ConsoleUi(),
    private val scanner: SyncFolderScanner = SyncFolderScanner(),
    private val resolver: SyncPairResolver = SyncPairResolver(),
    private val diffEngine: DiffEngine = DiffEngine(),
    private val synchronizer: FileSynchronizer = FileSynchronizer(),
) {

    /** Scan for markers and report the resolved pairs. */
    fun scan(): List<SyncPair> {
        ui.heading("Scanning for sync folders")
        val markers = ui.spinner("Scanning file system") { detail ->
            scanner.scan { path -> detail(path.toString()) }
        }
        val pairs = resolver.resolve(markers)
        if (pairs.isEmpty()) {
            ui.warn("No matching sync pairs found.")
        } else {
            ui.success("Found ${pairs.size} sync pair(s):")
            pairs.forEach { ui.bullet("${it.name}:  ${it.source}  →  ${it.target}") }
        }
        ui.blank()
        return pairs
    }

    /** Scan + compare and print the pending changes without applying them. */
    fun diff(mode: SyncMode = SyncMode.SYNC): List<FileChange> {
        val pairs = scan()
        if (pairs.isEmpty()) return emptyList()
        val changes = compareAll(pairs, mode)
        printChangeSummary(changes)
        return changes
    }

    /** Full pipeline: scan, compare, then apply, with a closing summary. */
    fun run(mode: SyncMode = SyncMode.SYNC) {
        val startNs = System.nanoTime()
        val pairs = scan()
        if (pairs.isEmpty()) return

        val changes = compareAll(pairs, mode)
        if (changes.isEmpty()) {
            ui.summary(mode, SyncResult.EMPTY, elapsedSeconds(startNs))
            return
        }
        printChangeSummary(changes)

        val totalBytes = changes
            .filter { it.action == SyncAction.CREATE || it.action == SyncAction.UPDATE }
            .sumOf { it.size }

        val result = ui.copyProgress(totalBytes) { listener -> synchronizer.sync(changes, listener) }
        ui.summary(mode, result, elapsedSeconds(startNs))
    }

    private fun compareAll(pairs: List<SyncPair>, mode: SyncMode): List<FileChange> =
        pairs.flatMap { pair ->
            ui.spinner("Comparing ${pair.name}") { detail ->
                diffEngine.diff(pair, mode) { path -> detail(path.toString()) }
            }
        }

    private fun printChangeSummary(changes: List<FileChange>) {
        val byAction = changes.groupingBy { it.action }.eachCount()
        val parts = buildList {
            byAction[SyncAction.CREATE]?.let { add("$it to create") }
            byAction[SyncAction.UPDATE]?.let { add("$it to update") }
            byAction[SyncAction.DELETE]?.let { add("$it to delete") }
        }
        val bytes = changes.filter { it.action == SyncAction.CREATE || it.action == SyncAction.UPDATE }
            .sumOf { it.size }
        ui.step("Pending changes: ${parts.joinToString(", ").ifEmpty { "none" }} (${formatBytes(bytes)})")
        ui.blank()
    }

    private fun elapsedSeconds(startNs: Long): Double = (System.nanoTime() - startNs) / 1e9
}
