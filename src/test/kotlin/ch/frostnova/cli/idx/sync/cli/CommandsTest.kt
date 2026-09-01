package ch.frostnova.cli.idx.sync.cli

import ch.frostnova.cli.idx.sync.config.IdxSyncFile
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import ch.frostnova.cli.idx.sync.ui.ConsoleUi
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

class CommandsTest {

    private val repo = IdxSyncFileRepository()

    /** Parse `pair <source> <target>` through the real root command, as `main` wires it. */
    private fun pair(source: Path, target: Path) {
        IdxSync(ConsoleUi()).subcommands(PairCommand(ConsoleUi(), repo))
            .parse(listOf("pair", source.toString(), target.toString()))
    }

    @Test
    fun `pair marks a fresh source and its target`(@TempDir root: Path) {
        val source = root.resolve("docs").createDirectories()
        val target = root.resolve("usb/docs").createDirectories()

        pair(source, target)

        val src = repo.readOrNull(source)!!
        val tgt = repo.readOrNull(target)!!
        assertThat(src.isSource).isTrue()
        assertThat(src.folderName).isEqualTo("docs")
        assertThat(tgt.isTarget).isTrue()
        assertThat(tgt.sourceFolderId).isEqualTo(src.folderId)
    }

    @Test
    fun `pair reuses an existing source's folder-id`(@TempDir root: Path) {
        val source = root.resolve("s").createDirectories()
        val target = root.resolve("t").createDirectories()
        repo.write(source, IdxSyncFile(folderId = "existing-id", folderName = "Keep Me"))

        pair(source, target)

        val src = repo.readOrNull(source)!!
        assertThat(src.folderId).isEqualTo("existing-id")
        assertThat(src.folderName).isEqualTo("Keep Me")
        assertThat(repo.readOrNull(target)!!.sourceFolderId).isEqualTo("existing-id")
    }

    @Test
    fun `pair updates an existing target to point at the source`(@TempDir root: Path) {
        val source = root.resolve("s").createDirectories()
        val target = root.resolve("t").createDirectories()
        repo.write(target, IdxSyncFile(folderId = "target-id", sourceFolderId = "some-old-source"))

        pair(source, target)

        val tgt = repo.readOrNull(target)!!
        assertThat(tgt.folderId).isEqualTo("target-id") // own id preserved
        assertThat(tgt.sourceFolderId).isEqualTo(repo.readOrNull(source)!!.folderId)
    }

    @Test
    fun `parses durations`() {
        assertThat(parseDurationMillis("15s")).isEqualTo(15_000)
        assertThat(parseDurationMillis("2m")).isEqualTo(120_000)
        assertThat(parseDurationMillis("500ms")).isEqualTo(500)
        assertThat(parseDurationMillis("1h")).isEqualTo(3_600_000)
        assertThat(parseDurationMillis("10")).isEqualTo(10_000)
        assertThat(parseDurationMillis("1.5s")).isEqualTo(1_500)
    }

    @Test
    fun `rejects invalid durations`() {
        assertThatThrownBy { parseDurationMillis("soon") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { parseDurationMillis("5 weeks") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `written marker does not leak computed properties`(@TempDir dir: Path) {
        val repo = IdxSyncFileRepository()
        val path = repo.write(dir, IdxSyncFile(folderId = "x", folderName = "n"))
        val yaml = path.readText()
        assertThat(yaml).doesNotContain("isSource").doesNotContain("isTarget")
    }
}
