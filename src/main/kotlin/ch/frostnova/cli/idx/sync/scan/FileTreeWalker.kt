package ch.frostnova.cli.idx.sync.scan

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isSymbolicLink

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
 * path — directories included, in pre-order — is passed to the visitor, which decides whether to descend.
 *
 * Single-threaded on purpose: the original tool's parallel walker added complexity and confusing output
 * without a real speedup, since scanning is I/O-bound on a single device.
 */
class FileTreeWalker {

    /**
     * Walk the tree rooted at [start]. [onVisit] is invoked for [start] and every descendant; returning
     * [Visit.SKIP_SUBTREE] prunes a directory. [onError] is notified of per-directory I/O errors (default:
     * ignore) and the walk continues.
     */
    fun walk(start: Path, onError: (Path, IOException) -> Unit = { _, _ -> }, onVisit: (Path) -> Visit) {
        val decision = onVisit(start)
        if (decision == Visit.SKIP_SUBTREE) return
        if (!start.isDirectory() || start.isSymbolicLink() || !start.isReadable()) return

        val children: List<Path> = try {
            Files.newDirectoryStream(start).use { it.sorted() }
        } catch (_: AccessDeniedException) {
            emptyList()
        } catch (ex: IOException) {
            onError(start, ex)
            emptyList()
        }
        for (child in children) {
            walk(child, onError, onVisit)
        }
    }
}
