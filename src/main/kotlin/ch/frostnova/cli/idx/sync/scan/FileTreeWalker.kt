package ch.frostnova.cli.idx.sync.scan

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A visitor's decision for a directory: whether to descend into it. */
enum class Visit {
    /** Descend into this directory (no effect for regular files). */
    CONTINUE,

    /** Do not descend into this directory's children. */
    SKIP_SUBTREE,
}

/**
 * A clean, single-threaded, depth-first file-tree walker. It is symlink-safe (never follows symbolic
 * links, so it cannot loop) and permission-safe (silently skips directories it cannot read). Every visited
 * path — directories included, in pre-order — is passed to the visitor together with its
 * [BasicFileAttributes], which decides whether to descend.
 *
 * The attributes are read **once** per entry (with [LinkOption.NOFOLLOW_LINKS]) and handed to the visitor,
 * so neither the walker nor the visitor needs to re-`stat` the same path to learn its type, size or mtime.
 * On the FAT/exFAT removable media this tool targets, where `stat` dominates the walk, collapsing the
 * several per-entry probes into one is the bulk of the scan cost.
 *
 * Single-threaded on purpose: parallelising the walk *within* one device added complexity and confusing
 * output without a real speedup, since scanning one device is I/O-bound. (Walking the two *sides* of a diff
 * concurrently is a different matter — they live on independent devices — and is done by the caller.)
 */
class FileTreeWalker {

    /**
     * Walk the tree rooted at [start]. [onVisit] is invoked for [start] and every descendant with the
     * path and its attributes; returning [Visit.SKIP_SUBTREE] prunes a directory. [onError] is notified of
     * per-directory I/O errors (default: ignore) and the walk continues. An entry whose attributes cannot
     * be read is skipped.
     */
    fun walk(
        start: Path,
        onError: (Path, IOException) -> Unit = { _, _ -> },
        onVisit: (Path, BasicFileAttributes) -> Visit,
    ) {
        val attrs = readAttributes(start) ?: return
        walkFrom(start, attrs, onError, onVisit)
    }

    private fun walkFrom(
        path: Path,
        attrs: BasicFileAttributes,
        onError: (Path, IOException) -> Unit,
        onVisit: (Path, BasicFileAttributes) -> Visit,
    ) {
        if (onVisit(path, attrs) == Visit.SKIP_SUBTREE) return
        // Never descend into a non-directory or a symbolic link (loop-safe): a symlinked directory reports
        // isDirectory=false here because attributes are read with NOFOLLOW_LINKS.
        if (!attrs.isDirectory || attrs.isSymbolicLink) return

        val children: List<Path> = try {
            Files.newDirectoryStream(path).use { it.sorted() }
        } catch (_: AccessDeniedException) {
            emptyList()
        } catch (ex: DirectoryIteratorException) {
            // An I/O error *during* iteration surfaces as this unchecked exception (wrapping the IOException),
            // not as a plain IOException. Without catching it a single bad directory would abort the whole
            // scan; report it like any other per-directory error and keep walking.
            onError(path, ex.cause as? IOException ?: IOException(ex))
            emptyList()
        } catch (ex: IOException) {
            onError(path, ex)
            emptyList()
        }
        for (child in children) {
            val childAttrs = readAttributes(child) ?: continue
            walkFrom(child, childAttrs, onError, onVisit)
        }
    }

    /** Read a path's attributes once, without following links; `null` if they cannot be read. */
    private fun readAttributes(path: Path): BasicFileAttributes? = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()
}
