package ch.frostnova.cli.idx.sync.sync

import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
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
     *
     * When not verifying, the bytes are moved with [FileChannel.transferTo] — a kernel-side copy (e.g.
     * `copy_file_range`/`sendfile` on Linux) that avoids pulling every byte through a userspace buffer,
     * so it is typically faster and does fewer syscalls than a read/write loop. Verify mode still streams
     * through userspace, because the integrity hash needs to see the bytes.
     */
    fun write(source: Path, destination: Path, onBytes: (Long) -> Unit = {}) {
        val lastModified = source.readAttributes<BasicFileAttributes>().lastModifiedTime()
        if (verify) {
            // Unbuffered stream + a large copy buffer: no redundant double-buffering, fewer syscalls.
            Files.newInputStream(source).use { input -> write(input, destination, lastModified, onBytes) }
            return
        }
        writeAtomically(destination, lastModified) { temp ->
            FileChannel.open(source, READ).use { src ->
                FileChannel.open(temp, CREATE, WRITE, TRUNCATE_EXISTING).use { dst ->
                    transfer(src, dst, onBytes)
                    // force content + metadata to the physical disk before we rely on the rename — a power
                    // loss after the rename must never surface a target that was never fully persisted.
                    dst.force(true)
                }
            }
        }
    }

    /**
     * Stream-based seam (used for verify mode and by tests): copy [input] into [destination] using the
     * crash-safe temp→backup→rename scheme, stamping [lastModified] on the result. If [input] fails
     * partway, the original [destination] is untouched and no temp files remain.
     */
    internal fun write(input: InputStream, destination: Path, lastModified: FileTime, onBytes: (Long) -> Unit = {}) {
        val digest = if (verify) MessageDigest.getInstance("SHA-256") else null
        writeAtomically(destination, lastModified) { temp ->
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
                output.fd.sync()
            }
            // optional integrity check: the file now on disk must match the bytes we streamed
            if (digest != null) verifyMatches(temp, digest.digest())
        }
    }

    /**
     * The crash-safe orchestration shared by both copy paths: [fill] writes (and fsyncs) the file content
     * into a hidden temp sibling, then the temp is stamped with [lastModified] and atomically swapped into
     * place (old → backup → replace → drop backup). A crash between any two steps leaves either the original
     * [destination] or the fully-written replacement — never a partial file. On any failure the original is
     * restored and no temp/backup files are left behind.
     */
    private fun writeAtomically(destination: Path, lastModified: FileTime, fill: (Path) -> Unit) {
        val dir = destination.parent
        if (dir != null) Files.createDirectories(dir)

        val temp = sibling(destination, TEMP_SUFFIX)
        val backup = sibling(destination, BACKUP_SUFFIX)

        try {
            // 1. write the content into the temp file (an abort here never touches the target)
            temp.deleteIfExists()
            fill(temp)
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

    /**
     * Move all of [src] into [dst] via kernel-side [FileChannel.transferTo], looping because a single call
     * may move only part of the file (and platforms such as Windows cap the per-call size). [onBytes]
     * receives each transferred chunk for progress reporting.
     */
    private fun transfer(src: FileChannel, dst: FileChannel, onBytes: (Long) -> Unit) {
        val size = src.size()
        var position = 0L
        while (position < size) {
            val transferred = src.transferTo(position, size - position, dst)
            if (transferred <= 0L) break // past EOF (e.g. source truncated concurrently) — copy what exists
            position += transferred
            onBytes(transferred)
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
