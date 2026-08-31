package ch.frostnova.cli.idx.sync.scan

import ch.frostnova.cli.idx.sync.config.IdxSyncFile
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import ch.frostnova.cli.idx.sync.filter.PlatformExcludes
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name

/** A discovered `.idxsync` marker together with the folder it marks. */
data class DiscoveredMarker(val file: IdxSyncFile, val dir: Path)

/**
 * Scans the file system for `.idxsync` marker files, like the original tool: a **quick, shallow** sweep of
 * the filesystem roots *and* the current working directory, down to a bounded depth. Removable media and
 * home folders are shallow, so a small [maxDepth] finds markers fast without walking entire disks.
 *
 * Traversal skips platform-excluded and hidden directories, symbolic links, pseudo filesystems, and
 * unreadable folders. Progress (0..1) and the current directory are reported for a progress bar.
 */
class SyncFolderScanner(
    private val repository: IdxSyncFileRepository = IdxSyncFileRepository(),
    private val platformExcludes: PlatformExcludes = PlatformExcludes.ALL,
    private val maxDepth: Int = 5,
) {
    /**
     * Scan [roots] (default: filesystem roots + the current directory). [onProgress] receives a rising
     * fraction and the directory currently being visited. Returns all discovered markers.
     */
    fun scan(
        roots: List<Path> = defaultRoots(),
        onProgress: (fraction: Double, dir: Path) -> Unit = { _, _ -> },
    ): List<DiscoveredMarker> {
        val result = mutableListOf<DiscoveredMarker>()
        val n = roots.size.coerceAtLeast(1)
        roots.forEachIndexed { i, root ->
            traverse(root, root.nameCount, i.toDouble() / n, (i + 1).toDouble() / n, result, onProgress)
        }
        onProgress(1.0, roots.lastOrNull() ?: Path.of("."))
        // The current dir and a filesystem root can reach the same folder — keep each marker once.
        return result.distinctBy { it.dir.toAbsolutePath().normalize() }
    }

    private fun traverse(
        dir: Path,
        baseDepth: Int,
        lower: Double,
        upper: Double,
        result: MutableList<DiscoveredMarker>,
        onProgress: (Double, Path) -> Unit,
    ) {
        onProgress(lower, dir)
        repository.readOrNull(dir)?.let { result += DiscoveredMarker(it, dir) }

        if (dir.nameCount - baseDepth >= maxDepth) return

        val children = childDirs(dir)
        val count = children.size
        if (count == 0) return
        children.forEachIndexed { idx, child ->
            val lo = lower + (upper - lower) * idx / count
            val hi = lower + (upper - lower) * (idx + 1) / count
            traverse(child, baseDepth, lo, hi, result, onProgress)
        }
    }

    private fun childDirs(dir: Path): List<Path> = try {
        Files.newDirectoryStream(dir).use { stream ->
            stream.filter { child ->
                runCatching {
                    child.isDirectory() && child.isReadable() && !child.isSymbolicLink() &&
                        !isIgnoredSystemPath(child) && !isPrunable(child.name)
                }.getOrDefault(false)
            }.sorted()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun isPrunable(name: String): Boolean =
        platformExcludes.excludes(name) || (name.startsWith(".") && name.length > 1)

    private fun isIgnoredSystemPath(dir: Path): Boolean =
        dir.toString() in IGNORED_ABSOLUTE_PATHS

    companion object {
        private val IGNORED_ABSOLUTE_PATHS = setOf("/dev", "/proc", "/sys", "/run")

        fun fileSystemRoots(): List<Path> =
            FileSystems.getDefault().rootDirectories.toList()

        /** Filesystem roots plus the current working directory (deduplicated). */
        fun defaultRoots(): List<Path> {
            val current = runCatching { Path.of("").toAbsolutePath().normalize() }.getOrNull()
            return (fileSystemRoots() + listOfNotNull(current)).distinct()
        }
    }
}
