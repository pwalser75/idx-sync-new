package ch.frostnova.cli.idx.sync.diff

import ch.frostnova.cli.idx.sync.core.SyncAction
import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncPair
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

class DiffEngineTest {

    private val engine = DiffEngine()

    private fun file(root: Path, rel: String, content: String, mtime: Instant? = null): Path {
        val path = root.resolve(rel)
        path.parent?.createDirectories()
        path.writeText(content)
        if (mtime != null) path.setLastModifiedTime(FileTime.from(mtime))
        return path
    }

    private fun pair(source: Path, target: Path) = SyncPair("test", source, target)

    private fun byAction(root: Path, target: Path, mode: SyncMode = SyncMode.SYNC) =
        engine.diff(pair(root, target), mode).groupBy { it.action }

    @Test
    fun `detects created updated deleted and unchanged`(@TempDir source: Path, @TempDir target: Path) {
        val t = Instant.parse("2026-01-01T00:00:00Z")
        file(source, "new.txt", "hello")                    // CREATE
        file(source, "same.txt", "identical", t); file(target, "same.txt", "identical", t) // unchanged
        file(source, "changed.txt", "new content", t)       // UPDATE (size differs)
        file(target, "changed.txt", "old", t)
        file(target, "removed.txt", "gone")                 // DELETE

        val changes = byAction(source, target)
        assertThat(changes[SyncAction.CREATE]?.map { it.relativePath.toString() }).containsExactly("new.txt")
        assertThat(changes[SyncAction.UPDATE]?.map { it.relativePath.toString() }).containsExactly("changed.txt")
        assertThat(changes[SyncAction.DELETE]?.map { it.relativePath.toString() }).containsExactly("removed.txt")
        assertThat(changes.keys).doesNotContain(SyncAction.SKIP)
    }

    @Test
    fun `reports scan and compute phases so the UI never looks frozen`(@TempDir source: Path, @TempDir target: Path) {
        file(source, "a.txt", "hello")
        file(target, "old.txt", "gone")

        val statuses = mutableListOf<String>()
        engine.diff(pair(source, target)) { statuses.add(it) }

        // The scan phases label which side is being walked, and a distinct compute phase is announced once
        // the walks are done — otherwise the UI would sit on the last scanned path during the in-memory diff.
        assertThat(statuses).anyMatch { it.startsWith("scanning source:") }
        assertThat(statuses).anyMatch { it.startsWith("scanning target:") }
        assertThat(statuses.last()).startsWith("computing changes")
    }

    @Test
    fun `identical files by size and mtime are not updated`(@TempDir source: Path, @TempDir target: Path) {
        val t = Instant.parse("2026-05-05T12:00:00Z")
        file(source, "a.txt", "content", t)
        file(target, "a.txt", "content", t)
        assertThat(engine.diff(pair(source, target))).isEmpty()
    }

    @Test
    fun `update detected when source is newer regardless of direction`(@TempDir source: Path, @TempDir target: Path) {
        val old = Instant.parse("2026-01-01T00:00:00Z")
        val new = Instant.parse("2026-06-01T00:00:00Z")
        // same size, source newer -> must still be flagged (reference bug: only flagged target-newer)
        file(source, "a.txt", "1234567890", new)
        file(target, "a.txt", "abcdefghij", old)
        val changes = engine.diff(pair(source, target))
        assertThat(changes.single().action).isEqualTo(SyncAction.UPDATE)
    }

    @Test
    fun `deletes a removed subtree at its root only`(@TempDir source: Path, @TempDir target: Path) {
        file(target, "obsolete/a.txt", "x")
        file(target, "obsolete/deep/b.txt", "y")
        file(source, "keep.txt", "z"); file(target, "keep.txt", "z")

        val deletes = engine.diff(pair(source, target)).filter { it.action == SyncAction.DELETE }
        assertThat(deletes.map { it.relativePath.toString() }).containsExactly("obsolete")
    }

    @Test
    fun `restore never deletes and reverses direction`(@TempDir source: Path, @TempDir target: Path) {
        file(target, "backup-only.txt", "restore me")       // present only in backup(target)
        file(source, "source-only.txt", "keep me")          // present only at source

        val changes = engine.diff(pair(source, target), SyncMode.RESTORE)
        // no deletes at source, and the backup-only file is restored (CREATE at source)
        assertThat(changes.map { it.action }).doesNotContain(SyncAction.DELETE)
        val create = changes.single { it.action == SyncAction.CREATE }
        assertThat(create.relativePath.toString()).isEqualTo("backup-only.txt")
        assertThat(create.destination).isEqualTo(source.resolve("backup-only.txt"))
    }

    @Test
    fun `respects exclude patterns and hidden policy`(@TempDir source: Path, @TempDir target: Path) {
        file(source, "keep.txt", "a")
        file(source, "build/out.bin", "b")                  // excluded by pattern
        file(source, ".secret", "c")                        // excluded (hidden, includeHidden=false)
        file(source, "photos/Thumbs.db", "d")               // excluded (platform)

        val p = SyncPair("t", source, target, excludePatterns = setOf("build/**"), includeHidden = false)
        val creates = engine.diff(p).filter { it.action == SyncAction.CREATE }.map { it.relativePath.toString() }
        assertThat(creates).containsExactly("keep.txt")
    }
}
