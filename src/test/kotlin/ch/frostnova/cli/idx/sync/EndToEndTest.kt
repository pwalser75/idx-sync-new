package ch.frostnova.cli.idx.sync

import ch.frostnova.cli.idx.sync.config.IdxSyncFile
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncResult
import ch.frostnova.cli.idx.sync.diff.DiffEngine
import ch.frostnova.cli.idx.sync.scan.SyncFolderScanner
import ch.frostnova.cli.idx.sync.scan.SyncPairResolver
import ch.frostnova.cli.idx.sync.sync.FileSynchronizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * Full pipeline integration: scan → resolve → diff → synchronize over real temp directories, asserting the
 * observable behavior and the safety guarantees end to end.
 */
class EndToEndTest {

    private val repo = IdxSyncFileRepository()

    private fun fullSync(root: Path, mode: SyncMode = SyncMode.SYNC): SyncResult {
        val markers = SyncFolderScanner(repo).scan(listOf(root))
        val pairs = SyncPairResolver().resolve(markers)
        val changes = pairs.flatMap { DiffEngine().diff(it, mode) }
        val protected = if (mode == SyncMode.SYNC) pairs.map { it.source } else pairs.map { it.target }
        return FileSynchronizer().sync(changes, protectedRoots = protected)
    }

    private fun file(base: Path, rel: String, content: String): Path {
        val p = base.resolve(rel); p.parent?.createDirectories(); p.writeText(content); return p
    }

    private fun setup(root: Path): Pair<Path, Path> {
        val source = root.resolve("source").createDirectories()
        val target = root.resolve("target").createDirectories()
        repo.write(source, IdxSyncFile(folderId = "S", folderName = "Docs", excludePatterns = setOf("skip"), includeHidden = true))
        repo.write(target, IdxSyncFile(folderId = "T", sourceFolderId = "S"))
        return source to target
    }

    @Test
    fun `mirrors source to target, skips excluded, creates empty, deletes obsolete`(@TempDir root: Path) {
        val (source, target) = setup(root)
        file(source, "a.txt", "hello")
        file(source, "sub/b.txt", "world")
        file(source, "skip/ignored.txt", "nope")   // excluded by pattern
        file(source, "empty.txt", "")               // 0-byte, no target -> created empty
        file(target, "obsolete.txt", "remove me")   // not in source -> deleted

        val result = fullSync(root)

        assertThat(target.resolve("a.txt").readText()).isEqualTo("hello")
        assertThat(target.resolve("sub/b.txt").readText()).isEqualTo("world")
        assertThat(target.resolve("skip/ignored.txt").exists()).isFalse()
        assertThat(target.resolve("empty.txt").exists()).isTrue()   // genuinely empty file is backed up
        assertThat(target.resolve("empty.txt").readText()).isEmpty()
        assertThat(target.resolve("obsolete.txt").exists()).isFalse()
        assertThat(result.created).isEqualTo(3)
        assertThat(result.skipped).isEqualTo(0)
        assertThat(result.deleted).isEqualTo(1)
        assertThat(result.errors).isEmpty()

        // no temp/backup litter anywhere under target
        val litter = target.walk().filter { it.fileName.toString().let { n -> n.endsWith(".idxtmp") || n.endsWith(".idxbak") } }.toList()
        assertThat(litter).isEmpty()
    }

    @Test
    fun `the source is never modified during a sync`(@TempDir root: Path) {
        val (source, target) = setup(root)
        file(source, "a.txt", "one")
        file(source, "dir/b.txt", "two")
        file(target, "a.txt", "stale")              // will be updated in target
        file(target, "extra.txt", "obsolete")       // would be deleted — in target only
        file(target, "extra-dir/c.txt", "obsolete") // whole subtree deleted — in target only

        val before = snapshot(source)
        fullSync(root)
        val after = snapshot(source)

        assertThat(after).isEqualTo(before) // identical: no file added, removed, or changed in the source
    }

    /** Map of every regular file under [dir] (relative path -> content), excluding the .idxsync marker. */
    private fun snapshot(dir: Path): Map<String, String> =
        dir.walk().filter { it.isRegularFile() && it.fileName.toString() != ".idxsync" }
            .associate { dir.relativize(it).toString() to it.readText() }

    @Test
    fun `a 0-byte source never destroys an existing good target`(@TempDir root: Path) {
        val (source, target) = setup(root)
        file(source, "a.txt", "")                       // truncated/0-byte source
        file(target, "a.txt", "PREVIOUS GOOD BACKUP")   // valuable existing backup

        val result = fullSync(root)

        assertThat(target.resolve("a.txt").readText()).isEqualTo("PREVIOUS GOOD BACKUP")
        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.warnings).anyMatch { it.contains("0 bytes") }
    }

    @Test
    fun `second run is a no-op, and updates propagate on change`(@TempDir root: Path) {
        val (source, target) = setup(root)
        file(source, "a.txt", "v1")

        assertThat(fullSync(root).created).isEqualTo(1)
        assertThat(fullSync(root).isEmpty).isTrue()     // nothing changed

        file(source, "a.txt", "v2 longer content")      // change it
        val update = fullSync(root)
        assertThat(update.updated).isEqualTo(1)
        assertThat(target.resolve("a.txt").readText()).isEqualTo("v2 longer content")
    }

    @Test
    fun `restore brings back a deleted source file without deleting anything`(@TempDir root: Path) {
        val (source, target) = setup(root)
        file(source, "keep.txt", "keep")
        file(target, "backup-only.txt", "recovered")    // exists only in backup
        file(source, "extra.txt", "still here")          // exists only at source

        val result = fullSync(root, SyncMode.RESTORE)

        assertThat(source.resolve("backup-only.txt").readText()).isEqualTo("recovered") // restored
        assertThat(source.resolve("extra.txt").exists()).isTrue()                        // never deleted
        assertThat(result.deleted).isEqualTo(0)
        assertThat(result.created).isEqualTo(1)
    }
}
