package ch.frostnova.cli.idx.sync.ui

import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncPair
import ch.frostnova.cli.idx.sync.core.SyncResult
import ch.frostnova.cli.idx.sync.scan.DiscoveredMarker
import ch.frostnova.cli.idx.sync.sync.SyncListener
import com.github.ajalt.mordant.animation.progress.advance
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
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
 * The console front-end, built on Mordant. Colours mirror the original tool's 256-colour scheme; Mordant
 * auto-detects terminal capabilities and degrades gracefully (colours where supported, plain text — incl.
 * Windows — otherwise). Progress bars use (almost) the full terminal width with a safe margin so they never
 * wrap, and transient progress is cleared when done, leaving only the results / report.
 */
class ConsoleUi(val terminal: Terminal = Terminal()) {

    // Palette (user-picked), semantically mapped: blue = scan/sync, green = create/success,
    // yellow = update, orange = delete, red = error, cyan = ids/accents, gray = muted.
    private val blue = TextColors.rgb("9e9e9e")
    private val green = TextColors.rgb("7ee787")
    private val yellow = TextColors.rgb("f5c542")
    private val orange = TextColors.rgb("fb923c")
    private val red = TextColors.rgb("ef4444")
    private val cyan = TextColors.rgb("5fafff")
    private val gray = TextColors.rgb("9e9e9e")

    // ---- static output --------------------------------------------------------------------------------

    fun logo() {
        terminal.println(blue("------------"))
        terminal.println((bold + blue)("$ROCKET Idx SYNC"))
        terminal.println((bold + blue)("------------"))
    }

    /** Usage text, in the original tool's style (command names + their arguments). */
    fun usage() {
        line("Usage: ${(bold + cyan)("idx-sync [command] [args]...")}")
        line("Commands:")
        cmd("scan", "", "Scan for sync files and show matching pairs")
        cmd("diff", "", "Scan for sync files, compare matching pairs and report changes")
        cmd("sync", "[source-folder-id]", "synchronize all pairs, or only the given source folder")
        cmd("source", "[path] [name]", "add the given path as a source with the given name")
        cmd("target", "[path] [source-folder-id]", "add the given path as a target for the source with the given id")
        cmd("remove", "[path]", "remove the given path as source or target folder (deletes the .idxsync file)")
        cmd("restore", "[source-folder-id]", "restore the given source folder from its target (asks first, never deletes)")
        cmd("demo", "[duration]", "simulate a run to showcase the UI, e.g. demo 15s")
    }

    private fun cmd(name: String, args: String, description: String) {
        val head = (bold + green)(name.padEnd(7))
        val argText = if (args.isEmpty()) "" else " ${gray(args)}"
        line("- $head$argText: $description")
    }

    fun line(text: String) = terminal.println(text)
    fun info(text: String) = terminal.println(text)
    fun success(text: String) = terminal.println(green(text))
    fun warn(text: String) = terminal.println(yellow(text))
    fun error(text: String) = terminal.println("$ERROR ${red(text)}")
    fun blank() = terminal.println()

    /** List the discovered markers, in the original tool's format. */
    fun listFoundMarkers(markers: List<DiscoveredMarker>) {
        if (markers.isEmpty()) {
            line("No .idxsync files found.")
            return
        }
        line("Found following .idxsync files:")
        markers.sortedWith(compareBy({ it.file.isTarget }, { it.file.folderName ?: "" })).forEach { m ->
            val id = (bold + cyan)(m.file.folderId ?: "?")
            if (m.file.isTarget) {
                line("- $SYNC $id: source = ${cyan(m.file.sourceFolderId ?: "?")} in ${m.dir}")
            } else {
                line("- $SYNC $id: ${cyan(m.file.folderName ?: "")}, in ${m.dir}")
            }
        }
    }

    /** List the resolved sync pairs, in the original tool's format. */
    fun listMatchingPairs(pairs: List<SyncPair>) {
        if (pairs.isEmpty()) {
            line("No matching sync folders found.")
            return
        }
        line("Matching sync folders found:")
        pairs.forEach { p -> line("- $CHECK ${(bold + green)(p.name)} ${p.source} -> ${p.target}") }
    }

    /** Report overlapping pairs (source/target nested) that are skipped. */
    fun listOverlappingPairs(pairs: List<SyncPair>) {
        if (pairs.isEmpty()) return
        line(orange("Overlapping folder pairs (skipped — source and target overlap):"))
        pairs.forEach { p -> line(orange("- $WARN ${(bold + orange)(p.name)} ${p.source} <-> ${p.target}")) }
    }

    /** Detailed per-file change listing (used by `diff`), coloured by action. */
    fun listChanges(changes: List<ch.frostnova.cli.idx.sync.core.FileChange>) {
        changes.filter { it.action == ch.frostnova.cli.idx.sync.core.SyncAction.CREATE }
            .forEach { line(green("+ ${it.relativePath} [${formatBytes(it.size)}]")) }
        changes.filter { it.action == ch.frostnova.cli.idx.sync.core.SyncAction.UPDATE }
            .forEach { line(yellow("* ${it.relativePath} [${formatBytes(it.size)}]")) }
        changes.filter { it.action == ch.frostnova.cli.idx.sync.core.SyncAction.DELETE }
            .forEach { line(orange("- ${it.relativePath}")) }
    }

