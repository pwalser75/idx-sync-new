package ch.frostnova.cli.idx.sync.cli

import ch.frostnova.cli.idx.sync.core.FileChange
import ch.frostnova.cli.idx.sync.core.SyncAction
import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncPair
import ch.frostnova.cli.idx.sync.core.SyncResult
import ch.frostnova.cli.idx.sync.diff.DiffEngine
import ch.frostnova.cli.idx.sync.scan.SyncFolderScanner
import ch.frostnova.cli.idx.sync.scan.SyncPairResolver
import ch.frostnova.cli.idx.sync.sync.AtomicFileWriter
import ch.frostnova.cli.idx.sync.sync.FileSynchronizer
import ch.frostnova.cli.idx.sync.ui.ConsoleUi
import ch.frostnova.cli.idx.sync.ui.formatBytes
import java.nio.file.Files
import java.nio.file.Path

/**
 * Orchestrates the scan → compare → synchronize pipeline, matching the original tool's flow and output:
 * a cleared scan progress bar, the list of discovered markers and matching pairs, per-pair compare
 * spinners, then a cleared copy progress bar replaced by the final report.
 *
 * The `run`/`restore` entry points return `true` on success and `false` when something went wrong (a
 * selector matched nothing, a precheck failed, or a file operation errored), so `main` can set an exit code.
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

    /**
     * Scan + compare and print the pending changes without applying them, grouped per folder pair: each
     * pair's name and source/target directories, then its changes since the last sync. When [source] is
     * given, only the pairs whose source matches it (by folder-id, folder name, or path) are reported.
     */
    fun diff(mode: SyncMode = SyncMode.SYNC, source: String? = null): List<FileChange> {
        val allPairs = scan()
        if (allPairs.isEmpty()) return emptyList()
        val pairs = selectPairs(allPairs, source) ?: return emptyList()
        val perPair = compareEach(pairs, mode)
        perPair.forEach { (pair, changes) ->
            if (changes.isNotEmpty()) {
                ui.blank()
                ui.pairDiffHeader(pair)
                ui.pendingChanges(count(changes, SyncAction.CREATE), count(changes, SyncAction.UPDATE), count(changes, SyncAction.DELETE))
                ui.blank()
                ui.listChanges(changes)
            }
        }
        return perPair.flatMap { it.second }
    }

    /**
     * Full sync: scan, compare, then apply, with the copy bar replaced by a report. When [source] is given,
     * only the pairs whose source matches it (by folder-id, folder name, or path) are synchronized. When
     * [verify] is set, every copied file is hashed and checked against the source before it replaces the
     * target. Returns whether the run succeeded.
     */
    fun run(
        mode: SyncMode = SyncMode.SYNC,
        source: String? = null,
        verify: Boolean = false,
        fast: Boolean = false,
    ): Boolean {
        val startNs = System.nanoTime()
        val allPairs = scan()
        if (allPairs.isEmpty()) return true // nothing configured — not an error
        val scanSeconds = elapsedSeconds(startNs)
        val pairs = selectPairs(allPairs, source) ?: return false
        ui.blank()

        val compareNs = System.nanoTime()
        val changes = compareAll(pairs, mode)
        val compareSeconds = elapsedSeconds(compareNs)
        ui.pendingChanges(count(changes, SyncAction.CREATE), count(changes, SyncAction.UPDATE), count(changes, SyncAction.DELETE))
        if (changes.isEmpty()) {
            ui.report(mode, SyncResult.EMPTY, elapsedSeconds(startNs))
            ui.phaseTimings(scanSeconds, compareSeconds, 0.0, 0)
            return true
        }

        insufficientSpace(changes)?.let { ui.blank(); ui.error(it); return false }
        ui.blank()

        // In a normal sync EVERY known source root is strictly read-only — not just the selected pairs' —
        // so no write can ever land inside any source, even a target nested in another pair's source.
        val protected = allPairs.map { it.source }
        val copyNs = System.nanoTime()
        val result = ui.copyProgress(totalBytes(changes)) { listener -> synchronizer(verify, fast).sync(changes, protected, listener) }
        val copySeconds = elapsedSeconds(copyNs)
        ui.report(mode, result, elapsedSeconds(startNs))
        ui.phaseTimings(scanSeconds, compareSeconds, copySeconds, result.bytesTransferred)
        return result.errors.isEmpty()
    }

    /**
     * Restore a source folder from its target (reverse sync, never deletes). Requires a source selector
     * ([source] folder-id, folder name, or path); an optional [subPath] restricts the restore to files under
     * that relative path. Shows the differences first and asks the user to confirm before writing anything.
     * Returns whether the restore succeeded.
     */
    fun restore(source: String, subPath: String? = null): Boolean {
        val startNs = System.nanoTime()
        val allPairs = scan()
        if (allPairs.isEmpty()) return true
        val scanSeconds = elapsedSeconds(startNs)
        val pairs = selectPairs(allPairs, source) ?: return false
        ui.blank()

        val compareNs = System.nanoTime()
        val changes = restrictTo(compareAll(pairs, SyncMode.RESTORE), subPath)
        val compareSeconds = elapsedSeconds(compareNs)
        if (changes.isEmpty()) {
            ui.report(SyncMode.RESTORE, SyncResult.EMPTY, elapsedSeconds(startNs))
            return true
        }
        ui.line("The following files would be restored at the source:")
        ui.blank()
        ui.listChanges(changes)
        ui.blank()
        if (!ui.confirm("Restore ${changes.size} file(s)?")) {
            ui.warn("Aborted — nothing was restored.")
            return true // the user chose not to proceed — not a failure
        }
        insufficientSpace(changes)?.let { ui.blank(); ui.error(it); return false }
        ui.blank()
        // Restore writes the source; EVERY known target is the read-only side here.
        val protected = allPairs.map { it.target }
        val copyNs = System.nanoTime()
        val result = ui.copyProgress(totalBytes(changes)) { listener -> synchronizer.sync(changes, protected, listener) }
        val copySeconds = elapsedSeconds(copyNs)
        ui.report(SyncMode.RESTORE, result, elapsedSeconds(startNs))
        ui.phaseTimings(scanSeconds, compareSeconds, copySeconds, result.bytesTransferred)
        return result.errors.isEmpty()
    }

    /**
     * Narrow [pairs] to those whose source matches [selector] — its `folder-id`, folder name, or path —
     * returning every match (several pairs may share a source). A `null` [selector] selects all pairs.
     * Returns `null` when the selector matched nothing (an error is reported). [pairs] must be non-empty.
     */
    private fun selectPairs(pairs: List<SyncPair>, selector: String?): List<SyncPair>? {
        if (selector == null) return pairs
        val selected = matchPairs(pairs, selector)
        if (selected.isEmpty()) {
            ui.blank()
            ui.error("No sync pair matches source folder-id, name or path '$selector'.")
            return null
        }
        return selected
    }

    /** Keep only changes whose relative path is at or under [subPath] (all changes when [subPath] is null). */
    private fun restrictTo(changes: List<FileChange>, subPath: String?): List<FileChange> {
        if (subPath.isNullOrBlank()) return changes
        val prefix = Path.of(subPath)
        return changes.filter { it.relativePath == prefix || it.relativePath.startsWith(prefix) }
    }

    /**
     * The synchronizer to use for this run. [verify] hash-checks every copy (implies durable writes);
     * [fast] trades power-loss durability for speed by skipping the per-file fsync (still crash-safe against
     * an abort). With neither flag the injected default is reused.
     */
    private fun synchronizer(verify: Boolean, fast: Boolean): FileSynchronizer = when {
        verify -> FileSynchronizer(AtomicFileWriter(verify = true))
        fast -> FileSynchronizer(AtomicFileWriter(durable = false))
        else -> synchronizer
    }

    /**
     * A message when the bytes to be written won't fit on a destination filesystem, or `null` if they will.
     * Sizes are summed per file store; an UPDATE is counted at full size because the crash-safe writer keeps
     * the old file until the new one is complete, so peak usage is the new size.
     */
    private fun insufficientSpace(changes: List<FileChange>): String? {
        val perStore = HashMap<java.nio.file.FileStore, Long>()
        // `getFileStore` is a syscall (and `nearestExisting` walks the tree); resolving it per file across
        // tens of thousands of changes is a long, pointless stall before the copy even starts. All files
        // under one directory share a store, so cache by parent directory — one lookup per folder, not file.
        val storeByDir = HashMap<Path, java.nio.file.FileStore?>()
        for (change in changes) {
            if (change.action != SyncAction.CREATE && change.action != SyncAction.UPDATE) continue
            val dir = change.destination.parent ?: change.destination
            val store = storeByDir.getOrPut(dir) {
                runCatching { Files.getFileStore(nearestExisting(change.destination)) }.getOrNull()
            } ?: continue
            perStore.merge(store, change.size) { a, b -> a + b }
        }
        for ((store, required) in perStore) {
            val usable = runCatching { store.usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (required > usable) {
                return "not enough free space on '${store.name()}': need ${formatBytes(required)}, only ${formatBytes(usable)} free"
            }
        }
        return null
    }

    /** The nearest ancestor of [path] that exists (so its file store can be queried before we create it). */
    private fun nearestExisting(path: Path): Path {
        var p = path.toAbsolutePath()
        while (!Files.exists(p) && p.parent != null) p = p.parent
        return p
    }

    private fun totalBytes(changes: List<FileChange>): Long = changes
        .filter { it.action == SyncAction.CREATE || it.action == SyncAction.UPDATE }
        .sumOf { it.size }

    private fun compareAll(pairs: List<SyncPair>, mode: SyncMode): List<FileChange> =
        compareEach(pairs, mode).flatMap { it.second }

    /** Compare every pair under one in-place progress line, keeping each pair's changes grouped. */
    private fun compareEach(pairs: List<SyncPair>, mode: SyncMode): List<Pair<SyncPair, List<FileChange>>> =
        // One in-place progress line for the whole compare phase, cleared when done — leaving only the
        // per-pair "Changes since last sync" summaries.
        ui.spinner("Comparing") { detail ->
            pairs.map { pair ->
                pair to diffEngine.diff(pair, mode) { status -> detail("${pair.name} — $status") }
            }
        }

    private fun count(changes: List<FileChange>, action: SyncAction) = changes.count { it.action == action }

    private fun elapsedSeconds(startNs: Long): Double = (System.nanoTime() - startNs) / 1e9

    companion object {
        /**
         * All [pairs] whose source matches [selector], by `folder-id`, folder name, or filesystem path
         * (the pair's source or target directory). Returns every match, so multiple pairs sharing a source
         * id/name are all selected.
         */
        internal fun matchPairs(pairs: List<SyncPair>, selector: String): List<SyncPair> {
            val asPath = runCatching { Path.of(selector).toAbsolutePath().normalize() }.getOrNull()
            return pairs.filter { pair ->
                pair.sourceId == selector || pair.name == selector ||
                    (asPath != null && (pair.source.toAbsolutePath().normalize() == asPath ||
                        pair.target.toAbsolutePath().normalize() == asPath))
            }
        }
    }
}
