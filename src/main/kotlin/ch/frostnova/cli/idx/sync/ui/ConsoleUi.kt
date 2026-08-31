package ch.frostnova.cli.idx.sync.ui

import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncResult
import ch.frostnova.cli.idx.sync.sync.SyncListener
import com.github.ajalt.mordant.animation.progress.advance
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightWhite
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarContextLayout
import com.github.ajalt.mordant.widgets.progress.speed
import com.github.ajalt.mordant.widgets.progress.spinner
import com.github.ajalt.mordant.widgets.progress.text
import com.github.ajalt.mordant.widgets.progress.timeRemaining

/**
 * The console front-end, built on Mordant. Mordant auto-detects terminal capabilities and downgrades
 * gracefully (colors on a capable terminal, plain text otherwise) on every platform — including Windows,
 * which the original tool could not colorize. Progress bars expand to the full terminal width, and the
 * copy phase shows an **accurate** ETA because the total byte count is known up front (unlike the original,
 * which discovered files on the fly).
 */
class ConsoleUi(val terminal: Terminal = Terminal()) {

    fun logo() {
        terminal.println()
        terminal.println((bold + brightBlue)("  ┌─────────────────────┐"))
        terminal.println((bold + brightBlue)("  │  ") + (bold + brightWhite)("◆ idx-sync") + (gray)("  backup") + (bold + brightBlue)("  │"))
        terminal.println((bold + brightBlue)("  └─────────────────────┘"))
        terminal.println()
    }

    fun heading(text: String) = terminal.println((bold + brightBlue)("▚ $text"))
    fun info(text: String) = terminal.println("  $text")
    fun bullet(text: String) = terminal.println((gray)("  • ") + text)
    fun step(text: String) = terminal.println((brightCyan)("  → ") + text)
    fun success(text: String) = terminal.println((green)("  ✔ ") + text)
    fun warn(text: String) = terminal.println((yellow)("  ! ") + text)
    fun error(text: String) = terminal.println((red)("  ✖ ") + text)
    fun blank() = terminal.println()

    private val width: Int get() = terminal.size.width

    private fun ellipsize(text: String, reserve: Int = 30): String {
        val max = (width - reserve).coerceAtLeast(12)
        return if (text.length <= max) text else "…" + text.takeLast(max - 1)
    }

    /**
     * Run an indeterminate phase (scanning / comparing — total unknown by nature) with a live spinner that
     * shows the current item. [block] receives a callback to update the displayed detail.
     */
    fun <T> spinner(title: String, block: (setDetail: (String) -> Unit) -> T): T {
        val layout = progressBarContextLayout<String> {
            spinner(Spinner.Dots())
            text(align = TextAlign.LEFT) { context }
        }
        val anim = layout.animateOnThread(terminal, "$title…", null)
        val future = anim.execute()
        return try {
            block { detail -> anim.update { context = (bold)("$title  ") + gray(ellipsize(detail)) } }
        } finally {
            anim.update { context = (green)("✔ ") + title }
            anim.stop()
            runCatching { future.get() }
        }
    }

    /**
     * Run the copy phase with a full-width, byte-accurate progress bar (bar · % · speed · ETA). Returns a
     * [SyncListener] to the caller's [run] block so the synchronizer can drive it.
     */
    fun copyProgress(totalBytes: Long, run: (SyncListener) -> SyncResult): SyncResult {
        val layout = progressBarContextLayout<String> {
            text(align = TextAlign.LEFT) { context }
            progressBar()
            percentage()
            speed("B/s")
            timeRemaining()
        }
        val anim = layout.animateOnThread(terminal, "starting", totalBytes.coerceAtLeast(1))
        val future = anim.execute()
        val listener = object : SyncListener {
            override fun onChangeStart(
                change: ch.frostnova.cli.idx.sync.core.FileChange,
                index: Int,
                total: Int,
            ) {
                anim.update { context = gray("[${index + 1}/$total] ") + ellipsize(change.relativePath.toString()) }
            }

            override fun onBytes(bytesCopied: Long) = anim.advance(bytesCopied)
        }
        return try {
            run(listener)
        } finally {
            anim.update { completed = totalBytes.coerceAtLeast(1) }
            anim.stop()
            runCatching { future.get() }
        }
    }

    /** Print the closing summary block. */
    fun summary(mode: SyncMode, result: SyncResult, elapsedSeconds: Double) {
        blank()
        heading(if (mode == SyncMode.RESTORE) "Restore complete" else "Sync complete")
        if (result.isEmpty) {
            success("Everything already up to date.")
        } else {
            if (result.created > 0) terminal.println((green)("  + ") + "${bold(result.created.toString())} created")
            if (result.updated > 0) terminal.println((brightBlue)("  * ") + "${bold(result.updated.toString())} updated")
            if (result.deleted > 0) terminal.println((yellow)("  - ") + "${bold(result.deleted.toString())} deleted")
            if (result.skipped > 0) terminal.println((yellow)("  ~ ") + "${bold(result.skipped.toString())} skipped")
            if (result.bytesTransferred > 0) {
                terminal.println((brightCyan)("  ⇄ ") + "${bold(formatBytes(result.bytesTransferred))} transferred")
            }
            result.warnings.forEach { warn(it) }
            result.errors.forEach { error(it) }
        }
        blank()
        terminal.println(gray("  finished in ${formatDuration(elapsedSeconds)}"))
        blank()
    }
}