    /** One-line summary of pending changes, in the original tool's phrasing. */
    fun pendingChanges(created: Int, updated: Int, deleted: Int) {
        val parts = buildList {
            if (created > 0) add("${(bold + green)("$created")} files created")
            if (updated > 0) add("${(bold + yellow)("$updated")} files updated")
            if (deleted > 0) add("${(bold + orange)("$deleted")} files deleted")
        }
        if (parts.isEmpty()) line("No changes since last sync.")
        else line("Changes since last sync: ${parts.joinToString(", ")}")
    }

    /** The final report that replaces the (cleared) copy progress bar. */
    fun report(mode: SyncMode, result: SyncResult, elapsedSeconds: Double) {
        val title = if (mode == SyncMode.RESTORE) "Restore result" else "Sync result"
        if (result.isEmpty) {
            line("$CHECK ${(bold + green)("Done")}, everything already up to date (${formatDuration(elapsedSeconds)}).")
            return
        }
        line("${(bold + blue)(title)}:")
        if (result.created > 0) line("- ${(bold + green)("${result.created}")} files created")
        if (result.updated > 0) line("- ${(bold + yellow)("${result.updated}")} files updated")
        if (result.deleted > 0) line("- ${(bold + orange)("${result.deleted}")} files deleted")
        if (result.skipped > 0) line("- ${(bold + yellow)("${result.skipped}")} files skipped")
        if (result.bytesTransferred > 0) line("- ${(bold + yellow)(formatBytes(result.bytesTransferred))} transferred")
        result.warnings.forEach { warn("  ! $it") }
        result.errors.forEach { line("  ${red("$ERROR $it")}") }
        line("- done in ${(bold + cyan)(formatDuration(elapsedSeconds))}")
    }

    // ---- progress -------------------------------------------------------------------------------------

    /** Indeterminate phase (comparing) with a spinner; cleared when done. */
    fun <T> spinner(title: String, block: (setDetail: (String) -> Unit) -> T): T {
        val layout = progressBarContextLayout<String> {
            spinner(Spinner.Dots(style = cyan))
            text(align = TextAlign.LEFT) { context }
        }
        val anim = layout.animateOnThread(terminal, title, null)
        val future = anim.execute()
        return try {
            block { detail -> anim.update { context = "$title  " + gray(ellipsize(detail, 60)) } }
        } finally {
            anim.stop(); runCatching { future.get() }; runCatching { anim.clear() }
        }
    }

    /** Determinate phase reporting a 0..1 fraction (scanning); a full-width bar, cleared when done. */
    fun <T> fractionProgress(title: String, block: (report: (Double, Any) -> Unit) -> T): T {
        val info = 40
        val layout = progressBarContextLayout<String> {
            text(align = TextAlign.LEFT) { context }
            // No explicit width -> the bar expands to fill the remaining terminal width.
            progressBar(completeStyle = blue, finishedStyle = blue)
            percentage()
        }
        val anim = layout.animateOnThread(terminal, title, TICKS)
        val future = anim.execute()
        return try {
            block { fraction, detail ->
                anim.update {
                    completed = (fraction.coerceIn(0.0, 1.0) * TICKS).toLong()
                    context = ellipsize("$title  $detail", info)
                }
            }
        } finally {
            anim.update { completed = TICKS }
            anim.stop(); runCatching { future.get() }; runCatching { anim.clear() }
        }
    }

    /**
     * The copy phase: a (near) full-width, byte-accurate progress bar (bar · % · speed · ETA). Cleared when
     * done so the caller can print the report in its place. Returns the caller's [SyncResult].
     */
    fun copyProgress(totalBytes: Long, run: (SyncListener) -> SyncResult): SyncResult {
        val info = 24
        val layout = progressBarContextLayout<String> {
            text(align = TextAlign.LEFT) { context }
            // No explicit width -> the bar expands to fill the remaining terminal width.
            progressBar(completeStyle = blue, finishedStyle = blue)
            percentage()
            speed("B/s")
            timeRemaining()
        }
        val total = totalBytes.coerceAtLeast(1)
        val anim = layout.animateOnThread(terminal, "", total)
        val future = anim.execute()
        val listener = object : SyncListener {
            override fun onChangeStart(
                change: ch.frostnova.cli.idx.sync.core.FileChange,
                index: Int,
                total: Int,
            ) {
                anim.update { context = ellipsize(change.relativePath.toString(), info) }
            }

            override fun onBytes(bytesCopied: Long) = anim.advance(bytesCopied)
        }
        return try {
            run(listener)
        } finally {
            anim.update { completed = total }
            anim.stop(); runCatching { future.get() }; runCatching { anim.clear() }
        }
    }

    /** Ask a yes/no question on the same line; defaults to No (safe) on empty/non-interactive input. */
    fun confirm(question: String): Boolean {
        terminal.print(yellow("$question (y/N) "))
        val answer = runCatching { readlnOrNull() }.getOrNull()?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private fun ellipsize(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text.padEnd(maxLen)
        return ("…" + text.takeLast(maxLen - 1))
    }

    private operator fun TextStyle.invoke(value: Any): String = this(value.toString())

    companion object {
        private const val TICKS = 10_000L
        private const val ROCKET = "🚀" // 🚀
        private const val SYNC = "🔄"   // 🔄
        private const val CHECK = "✅"        // ✅
        private const val ERROR = "❌"        // ❌
        private const val WARN = "⚠"          // ⚠
    }
}
