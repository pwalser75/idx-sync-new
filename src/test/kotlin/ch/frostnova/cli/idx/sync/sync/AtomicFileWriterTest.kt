package ch.frostnova.cli.idx.sync.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

class AtomicFileWriterTest {

    private val writer = AtomicFileWriter()

    @Test
    fun `creates a new destination file`(@TempDir dir: Path) {
        val source = dir.resolve("source.txt").apply { writeText("hello world") }
        val dest = dir.resolve("out/dest.txt")

        writer.write(source, dest)

        assertThat(dest.readText()).isEqualTo("hello world")
    }

    @Test
    fun `replaces an existing destination and preserves last-modified`(@TempDir dir: Path) {
        val mtime = Instant.parse("2026-03-03T10:00:00Z")
        val source = dir.resolve("s.txt").apply { writeText("new"); setLastModifiedTime(FileTime.from(mtime)) }
        val dest = dir.resolve("d.txt").apply { writeText("old original content") }

        writer.write(source, dest)

        assertThat(dest.readText()).isEqualTo("new")
        assertThat(dest.getLastModifiedTime().toInstant().epochSecond).isEqualTo(mtime.epochSecond)
    }

    @Test
    fun `reports byte progress`(@TempDir dir: Path) {
        val source = dir.resolve("s.bin").apply { writeText("x".repeat(1000)) }
        val dest = dir.resolve("d.bin")
        var total = 0L
        writer.write(source, dest) { total += it }
        assertThat(total).isEqualTo(1000)
    }

    @Test
    fun `verifying writer produces a correct copy`(@TempDir dir: Path) {
        val source = dir.resolve("s.txt").apply { writeText("verified content, byte-for-byte") }
        val dest = dir.resolve("out/d.txt")

        AtomicFileWriter(verify = true).write(source, dest)

        assertThat(dest.readText()).isEqualTo("verified content, byte-for-byte")
    }

    @Test
    fun `relaxed-durability writer still copies correctly and preserves last-modified`(@TempDir dir: Path) {
        val mtime = Instant.parse("2026-03-03T10:00:00Z")
        val source = dir.resolve("s.txt").apply { writeText("fast but crash-safe"); setLastModifiedTime(FileTime.from(mtime)) }
        val dest = dir.resolve("out/d.txt")

        AtomicFileWriter(durable = false).write(source, dest)

        assertThat(dest.readText()).isEqualTo("fast but crash-safe")
        assertThat(dest.getLastModifiedTime().toInstant().epochSecond).isEqualTo(mtime.epochSecond)
    }

    @Test
    fun `relaxed-durability abort leaves the original target intact and no temp files behind`(@TempDir dir: Path) {
        val dest = dir.resolve("important.txt").apply { writeText("PRECIOUS ORIGINAL") }
        val failing = object : InputStream() {
            private var n = 0
            override fun read(): Int {
                if (n++ >= 5) throw IOException("simulated abort")
                return 'z'.code
            }
        }

        assertThatThrownBy { AtomicFileWriter(durable = false).write(failing, dest, FileTime.from(Instant.now())) }
            .isInstanceOf(IOException::class.java)

        assertThat(dest.readText()).isEqualTo("PRECIOUS ORIGINAL")
        val litter = dir.listDirectoryEntries().map { it.fileName.toString() }
            .filter { it.endsWith(".idxtmp") || it.endsWith(".idxbak") }
        assertThat(litter).isEmpty()
    }

    @Test
    fun `abort mid-write leaves the original target intact and no temp files behind`(@TempDir dir: Path) {
        val dest = dir.resolve("important.txt").apply { writeText("PRECIOUS ORIGINAL") }

        // a stream that fails partway through the copy, simulating an abort
        val failing = object : InputStream() {
            private var n = 0
            override fun read(): Int {
                if (n++ >= 5) throw IOException("simulated abort")
                return 'z'.code
            }
        }

        assertThatThrownBy { writer.write(failing, dest, FileTime.from(Instant.now())) }
            .isInstanceOf(IOException::class.java)

        assertThat(dest.readText()).isEqualTo("PRECIOUS ORIGINAL")
        val litter = dir.listDirectoryEntries().map { it.fileName.toString() }
            .filter { it.endsWith(".idxtmp") || it.endsWith(".idxbak") }
        assertThat(litter).isEmpty()
    }
}
