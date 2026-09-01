package ch.frostnova.cli.idx.sync.sync

import ch.frostnova.cli.idx.sync.core.FileChange
import ch.frostnova.cli.idx.sync.core.SyncAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileSynchronizerTest {

    private val synchronizer = FileSynchronizer()

    private fun change(action: SyncAction, origin: Path, destination: Path, size: Long = 0) =
        FileChange(origin.fileName, origin, destination, action, size)

    @Test
    fun `applies create update and delete`(@TempDir dir: Path) {
        val srcNew = dir.resolve("new.txt").apply { writeText("created") }
        val destNew = dir.resolve("t/new.txt")
        val srcUpd = dir.resolve("upd.txt").apply { writeText("fresh") }
        val destUpd = dir.resolve("t/upd.txt").apply { parent.createDirectories(); writeText("stale") }
        val destDel = dir.resolve("t/old.txt").apply { writeText("remove me") }

        val result = synchronizer.sync(
            listOf(
                change(SyncAction.CREATE, srcNew, destNew),
                change(SyncAction.UPDATE, srcUpd, destUpd),
                change(SyncAction.DELETE, srcNew, destDel),
            ),
        )

        assertThat(destNew.readText()).isEqualTo("created")
        assertThat(destUpd.readText()).isEqualTo("fresh")
        assertThat(destDel.exists()).isFalse()
        assertThat(result.created).isEqualTo(1)
        assertThat(result.updated).isEqualTo(1)
        assertThat(result.deleted).isEqualTo(1)
        assertThat(result.errors).isEmpty()
        assertThat(result.bytesTransferred).isEqualTo("created".length + "fresh".length.toLong())
    }

    @Test
    fun `skips a 0-byte source only when the target has real content`(@TempDir dir: Path) {
        val emptySource = dir.resolve("empty.txt").apply { writeText("") }
        val target = dir.resolve("t/empty.txt").apply { parent.createDirectories(); writeText("GOOD BACKUP DATA") }

        val result = synchronizer.sync(listOf(change(SyncAction.UPDATE, emptySource, target)))

        assertThat(target.readText()).isEqualTo("GOOD BACKUP DATA") // untouched!
        assertThat(result.updated).isEqualTo(0)
        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single()).contains("0 bytes")
    }

    @Test
    fun `backs up a genuinely empty file when the target is absent or empty`(@TempDir dir: Path) {
        val emptySource = dir.resolve("empty.txt").apply { writeText("") }
        val absentTarget = dir.resolve("t/empty.txt")

        val created = synchronizer.sync(listOf(change(SyncAction.CREATE, emptySource, absentTarget)))
        assertThat(absentTarget.exists()).isTrue()
        assertThat(absentTarget.readText()).isEmpty()
        assertThat(created.created).isEqualTo(1)
        assertThat(created.skipped).isEqualTo(0)

        // an already-empty target is also fine to overwrite with an empty source
        val emptyTarget = dir.resolve("t2/empty.txt").apply { parent.createDirectories(); writeText("") }
        val again = synchronizer.sync(listOf(change(SyncAction.UPDATE, emptySource, emptyTarget)))
        assertThat(again.skipped).isEqualTo(0)
    }

    @Test
    fun `skips a source that disappeared before copy`(@TempDir dir: Path) {
        val gone = dir.resolve("gone.txt") // never created
        val target = dir.resolve("t/gone.txt").apply { parent.createDirectories(); writeText("keep") }

        val result = synchronizer.sync(listOf(change(SyncAction.CREATE, gone, target)))

        assertThat(result.skipped).isEqualTo(1)
        assertThat(target.readText()).isEqualTo("keep")
    }

    @Test
    fun `recursively deletes a directory`(@TempDir dir: Path) {
        val tree = dir.resolve("t/subtree").apply { createDirectories() }
        tree.resolve("a.txt").writeText("a")
        tree.resolve("deep").createDirectories().resolve("b.txt").writeText("b")

        val result = synchronizer.sync(listOf(change(SyncAction.DELETE, dir.resolve("subtree"), tree)))

        assertThat(tree.exists()).isFalse()
        assertThat(result.deleted).isEqualTo(1)
    }

    @Test
    fun `refuses to write or delete within a protected read-only root`(@TempDir dir: Path) {
        val source = dir.resolve("src").apply { createDirectories() }
        val sourceFile = source.resolve("keep.txt").apply { writeText("PRECIOUS SOURCE DATA") }
        val someSource = dir.resolve("payload.txt").apply { writeText("data") }

        // a (buggy) change that would write/delete inside the protected source must be refused
        val badWrite = FileChange(sourceFile.fileName, someSource, sourceFile, SyncAction.UPDATE, 4)
        val badDelete = FileChange(sourceFile.fileName, someSource, sourceFile, SyncAction.DELETE)

        val result = synchronizer.sync(listOf(badWrite, badDelete), protectedRoots = listOf(source))

        assertThat(sourceFile.readText()).isEqualTo("PRECIOUS SOURCE DATA") // untouched
        assertThat(result.errors).hasSize(2)
        assertThat(result.errors).allMatch { it.contains("read-only source") }
        assertThat(result.created + result.updated + result.deleted).isEqualTo(0)
    }

    @Test
    fun `protects every configured source root, not only the first`(@TempDir dir: Path) {
        val sourceA = dir.resolve("a").apply { createDirectories() }
        val sourceB = dir.resolve("b").apply { createDirectories() }
        val victim = sourceB.resolve("keep.txt").apply { writeText("PRECIOUS") }
        val payload = dir.resolve("payload.txt").apply { writeText("data") }

        val result = synchronizer.sync(
            listOf(FileChange(victim.fileName, payload, victim, SyncAction.UPDATE, 4)),
            protectedRoots = listOf(sourceA, sourceB),
        )

        assertThat(victim.readText()).isEqualTo("PRECIOUS")
        assertThat(result.errors).singleElement().asString().contains("read-only source")
    }

    @Test
    fun `refuses a write that a symlinked destination would land inside a protected source`(@TempDir dir: Path) {
        val source = dir.resolve("src").apply { createDirectories() }
        source.resolve("existing.txt").writeText("PRECIOUS SOURCE DATA")
        val target = dir.resolve("tgt").apply { createDirectories() }
        // A booby-trapped symlink inside the target that resolves into the source.
        val link = target.resolve("link")
        assumeSymlink(link, source)
        val payload = dir.resolve("payload.txt").apply { writeText("attacker") }

        // A CREATE whose lexical path is under the target, but physically lands in the source via the link.
        val sneaky = FileChange(Path.of("link/new.txt"), payload, link.resolve("new.txt"), SyncAction.CREATE, 8)
        val result = synchronizer.sync(listOf(sneaky), protectedRoots = listOf(source))

        assertThat(result.errors).singleElement().asString().contains("read-only source")
        // Nothing was written into the source — not even a temp/backup file.
        assertThat(source.listDirectoryEntries().map { it.fileName.toString() }).containsExactly("existing.txt")
    }

    @Test
    fun `refuses deleting a target-side symlink that points into a protected source`(@TempDir dir: Path) {
        val source = dir.resolve("src").apply { createDirectories() }
        source.resolve("keep.txt").writeText("PRECIOUS SOURCE DATA")
        val target = dir.resolve("tgt").apply { createDirectories() }
        val link = target.resolve("link")
        assumeSymlink(link, source)

        val result = synchronizer.sync(
            listOf(FileChange(Path.of("link"), source, link, SyncAction.DELETE)),
            protectedRoots = listOf(source),
        )

        assertThat(result.errors).singleElement().asString().contains("read-only source")
        assertThat(source.resolve("keep.txt").readText()).isEqualTo("PRECIOUS SOURCE DATA")
        assertThat(source.exists()).isTrue()
    }

    /** Create a symbolic link, or skip the test on platforms/filesystems that don't allow it (e.g. Windows without privilege). */
    private fun assumeSymlink(link: Path, target: Path) {
        val created = runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        assumeTrue(created, "symbolic links not supported in this environment")
    }

    @Test
    fun `notifies the listener of progress`(@TempDir dir: Path) {
        val source = dir.resolve("s.txt").apply { writeText("1234567890") }
        val dest = dir.resolve("d.txt")
        var started = 0
        var bytes = 0L
        var done = 0
        val listener = object : SyncListener {
            override fun onChangeStart(change: FileChange, index: Int, total: Int) { started++ }
            override fun onBytes(bytesCopied: Long) { bytes += bytesCopied }
            override fun onChangeDone(change: FileChange, action: SyncAction) { done++ }
        }

        synchronizer.sync(listOf(change(SyncAction.CREATE, source, dest)), listener = listener)

        assertThat(started).isEqualTo(1)
        assertThat(done).isEqualTo(1)
        assertThat(bytes).isEqualTo(10)
    }
}
