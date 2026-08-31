package ch.frostnova.cli.idx.sync.sync

import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.readAttributes

/**
 * Writes a destination file **crash-safely** so that aborting mid-copy can never corrupt or lose the
 * existing target. The scheme (per the project brief):
 *
 *  1. stream the source into a hidden temp file *in the same directory* (`.<name>.idxtmp`),
 *  2. if a target already exists, atomically rename it aside to a backup (`.<name>.idxbak`),
 *  3. atomically rename the temp file into place,
 *  4. delete the backup.
 *
 * A crash between any two steps leaves either the original target or the fully-written replacement intact —
 * never a partial file. If step 3 fails after the target was moved aside, the backup is restored. Temp and
 * backup files are named so [ch.frostnova.cli.idx.sync.filter.PlatformExcludes] always ignores them.
 */
class AtomicFileWriter(private val bufferSize: Int = 1 shl 16) {

    /**
     * Replace [destination] with the contents of [source], preserving the source's last-modified time.
     * [onBytes] receives incremental byte counts as the copy progresses. Throws on I/O failure (leaving
     * the original [destination] intact).
     */
    fun write(source: Path, destination: Path, onBytes: (Long) -> Unit = {}) {
        val lastModified = source.readAttributes<BasicFileAttributes>().lastModifiedTime()
        Files.newInputStream(source).buffered(bufferSize).use { input ->
            write(input, destination, lastModified, onBytes)
        }
    }

    /**
     * Stream-based seam (also the real implementation of the [Path] overload): copy [input] into
     * [destination] using the crash-safe temp→backup→rename scheme, stamping [lastModified] on the result.
     * If [input] fails partway, the original [destination] is untouched and no temp files remain.
     */
    internal fun write(input: InputStream, destination: Path, lastModified: FileTime, onBytes: (Long) -> Unit = {}) {
        val dir = destination.parent
        if (dir != null) Files.createDirectories(dir)

        val temp = sibling(destination, TEMP_SUFFIX)
        val backup = sibling(destination, BACKUP_SUFFIX)

        try {
            // 1. stream into the temp file (an abort here never touches the target)
            temp.deleteIfExists()
            Files.newOutputStream(temp).buffered(bufferSize).use { output ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (read > 0) onBytes(read.toLong())
                }
                output.flush()
            }
            Files.setLastModifiedTime(temp, lastModified)

            // 2. move any existing target aside
            val hadTarget = Files.exists(destination)
            if (hadTarget) move(destination, backup)

            // 3. move the temp file into place; recover the backup on failure
            try {
                move(temp, destination)
            } catch (ex: Exception) {
                if (hadTarget) runCatching { move(backup, destination) }
                throw ex
            }

            // 4. drop the backup
            if (hadTarget) backup.deleteIfExists()
        } finally {
            runCatching { temp.deleteIfExists() }
        }
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, REPLACE_EXISTING)
        }
    }

    private fun sibling(path: Path, suffix: String): Path {
        val name = ".${path.name}$suffix"
        return path.parent?.resolve(name) ?: Path.of(name)
    }

    companion object {
        const val TEMP_SUFFIX = ".idxtmp"
        const val BACKUP_SUFFIX = ".idxbak"
    }
}
