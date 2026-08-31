package ch.frostnova.cli.idx.sync.sync

import ch.frostnova.cli.idx.sync.core.FileChange
import ch.frostnova.cli.idx.sync.core.SyncAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
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
    fun `skips a 0-byte source and never destroys the target`(@TempDir dir: Path) {
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

        synchronizer.sync(listOf(change(SyncAction.CREATE, source, dest)), listener)

        assertThat(started).isEqualTo(1)
        assertThat(done).isEqualTo(1)
        assertThat(bytes).isEqualTo(10)
    }
}
