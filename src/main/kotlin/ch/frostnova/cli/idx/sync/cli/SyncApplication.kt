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

/**
 * Orchestrates the scan → compare → synchronize pipeline, matching the original tool's flow and output:
 * a cleared scan progress bar, the list of discovered markers and matching pairs, per-pair compare
 * spinners, then a cleared copy progress bar replaced by the final report.
 */
class SyncApplication(
    private val ui: ConsoleUi = ConsoleUi(),
    private val scanner: SyncFolderScanner = SyncFolderScanner(),
    private val resolver: SyncPairResolver = SyncPairResolver(),
    private val diffEngine: DiffEngine = DiffEngine(),
    private val synchronizer: FileSynchronizer = FileSynchronizer(),
) {

    /** Scan for markers (progress bar cleared when done), list markers + matching pairs. */
    fun scan(): List<SyncPair> {
        val markers = ui.fractionProgress("Scanning for sync files") { report ->
            scanner.scan { fraction, dir -> report(fraction, dir) }
        }
        ui.listFoundMarkers(markers)
        ui.blank()
        val pairs = resolver.resolve(markers)
        ui.listMatchingPairs(pairs)
        return pairs
    }

    /** Scan + compare and print the pending changes without applying them. */
    fun diff(mode: SyncMode = SyncMode.SYNC): List<FileChange> {
        val pairs = scan()
        if (pairs.isEmpty()) return emptyList()
        ui.blank()
        val changes = compareAll(pairs, mode)
        ui.pendingChanges(count(changes, SyncAction.CREATE), count(changes, SyncAction.UPDATE), count(changes, SyncAction.DELETE))
        if (changes.isNotEmpty()) {
            ui.blank()
            ui.listChanges(changes)
        }
        return changes
    }

    /** Full pipeline: scan, compare, then apply, with the copy bar replaced by a report. */
    fun run(mode: SyncMode = SyncMode.SYNC) {
        val startNs = System.nanoTime()
        val pairs = scan()
        if (pairs.isEmpty()) return
        ui.blank()

        val changes = compareAll(pairs, mode)
        ui.pendingChanges(count(changes, SyncAction.CREATE), count(changes, SyncAction.UPDATE), count(changes, SyncAction.DELETE))
        if (changes.isEmpty()) {
            ui.report(mode, SyncResult.EMPTY, elapsedSeconds(startNs))
            return
        }
        ui.blank()

        val totalBytes = changes
            .filter { it.action == SyncAction.CREATE || it.action == SyncAction.UPDATE }
            .sumOf { it.size }

        val result = ui.copyProgress(totalBytes) { listener -> synchronizer.sync(changes, listener) }
        ui.report(mode, result, elapsedSeconds(startNs))
    }

    private fun compareAll(pairs: List<SyncPair>, mode: SyncMode): List<FileChange> =
        pairs.flatMap { pair ->
            ui.spinner("Comparing ${pair.name}") { detail ->
                diffEngine.diff(pair, mode) { path -> detail(path.toString()) }
            }
        }

    private fun count(changes: List<FileChange>, action: SyncAction) = changes.count { it.action == action }

    private fun elapsedSeconds(startNs: Long): Double = (System.nanoTime() - startNs) / 1e9
}
