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
        val (overlapping, pairs) = resolver.resolve(markers).partition { it.overlapping }
        ui.listMatchingPairs(pairs)
        if (overlapping.isNotEmpty()) {
            ui.blank()
            ui.listOverlappingPairs(overlapping)
        }
        return pairs // overlapping pairs are reported but never synchronized
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

    /**
     * Full sync: scan, compare, then apply, with the copy bar replaced by a report. When [sourceId] is
     * given, only the pair with that source folder-id is synchronized.
     */
    fun run(mode: SyncMode = SyncMode.SYNC, sourceId: String? = null) {
        val startNs = System.nanoTime()
        val pairs = selectPairs(scan(), sourceId) ?: return
        ui.blank()

        val changes = compareAll(pairs, mode)
        ui.pendingChanges(count(changes, SyncAction.CREATE), count(changes, SyncAction.UPDATE), count(changes, SyncAction.DELETE))
        if (changes.isEmpty()) {
            ui.report(mode, SyncResult.EMPTY, elapsedSeconds(startNs))
            return
        }
        ui.blank()

        // In a normal sync the source roots are strictly read-only.
        val protected = pairs.map { it.source }
        val result = ui.copyProgress(totalBytes(changes)) { listener -> synchronizer.sync(changes, protected, listener) }
        ui.report(mode, result, elapsedSeconds(startNs))
    }

    /**
     * Restore a single source folder from its target (reverse sync, never deletes). Requires the source
     * folder-id, shows the differences first, and asks the user to confirm before writing anything.
     */
    fun restore(sourceId: String) {
        val startNs = System.nanoTime()
        val pairs = selectPairs(scan(), sourceId) ?: return
        ui.blank()

        val changes = compareAll(pairs, SyncMode.RESTORE)
        if (changes.isEmpty()) {
            ui.report(SyncMode.RESTORE, SyncResult.EMPTY, elapsedSeconds(startNs))
            return
        }
        ui.line("The following files would be restored at the source:")
        ui.blank()
        ui.listChanges(changes)
        ui.blank()
        if (!ui.confirm("Restore ${changes.size} file(s)?")) {
            ui.warn("Aborted — nothing was restored.")
            return
        }
        ui.blank()
        // Restore writes the source; the target is the read-only side here.
        val protected = pairs.map { it.target }
        val result = ui.copyProgress(totalBytes(changes)) { listener -> synchronizer.sync(changes, protected, listener) }
        ui.report(SyncMode.RESTORE, result, elapsedSeconds(startNs))
    }

    /** Narrow [pairs] to a single source folder-id, or return all. `null` = nothing to do (already reported). */
    private fun selectPairs(pairs: List<SyncPair>, sourceId: String?): List<SyncPair>? {
        if (pairs.isEmpty()) return null
        if (sourceId == null) return pairs
        val selected = pairs.filter { it.sourceId == sourceId }
        if (selected.isEmpty()) {
            ui.blank()
            ui.warn("No matching sync pair for source folder-id '$sourceId'.")
            return null
        }
        return selected
    }

    private fun totalBytes(changes: List<FileChange>): Long = changes
        .filter { it.action == SyncAction.CREATE || it.action == SyncAction.UPDATE }
        .sumOf { it.size }

    private fun compareAll(pairs: List<SyncPair>, mode: SyncMode): List<FileChange> =
        // One in-place progress line for the whole compare phase, cleared when done — leaving only the
        // "Changes since last sync" summary.
        ui.spinner("Comparing") { detail ->
            pairs.flatMap { pair ->
                diffEngine.diff(pair, mode) { path -> detail("${pair.name}: $path") }
            }
        }

    private fun count(changes: List<FileChange>, action: SyncAction) = changes.count { it.action == action }

    private fun elapsedSeconds(startNs: Long): Double = (System.nanoTime() - startNs) / 1e9
}
