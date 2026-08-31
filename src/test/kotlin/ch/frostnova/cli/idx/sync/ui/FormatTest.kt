package ch.frostnova.cli.idx.sync.ui

import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncResult
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FormatTest {

    @Test
    fun `formats bytes`() {
        assertThat(formatBytes(0)).isEqualTo("0 B")
        assertThat(formatBytes(512)).isEqualTo("512 B")
        assertThat(formatBytes(1024)).isEqualTo("1.0 KB")
        assertThat(formatBytes(1536)).isEqualTo("1.5 KB")
        assertThat(formatBytes(5L * 1024 * 1024)).isEqualTo("5.0 MB")
        assertThat(formatBytes(3L * 1024 * 1024 * 1024)).isEqualTo("3.0 GB")
    }

    @Test
    fun `formats durations`() {
        assertThat(formatDuration(2.34)).isEqualTo("2.3s")
        assertThat(formatDuration(42.0)).isEqualTo("42s")
        assertThat(formatDuration(90.0)).isEqualTo("1m 30s")
        assertThat(formatDuration(3720.0)).isEqualTo("1h 02m")
    }

    @Test
    fun `renders static output without error`() {
        val ui = ConsoleUi(Terminal(ansiLevel = AnsiLevel.NONE))
        ui.logo()
        ui.usage()
        ui.success("ok")
        ui.warn("careful")
        ui.error("boom")
        ui.listFoundMarkers(emptyList())
        ui.listMatchingPairs(emptyList())
        ui.pendingChanges(created = 2, updated = 1, deleted = 0)
        ui.report(
            SyncMode.SYNC,
            SyncResult(created = 2, updated = 1, deleted = 3, bytesTransferred = 2048, warnings = listOf("skipped x")),
            elapsedSeconds = 1.5,
        )
        ui.report(SyncMode.RESTORE, SyncResult(), elapsedSeconds = 0.2)
    }
}
