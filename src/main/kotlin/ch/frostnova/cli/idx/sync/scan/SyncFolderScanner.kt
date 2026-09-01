package ch.frostnova.cli.idx.sync.scan

import ch.frostnova.cli.idx.sync.config.IdxSyncFile
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import ch.frostnova.cli.idx.sync.filter.PlatformExcludes
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

        // At the depth boundary we don't descend, so a full listing would be wasted work on what may be a
        // huge leaf directory — probe the marker directly (two stats) instead.
        if (dir.nameCount - baseDepth >= maxDepth) {
            repository.readOrNull(dir)?.let { result += DiscoveredMarker(it, dir) }
            return
        }

        // One directory pass yields both this folder's marker and its sub-directories, with a single
        // stat (readAttributes) per entry instead of a separate isDirectory/isReadable/isSymbolicLink probe.
        val contents = readContents(dir)
        contents.markerFile?.let { marker ->
            repository.read(marker)?.let { result += DiscoveredMarker(it, dir) }
        }

        val children = contents.subDirs
        val count = children.size
        if (count == 0) return
        children.forEachIndexed { idx, child ->
            val lo = lower + (upper - lower) * idx / count
            val hi = lower + (upper - lower) * (idx + 1) / count
            traverse(child, baseDepth, lo, hi, result, onProgress)
        }
    }

    /** A directory's marker file (if any) and its traversable sub-directories, from a single listing. */
    private class DirContents(val markerFile: Path?, val subDirs: List<Path>)

    private fun readContents(dir: Path): DirContents {
        var marker: Path? = null
        val subDirs = ArrayList<Path>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    val attrs = runCatching {
                        Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    }.getOrNull() ?: continue
                    val name = child.name
                    if (name == IdxSyncFileRepository.FILENAME) {
                        if (!attrs.isDirectory) marker = child
                    } else if (attrs.isDirectory && !attrs.isSymbolicLink &&
                        !isIgnoredSystemPath(child) && !isPrunable(name)
                    ) {
                        subDirs.add(child)
                    }
                }
            }
        } catch (_: Exception) {
            return DirContents(null, emptyList())
        }
        return DirContents(marker, subDirs.sorted())
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
