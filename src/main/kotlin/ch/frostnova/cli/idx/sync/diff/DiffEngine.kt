package ch.frostnova.cli.idx.sync.diff

import ch.frostnova.cli.idx.sync.core.FileChange
import ch.frostnova.cli.idx.sync.core.SyncAction
import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncPair
import ch.frostnova.cli.idx.sync.filter.ExcludeFilter
import ch.frostnova.cli.idx.sync.scan.FileTreeWalker
import ch.frostnova.cli.idx.sync.scan.Visit
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readAttributes

/**
 * Computes the [FileChange]s needed to bring a destination in line with an origin, for a [SyncPair].
 *
 * For [SyncMode.SYNC] the origin is the source and the destination is the target (mirror: create, update,
 * delete). For [SyncMode.RESTORE] the roles are reversed (target → source) and deletions are never emitted,
 * so restore only ever brings files *back*.
 *
 * Two files are considered different when their sizes differ or their last-modified times differ by more
 * than [updateThreshold] (filesystems and copies rarely preserve sub-second precision).
 */
class DiffEngine(
    private val walker: FileTreeWalker = FileTreeWalker(),
    private val updateThreshold: Duration = Duration.ofSeconds(1),
) {

    fun diff(pair: SyncPair, mode: SyncMode = SyncMode.SYNC, onProgress: (Path) -> Unit = {}): List<FileChange> {
        val filter = ExcludeFilter(pair.excludePatterns, pair.includeHidden)
        val (originRoot, destRoot) = when (mode) {
            SyncMode.SYNC -> pair.source to pair.target
            SyncMode.RESTORE -> pair.target to pair.source
        }

        val origin = enumerate(originRoot, filter, onProgress)
        val dest = enumerate(destRoot, filter, onProgress)

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

    private fun differs(a: BasicFileAttributes, b: BasicFileAttributes): Boolean {
        if (a.size() != b.size()) return true
        val delta = Duration.between(a.lastModifiedTime().toInstant(), b.lastModifiedTime().toInstant()).abs()
        return delta > updateThreshold
    }

    private fun enumerate(root: Path, filter: ExcludeFilter, onProgress: (Path) -> Unit): Tree {
        val files = HashMap<Path, Path>()
        val attributes = HashMap<Path, BasicFileAttributes>()
        val dirs = HashSet<Path>()

        walker.walk(root) { path ->
            if (path == root) return@walk Visit.CONTINUE
            val rel = root.relativize(path)
            if (filter.excludes(rel)) {
                return@walk if (path.isDirectory()) Visit.SKIP_SUBTREE else Visit.CONTINUE
            }
            onProgress(path)
            when {
                path.isDirectory() -> dirs.add(rel)
                path.isRegularFile() -> {
                    val attrs = runCatching { path.readAttributes<BasicFileAttributes>() }.getOrNull()
                    if (attrs != null) {
                        files[rel] = path
                        attributes[rel] = attrs
                    } // unreadable → treat as absent
                }
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
