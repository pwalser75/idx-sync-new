package ch.frostnova.cli.idx.sync.cli

import ch.frostnova.cli.idx.sync.core.FileChange
import ch.frostnova.cli.idx.sync.core.SyncAction
import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncResult
import ch.frostnova.cli.idx.sync.sync.SyncListener
import ch.frostnova.cli.idx.sync.ui.ConsoleUi
import java.nio.file.Path
import kotlin.random.Random

/**
 * Simulates a full sync run over a fixed wall-clock [totalMillis] to exercise the console UI without
 * touching the file system. Nothing is read or written; every "file" and byte is synthetic.
 */
class DemoRunner(private val ui: ConsoleUi, private val random: Random = Random(42)) {

    fun run(totalMillis: Long) {
        val start = System.nanoTime()

        // ~15% scanning, ~15% comparing, ~70% copying
        val scanMillis = (totalMillis * 0.15).toLong()
        val compareMillis = (totalMillis * 0.15).toLong()
        val copyMillis = totalMillis - scanMillis - compareMillis

        ui.heading("Demo mode — simulating a backup run")
        ui.info("(no files are read or written)")
        ui.blank()

        ui.spinner("Scanning file system") { detail ->
            animate(scanMillis) { detail(randomDir()) }
        }
        ui.success("Found 2 sync pair(s):")
        ui.bullet("Photos:  /media/sd-card/DCIM  →  /mnt/backup/Photos")
        ui.bullet("Documents:  /home/demo/Documents  →  /mnt/backup/Documents")
        ui.blank()

        ui.spinner("Comparing Photos") { detail -> animate(compareMillis / 2) { detail(randomFile()) } }
        ui.spinner("Comparing Documents") { detail -> animate(compareMillis / 2) { detail(randomFile()) } }
        ui.blank()

        val files = syntheticFiles()
        val totalBytes = files.sumOf { it.size }
        ui.step("Pending changes: ${files.count { it.action == SyncAction.CREATE }} to create, " +
            "${files.count { it.action == SyncAction.UPDATE }} to update")
        ui.blank()

        val result = ui.copyProgress(totalBytes) { listener -> simulateCopy(files, copyMillis, listener) }
        ui.summary(SyncMode.SYNC, result, (System.nanoTime() - start) / 1e9)
    }

    private fun simulateCopy(files: List<FileChange>, copyMillis: Long, listener: SyncListener): SyncResult {
        val totalBytes = files.sumOf { it.size }.coerceAtLeast(1)
        var created = 0
        var updated = 0
        files.forEachIndexed { index, file ->
            listener.onChangeStart(file, index, files.size)
            var remaining = file.size
            val chunk = (file.size / 12).coerceAtLeast(1)
            while (remaining > 0) {
                val n = minOf(chunk, remaining)
                listener.onBytes(n)
                remaining -= n
                // spread the copy phase across copyMillis in proportion to bytes
                sleep(copyMillis * n / totalBytes)
            }
            if (file.action == SyncAction.CREATE) created++ else updated++
        }
        return SyncResult(created = created, updated = updated, bytesTransferred = totalBytes)
    }

    private fun syntheticFiles(): List<FileChange> = buildList {
        repeat(18) { i ->
            val action = if (random.nextInt(3) == 0) SyncAction.UPDATE else SyncAction.CREATE
            val size = (200_000L..8_000_000L).random(random)
            val rel = Path.of("${randomFolder()}/${randomBaseName()}.${randomExt()}")
            add(FileChange(rel, Path.of("/src").resolve(rel), Path.of("/dst").resolve(rel), action, size))
        }
    }

    private fun animate(millis: Long, tick: () -> Unit) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < deadline) {
            tick()
            sleep(60)
        }
    }

    private fun sleep(millis: Long) {
        if (millis > 0) runCatching { Thread.sleep(millis) }
    }

    private fun randomDir() = "/" + List(random.nextInt(1, 5)) { randomFolder() }.joinToString("/")
    private fun randomFile() = randomDir() + "/" + randomBaseName() + "." + randomExt()
    private fun randomFolder() = FOLDERS.random(random)
    private fun randomBaseName() = NAMES.random(random) + "_" + random.nextInt(1000)
    private fun randomExt() = EXTS.random(random)

    private fun LongRange.random(rnd: Random) = rnd.nextLong(first, last + 1)

    companion object {
        private val FOLDERS = listOf("media", "backup", "Documents", "Photos", "projects", "music", "2026", "archive")
        private val NAMES = listOf("IMG", "report", "invoice", "notes", "vacation", "budget", "song", "design")
        private val EXTS = listOf("jpg", "pdf", "docx", "mp3", "png", "txt", "zip")
    }
}
