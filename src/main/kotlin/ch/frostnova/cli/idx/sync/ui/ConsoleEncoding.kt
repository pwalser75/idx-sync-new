package ch.frostnova.cli.idx.sync.ui

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.Charset

/**
 * Makes the console able to render the tool's Unicode output — the emoji status icons and the block-glyph
 * progress bars.
 *
 * Mordant prints through [System.out], whose charset on Windows defaults to the legacy OEM code page
 * (e.g. cp850). Unicode glyphs such as `█`/`░` and the emoji can't be encoded there, so the JVM replaces
 * each with `?` before the bytes ever reach the console (colours are unaffected — ANSI escapes are ASCII).
 *
 * [configure] therefore, best-effort:
 *  1. makes stdout/stderr emit **UTF-8**, and
 *  2. on Windows, switches the console's **output code page to UTF-8** (65001) so those UTF-8 bytes are
 *     decoded correctly instead of as the OEM code page (which would otherwise turn them into mojibake).
 *
 * Must run **before** the [com.github.ajalt.mordant.terminal.Terminal] / [ConsoleUi] is used.
 */
object ConsoleEncoding {

    private const val CODE_PAGE_UTF8 = 65001

    fun configure() {
        // Emit UTF-8 bytes so glyphs aren't replaced with '?'. Only rewrap when the stream isn't already
        // UTF-8 (Linux/macOS usually are), keeping the working platforms untouched.
        runCatching {
            if (streamCharset("sun.stdout.encoding") != Charsets.UTF_8) {
                System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
            }
            if (streamCharset("sun.stderr.encoding") != Charsets.UTF_8) {
                System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
            }
        }
        // Tell the Windows console to interpret our output as UTF-8. No-op elsewhere; never touches JNA on
        // non-Windows (so Linux/macOS need no native library for this). Failure is non-fatal.
        if (isWindows) runCatching { Kernel32.INSTANCE.SetConsoleOutputCP(CODE_PAGE_UTF8) }
    }

    /**
     * The charset the JVM chose for a standard stream, read from its encoding property. This is the
     * Java 17-compatible stand-in for `PrintStream.charset()` (added in Java 18): the launcher sets
     * `sun.std{out,err}.encoding` for a real console and otherwise the stream falls back to `file.encoding`.
     * An unset/unknown name defaults to the platform charset — the same source the JVM itself uses.
     */
    private fun streamCharset(encodingProperty: String): Charset =
        runCatching {
            charset(System.getProperty(encodingProperty) ?: System.getProperty("file.encoding"))
        }.getOrElse { Charset.defaultCharset() }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Minimal Win32 kernel32 binding — just the code-page setter we need. */
    private interface Kernel32 : StdCallLibrary {
        fun SetConsoleOutputCP(wCodePageID: Int): Boolean

        companion object {
            val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)
        }
    }
}
