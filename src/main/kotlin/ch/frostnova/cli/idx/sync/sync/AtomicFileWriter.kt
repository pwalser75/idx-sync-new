package ch.frostnova.cli.idx.sync.sync

import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
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
class AtomicFileWriter(
    private val bufferSize: Int = 1 shl 20,
    /** When true, the bytes written to disk are hashed and compared against the source before the rename. */
    private val verify: Boolean = false,
) {

    /**
     * Replace [destination] with the contents of [source], preserving the source's last-modified time.
     * [onBytes] receives incremental byte counts as the copy progresses. Throws on I/O failure (leaving
     * the original [destination] intact).
     */
    fun write(source: Path, destination: Path, onBytes: (Long) -> Unit = {}) {
        val lastModified = source.readAttributes<BasicFileAttributes>().lastModifiedTime()
        // Unbuffered stream + a large copy buffer: no redundant double-buffering, fewer syscalls.
        Files.newInputStream(source).use { input ->
            write(input, destination, lastModified, onBytes)
        }
    }

    /**
     * Stream-based seam (also the real implementation of the [Path] overload): copy [input] into
     * [destination] using the crash-safe temp→backup→rename scheme, stamping [lastModified] on the result.
     * If [input] fails partway, the original [destination] is untouched and no temp files remain.
     */
    @SuppressWarnings("kotlin:S3776")
    internal fun write(input: InputStream, destination: Path, lastModified: FileTime, onBytes: (Long) -> Unit = {}) {
        val dir = destination.parent
        if (dir != null) Files.createDirectories(dir)

        val temp = sibling(destination, TEMP_SUFFIX)
        val backup = sibling(destination, BACKUP_SUFFIX)

        try {
            // 1. stream into the temp file (an abort here never touches the target)
            temp.deleteIfExists()
            val digest = if (verify) MessageDigest.getInstance("SHA-256") else null
            FileOutputStream(temp.toFile()).use { output ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (read > 0) {
                        digest?.update(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                }
                output.flush()
                // force the bytes to the physical disk before we rely on the rename — a power loss after the
                // rename must never surface a target that exists but was never fully persisted.
                output.fd.sync()
            }
            Files.setLastModifiedTime(temp, lastModified)

            // 1b. optional integrity check: the file now on disk must match the bytes we streamed
            if (digest != null) verifyMatches(temp, digest.digest())

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

    /** Re-read [temp] and confirm its content hashes to [expected]; throws if the written copy differs. */
    private fun verifyMatches(temp: Path, expected: ByteArray) {
        val actual = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(temp).use { input ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) actual.update(buffer, 0, read)
            }
        }
        if (!MessageDigest.isEqual(expected, actual.digest())) {
            throw IOException("verification failed: the written copy of ${temp.name} does not match the source")
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
