package ch.frostnova.cli.idx.sync.cli

import ch.frostnova.cli.idx.sync.config.IdxSyncFile
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class CommandsTest {

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
