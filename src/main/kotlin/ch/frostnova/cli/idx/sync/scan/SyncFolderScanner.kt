package ch.frostnova.cli.idx.sync.scan

import ch.frostnova.cli.idx.sync.config.IdxSyncFile
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import ch.frostnova.cli.idx.sync.filter.PlatformExcludes
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** A discovered `.idxsync` marker together with the folder it marks. */
data class DiscoveredMarker(val file: IdxSyncFile, val dir: Path)

/**
 * Scans the file system for `.idxsync` marker files. Traversal is depth-limited (removable media and home
 * folders are shallow), skips platform-excluded and hidden directories, and skips pseudo filesystems.
 */
class SyncFolderScanner(
    private val repository: IdxSyncFileRepository = IdxSyncFileRepository(),
    private val walker: FileTreeWalker = FileTreeWalker(),
    private val platformExcludes: PlatformExcludes = PlatformExcludes.ALL,
    private val maxDepth: Int = 8,
) {
    /** Scan the given [roots] (defaults to all filesystem roots) and return every discovered marker. */
    fun scan(roots: List<Path> = fileSystemRoots(), onVisit: (Path) -> Unit = {}): List<DiscoveredMarker> {
        val result = mutableListOf<DiscoveredMarker>()
        for (root in roots) {
            val baseDepth = root.nameCount
            walker.walk(root) { path ->
                onVisit(path)
                when {
                    !path.isDirectory() -> Visit.CONTINUE
                    isIgnoredSystemPath(path) -> Visit.SKIP_SUBTREE
                    path != root && isPrunable(path) -> Visit.SKIP_SUBTREE
                    else -> {
                        repository.readOrNull(path)?.let { result += DiscoveredMarker(it, path) }
                        if (path.nameCount - baseDepth >= maxDepth) Visit.SKIP_SUBTREE else Visit.CONTINUE
                    }
                }
            }
        }
        return result
    }

    private fun isPrunable(dir: Path): Boolean {
        val name = dir.name
        return platformExcludes.excludes(name) || (name.startsWith(".") && name.length > 1)
    }

    private fun isIgnoredSystemPath(dir: Path): Boolean =
        dir.toString() in IGNORED_ABSOLUTE_PATHS

    companion object {
        private val IGNORED_ABSOLUTE_PATHS = setOf("/dev", "/proc", "/sys", "/run")

        fun fileSystemRoots(): List<Path> =
            FileSystems.getDefault().rootDirectories.toList()
    }
}
