package ch.frostnova.cli.idx.sync.diff

import ch.frostnova.cli.idx.sync.core.FileChange
import ch.frostnova.cli.idx.sync.core.SyncAction
import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncPair
import ch.frostnova.cli.idx.sync.filter.ExcludeFilter
import ch.frostnova.cli.idx.sync.scan.FileTreeWalker
import ch.frostnova.cli.idx.sync.scan.Visit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration

/**
 * Computes the [FileChange]s needed to bring a destination in line with an origin, for a [SyncPair].
 *
 * For [SyncMode.SYNC] the origin is the source and the destination is the target (mirror: create, update,
 * delete). For [SyncMode.RESTORE] the roles are reversed (target → source) and deletions are never emitted,
 * so restore only ever brings files *back*.
 *
 * Two files are considered different when their sizes differ or their last-modified times differ by more
 * than [updateThreshold]. The default is 2s: FAT/exFAT (common on the USB sticks this tool targets) store
 * mtimes at 2-second resolution, so a tighter threshold would flag every file as changed on each run.
 */
class DiffEngine(
    private val walker: FileTreeWalker = FileTreeWalker(),
    private val updateThreshold: Duration = Duration.ofSeconds(2),
) {

    /**
     * [onProgress] receives a short human-readable status for the phase currently running — the path being
     * scanned while walking the source/target trees, then a distinct "computing changes…" message once both
     * walks are done and the (callback-free) in-memory diff begins. Without this the UI would freeze on the
     * last scanned path while the diff is computed, misleading the user into thinking it's still scanning it.
     */
    fun diff(pair: SyncPair, mode: SyncMode = SyncMode.SYNC, onProgress: (String) -> Unit = {}): List<FileChange> {
        val filter = ExcludeFilter(pair.excludePatterns, pair.includeHidden)
        val (originRoot, destRoot) = when (mode) {
            SyncMode.SYNC -> pair.source to pair.target
            SyncMode.RESTORE -> pair.target to pair.source
        }

        // The walk is the expensive, I/O-bound part, and only the final in-memory diff needs both trees.
        // When source and target sit on *independent* devices (e.g. a USB stick and a disk — the intended
        // setup) the two enumerations overlap on separate threads to hide latency. But when they share one
        // physical device (e.g. two partitions of the same spinning disk), running both at once makes the
        // drive head thrash between them and is *slower* than going one at a time — so there we walk
        // sequentially. The shared progress callback is serialised so parallel walkers never clash on the UI.
        val progressLock = Any()
        val report: (String) -> Unit = { msg -> synchronized(progressLock) { onProgress(msg) } }
        val scanSource: (Path) -> Unit = { report("scanning source: $it") }
        val scanTarget: (Path) -> Unit = { report("scanning target: $it") }

        val origin: Tree
        val dest: Tree
        if (onSameDevice(originRoot, destRoot)) {
            origin = enumerate(originRoot, filter, scanSource)
            dest = enumerate(destRoot, filter, scanTarget)
        } else {
            var destTree: Tree? = null
            var destFailure: Throwable? = null
            val destWalk = Thread({
                try {
                    destTree = enumerate(destRoot, filter, scanTarget)
                } catch (t: Throwable) {
                    destFailure = t
                }
            }, "idx-diff-dest").apply { start() }

            origin = enumerate(originRoot, filter, scanSource)
            destWalk.join()
            destFailure?.let { throw it }
            dest = destTree!!
        }

        // Both walks are done; the rest is CPU-bound and callback-free, so announce the phase change or the
        // UI would appear frozen on the last scanned path while thousands of entries are sorted and diffed.
        report("computing changes (${origin.files.size} source / ${dest.files.size} target files)…")

        val changes = mutableListOf<FileChange>()

        // CREATE / UPDATE — every origin file, in stable order.
        for (rel in origin.files.keys.sorted()) {
            val originFile = origin.files.getValue(rel)
            val destFile = dest.files[rel]
            val attrs = origin.attributes.getValue(rel)
            when {
                destFile == null ->
                    changes += FileChange(rel, originFile, destRoot.resolve(rel), SyncAction.CREATE, attrs.size())

                differs(attrs, dest.attributes.getValue(rel)) ->
                    changes += FileChange(rel, originFile, destFile, SyncAction.UPDATE, attrs.size())
            }
        }

        // DELETE — destination entries with no origin counterpart (mirror only; never on restore).
        if (mode == SyncMode.SYNC) {
            val extras = (dest.files.keys - origin.files.keys) + (dest.dirs - origin.dirs)
            // Keep only the roots of each removed subtree so we delete once, recursively.
            val roots = extras.filter { it.parent == null || it.parent !in extras }
            for (rel in roots.sorted()) {
                changes += FileChange(rel, originRoot.resolve(rel), destRoot.resolve(rel), SyncAction.DELETE)
            }
        }

        return changes
    }

    /**
     * Best-effort test of whether [a] and [b] live on the same *physical* device — true when we're confident
     * they do, false when they're independent or when we can't tell (the safe default, since guessing "same"
     * would needlessly serialise a genuinely parallel setup). Same filesystem is an obvious yes; two
     * partitions of one disk are caught by reducing each store's backing device (`/dev/sda2`, `/dev/nvme0n1p3`)
     * to its base disk (`/dev/sda`, `/dev/nvme0n1`) and comparing. Any failure or unrecognised name → false.
     */
    private fun onSameDevice(a: Path, b: Path): Boolean = runCatching {
        val storeA = Files.getFileStore(a)
        val storeB = Files.getFileStore(b)
        if (storeA == storeB) return true
        val keyA = physicalDeviceKey(storeA)
        val keyB = physicalDeviceKey(storeB)
        keyA != null && keyA == keyB
    }.getOrDefault(false)

    /**
     * Reduce a file store's backing device name to the physical disk it belongs to, or `null` when the name
     * isn't a recognisable device path. Strips a trailing partition number, plus the `p` separator used by
     * `nvme`/`mmcblk`-style names (`/dev/nvme0n1p3` → `/dev/nvme0n1`, `/dev/sda2` → `/dev/sda`).
     */
    private fun physicalDeviceKey(store: java.nio.file.FileStore): String? {
        val name = store.name()
        if (!name.startsWith("/dev/")) return null // volume labels / non-Linux names: can't compare reliably
        var base = name.trimEnd { it.isDigit() }
        if (base.length < name.length && base.endsWith("p")) {
            val withoutP = base.dropLast(1)
            if (withoutP.lastOrNull()?.isDigit() == true) base = withoutP // nvme0n1p3 -> nvme0n1
        }
        return base
    }

    private fun differs(a: BasicFileAttributes, b: BasicFileAttributes): Boolean {
        if (a.size() != b.size()) return true
        val delta = Duration.between(a.lastModifiedTime().toInstant(), b.lastModifiedTime().toInstant()).abs()
        return delta > updateThreshold
    }

    private fun enumerate(root: Path, filter: ExcludeFilter, onProgress: (Path) -> Unit): Tree {
        val files = HashMap<Path, Path>()
        val attributes = HashMap<Path, BasicFileAttributes>()
        val dirs = HashSet<Path>()

        walker.walk(root) { path, attrs ->
            if (path == root) return@walk Visit.CONTINUE
            val rel = root.relativize(path)
            if (filter.excludes(rel)) {
                return@walk if (attrs.isDirectory) Visit.SKIP_SUBTREE else Visit.CONTINUE
            }
            onProgress(path)
            when {
                attrs.isDirectory -> dirs.add(rel)
                attrs.isRegularFile -> {
                    // Attributes were read once by the walker (NOFOLLOW_LINKS) — no extra stat here.
                    files[rel] = path
                    attributes[rel] = attrs
                }
                // symlinks and other non-regular entries are treated as absent (symlink-safe)
            }
            Visit.CONTINUE
        }
        return Tree(files, dirs, attributes)
    }

    private class Tree(
        val files: Map<Path, Path>,
        val dirs: Set<Path>,
        val attributes: Map<Path, BasicFileAttributes>,
    )
}
