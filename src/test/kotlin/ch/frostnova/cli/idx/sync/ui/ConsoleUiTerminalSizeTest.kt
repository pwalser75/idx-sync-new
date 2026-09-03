package ch.frostnova.cli.idx.sync.ui

import ch.frostnova.cli.idx.sync.core.SyncResult
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.Size
import com.github.ajalt.mordant.terminal.PrintRequest
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalInfo
import com.github.ajalt.mordant.terminal.TerminalInterface
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.TimeMark

/**
 * Regression guard for the frozen-terminal-width bug: Mordant samples the terminal size once when the
 * [Terminal] is constructed and never refreshes it on the JVM. Our terminal is built at start-up, so a sync
 * running later (especially in a web/cloud workspace terminal that reports a stale default at start-up) would
 * otherwise draw its progress bar to that stale width and leave the rest of a now-wider window blank. The UI
 * must re-measure the terminal before each progress phase.
 */
class ConsoleUiTerminalSizeTest {

    /** A terminal that reports a narrow width at construction, then a wide one on every later query. */
    private class GrowingTerminal(val startupWidth: Int, val laterWidth: Int) : TerminalInterface {
        private var queried = false
        override fun getTerminalSize(): Size {
            val width = if (queried) laterWidth else startupWidth
            queried = true
            return Size(width, 24)
        }

        // Mimic the JVM, where the size is never auto-refreshed on print — only an explicit re-measure helps.
        override fun shouldAutoUpdateSize(): Boolean = false

        override fun info(
            ansiLevel: AnsiLevel?,
            hyperlinks: Boolean?,
            outputInteractive: Boolean?,
            inputInteractive: Boolean?,
        ): TerminalInfo = TerminalInfo(
            ansiLevel = AnsiLevel.NONE,
            ansiHyperLinks = false,
            outputInteractive = true, // interactive, so detectSize() consults getTerminalSize()
            inputInteractive = false,
            supportsAnsiCursor = false,
        )

        override fun completePrintRequest(request: PrintRequest) = Unit
        override fun readLineOrNull(hideInput: Boolean): String? = null
        override fun readInputEvent(timeout: TimeMark, mouseTracking: MouseTracking) = null
    }

    @Test
    fun `copyProgress re-measures the terminal so a stale start-up width is not used`() {
        val term = Terminal(terminalInterface = GrowingTerminal(startupWidth = 40, laterWidth = 140))
        assertThat(term.size.width).isEqualTo(40) // width frozen at construction

        val ui = ConsoleUi(term)
        ui.copyProgress(totalBytes = 1000) { SyncResult.EMPTY }

        // The phase refreshed the size, so the bar was laid out against the real (wide) terminal, not 40.
        assertThat(term.size.width).isEqualTo(140)
    }

    @Test
    fun `fractionProgress re-measures the terminal before drawing its bar`() {
        val term = Terminal(terminalInterface = GrowingTerminal(startupWidth = 40, laterWidth = 140))
        val ui = ConsoleUi(term)

        ui.fractionProgress("Scanning") { report -> report(1.0, "done") }

        assertThat(term.size.width).isEqualTo(140)
    }
}
