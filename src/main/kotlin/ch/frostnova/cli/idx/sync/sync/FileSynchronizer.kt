package ch.frostnova.cli.idx.sync.sync

import ch.frostnova.cli.idx.sync.core.FileChange
import ch.frostnova.cli.idx.sync.core.SyncAction
import ch.frostnova.cli.idx.sync.core.SyncResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile

/** Observes synchronization progress. Default no-op; the console UI supplies a real implementation. */
interface SyncListener {
    fun onChangeStart(change: FileChange, index: Int, total: Int) {}

    /** Incremental bytes copied for the current CREATE/UPDATE change. */
    fun onBytes(bytesCopied: Long) {}
    fun onChangeDone(change: FileChange, action: SyncAction) {}

    companion object {
        val NONE = object : SyncListener {}
    }
}

/**
 * Applies a list of [FileChange]s to disk and reports the aggregate [SyncResult].
 *
 * Safety guarantees:
 *  - every CREATE/UPDATE goes through [AtomicFileWriter], so an abort never corrupts a target;
 *  - a **0-byte or unreadable source is skipped**, never written — protecting a good backup target from a
 *    truncated/locked source (the core "safe backup" requirement);
 *  - failures on individual changes are collected and do not abort the run.
 */
class FileSynchronizer(private val writer: AtomicFileWriter = AtomicFileWriter()) {

    /**
     * Apply [changes]. [protectedRoots] are directories that must be treated as strictly **read-only** —
     * no file under them may ever be written, deleted or moved (in a normal sync these are the source
     * roots). Any change whose destination falls within a protected root is refused with an error rather
     * than executed: a hard safety net for the "never touch the source" guarantee.
     */
    fun sync(
        changes: List<FileChange>,
        protectedRoots: List<Path> = emptyList(),
        listener: SyncListener = SyncListener.NONE,
    ): SyncResult {
        val protected = protectedRoots.map { realLocation(it) }
        // The read-only guard resolves symlinks in each destination via `toRealPath` — a syscall. Files in
        // the same directory resolve to the same real parent, so we cache it per parent directory: with many
        // files under a handful of folders this turns one `toRealPath` per file into one per directory.
        val realParentCache = HashMap<Path, Path>()
        var result = SyncResult.EMPTY
        changes.forEachIndexed { index, change ->
            listener.onChangeStart(change, index, changes.size)
            result += apply(change, protected, realParentCache, listener)
        }
        return result
    }

    private fun apply(
        change: FileChange,
        protected: List<Path>,
        realParentCache: MutableMap<Path, Path>,
        listener: SyncListener,
    ): SyncResult {
        if (change.action != SyncAction.SKIP && isProtected(change.destination, protected, realParentCache)) {
            return SyncResult(errors = listOf("refused: ${change.destination} is within a read-only source"))
        }
        return when (change.action) {
            SyncAction.CREATE, SyncAction.UPDATE -> copy(change, listener)
            SyncAction.DELETE -> delete(change, listener)
            SyncAction.SKIP -> SyncResult(skipped = 1)
        }
    }

    private fun isProtected(
        destination: Path,
        protected: List<Path>,
        realParentCache: MutableMap<Path, Path>,
    ): Boolean {
        if (protected.isEmpty()) return false
        // Fast path: resolve the (cached) real *parent* and re-append the file name. Once the parent's
        // symlinks are resolved, appending a plain leaf gives the same `startsWith` answer as resolving the
        // whole path — with far fewer `toRealPath` syscalls when many files share a directory. This is only
        // valid when the leaf itself isn't a symlink; if it is (e.g. deleting a target-side link that points
        // into the source), or the destination has no parent, fall back to resolving the full path.
        val parent = destination.parent
        val dest = if (parent != null && !Files.isSymbolicLink(destination)) {
            realParentCache.getOrPut(parent) { realLocation(parent) }.resolve(destination.fileName)
        } else {
            realLocation(destination)
        }
        return protected.any { dest.startsWith(it) }
    }

    /**
     * The **physical** location a path points at: symbolic links in the already-existing portion of the
     * path are resolved (via [Path.toRealPath]), while any not-yet-created tail is kept lexically. This
     * makes the read-only guard robust against a symlinked directory that would otherwise disguise a write
     * or delete landing inside a protected source (a purely lexical `normalize()` would miss it).
     */
    private fun realLocation(path: Path): Path {
        var existing = path.toAbsolutePath()
        val tail = ArrayDeque<Path>()
        while (existing.parent != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing.fileName?.let { tail.addFirst(it) }
            existing = existing.parent
        }
        var real = runCatching { existing.toRealPath() }.getOrElse { existing.normalize() }
        for (segment in tail) real = real.resolve(segment)
        return real.normalize()
    }

    private fun copy(change: FileChange, listener: SyncListener): SyncResult {
        val source = change.origin
        val unsafe = unsafeReason(source, change.destination)
        if (unsafe != null) {
            listener.onChangeDone(change, SyncAction.SKIP)
            return SyncResult(skipped = 1, warnings = listOf("skipped ${change.relativePath}: $unsafe"))
        }
        return try {
            writer.write(source, change.destination) { listener.onBytes(it) }
            listener.onChangeDone(change, change.action)
            val bytes = runCatching { source.fileSize() }.getOrDefault(change.size)
            when (change.action) {
                SyncAction.CREATE -> SyncResult(created = 1, bytesTransferred = bytes)
                else -> SyncResult(updated = 1, bytesTransferred = bytes)
            }
        } catch (ex: Exception) {
            SyncResult(errors = listOf(describe(change, ex)))
        }
    }

    private fun delete(change: FileChange, listener: SyncListener): SyncResult = try {
        deleteRecursively(change.destination)
        listener.onChangeDone(change, SyncAction.DELETE)
        SyncResult(deleted = 1)
    } catch (ex: Exception) {
        SyncResult(errors = listOf(describe(change, ex)))
    }

    /**
     * Returns a human-readable reason the [source] is unsafe to copy over [destination], or `null` when it
     * is safe. A 0-byte source is only refused when the destination already holds real (non-empty) data —
     * a genuinely empty file is still backed up when the target is absent or itself empty.
     */
    private fun unsafeReason(source: Path, destination: Path): String? = when {
        !source.exists() -> "source no longer exists"
        !source.isRegularFile() -> "source is not a regular file"
        !source.isReadable() -> "source is not readable"
        source.fileSize() == 0L && hasContent(destination) -> "source is 0 bytes (protecting non-empty target)"
        else -> null
    }

    private fun hasContent(path: Path): Boolean =
        path.exists() && path.isRegularFile() && runCatching { path.fileSize() > 0L }.getOrDefault(false)

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { Files.delete(file) }

            override fun postVisitDirectory(dir: Path, exc: IOException?) =
                java.nio.file.FileVisitResult.CONTINUE.also { Files.delete(dir) }
        })
    }

    private fun describe(change: FileChange, ex: Exception): String =
        "${change.action.name.lowercase()} ${change.relativePath}: ${ex.javaClass.simpleName}: ${ex.message}"
}
