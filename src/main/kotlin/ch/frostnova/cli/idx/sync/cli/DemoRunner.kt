package ch.frostnova.cli.idx.sync.cli

import ch.frostnova.cli.idx.sync.config.IdxSyncFile
import ch.frostnova.cli.idx.sync.core.FileChange
import ch.frostnova.cli.idx.sync.core.SyncAction
import ch.frostnova.cli.idx.sync.core.SyncMode
import ch.frostnova.cli.idx.sync.core.SyncResult
import ch.frostnova.cli.idx.sync.scan.DiscoveredMarker
import ch.frostnova.cli.idx.sync.scan.SyncPairResolver
import ch.frostnova.cli.idx.sync.sync.SyncListener
import ch.frostnova.cli.idx.sync.ui.ConsoleUi
import java.nio.file.Path
import kotlin.random.Random

/**
 * Simulates a full sync run over a fixed wall-clock [totalMillis] to exercise the console UI without
 * touching the file system. Nothing is read or written; every "folder", "file" and byte is synthetic.
 */
class DemoRunner(private val ui: ConsoleUi, private val random: Random = Random(42)) {

    fun run(totalMillis: Long) {
        val start = System.nanoTime()

        // ~15% scanning, ~15% comparing, ~70% copying
        val scanMillis = (totalMillis * 0.15).toLong()
        val compareMillis = (totalMillis * 0.15).toLong()
        val copyMillis = totalMillis - scanMillis - compareMillis

        ui.info("(demo mode — no files are read or written)")
        ui.blank()

        ui.fractionProgress("Scanning for sync files") { report -> animateFraction(scanMillis, report) }

        val markers = fakeMarkers()
        val pairs = SyncPairResolver().resolve(markers)
        ui.listFoundMarkers(markers)
        ui.blank()
        ui.listMatchingPairs(pairs)
        ui.blank()

        pairs.forEach { pair ->
            ui.spinner("Comparing ${pair.name}") { detail ->
                animateDetail(compareMillis / pairs.size.coerceAtLeast(1)) { detail(randomFile()) }
            }
        }

        val files = syntheticFiles()
        ui.pendingChanges(
            files.count { it.action == SyncAction.CREATE },
            files.count { it.action == SyncAction.UPDATE },
            0,
        )
        ui.blank()

        val totalBytes = files.sumOf { it.size }
        val result = ui.copyProgress(totalBytes) { listener -> simulateCopy(files, copyMillis, listener) }
        ui.report(SyncMode.SYNC, result, (System.nanoTime() - start) / 1e9)
    }

    private fun fakeMarkers(): List<DiscoveredMarker> = listOf(
        DiscoveredMarker(IdxSyncFile(folderId = "photos-src", folderName = "Photos", includeHidden = true), Path.of("/media/sd-card/DCIM")),
        DiscoveredMarker(IdxSyncFile(folderId = "photos-tgt", sourceFolderId = "photos-src"), Path.of("/mnt/backup/Photos")),
        DiscoveredMarker(IdxSyncFile(folderId = "docs-src", folderName = "Documents", includeHidden = true), Path.of("/home/demo/Documents")),
        DiscoveredMarker(IdxSyncFile(folderId = "docs-tgt", sourceFolderId = "docs-src"), Path.of("/mnt/backup/Documents")),
    )

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
                sleep(copyMillis * n / totalBytes)
            }
            if (file.action == SyncAction.CREATE) created++ else updated++
        }
        return SyncResult(created = created, updated = updated, bytesTransferred = totalBytes)
    }

    private fun syntheticFiles(): List<FileChange> = buildList {
        repeat(18) {
            val action = if (random.nextInt(3) == 0) SyncAction.UPDATE else SyncAction.CREATE
            val size = random.nextLong(200_000L, 8_000_000L)
            val rel = Path.of("${randomFolder()}/${randomBaseName()}.${randomExt()}")
            add(FileChange(rel, Path.of("/src").resolve(rel), Path.of("/dst").resolve(rel), action, size))
        }
    }

    private fun animateFraction(millis: Long, report: (Double, Any) -> Unit) {
        val startNs = System.nanoTime()
        val endNs = startNs + millis * 1_000_000
        while (System.nanoTime() < endNs) {
            report((System.nanoTime() - startNs).toDouble() / (endNs - startNs), randomDir())
            sleep(60)
        }
        report(1.0, randomDir())
    }

    private fun animateDetail(millis: Long, tick: () -> Unit) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < deadline) {
            tick(); sleep(60)
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

    companion object {
        private val FOLDERS = listOf("media", "backup", "Documents", "Photos", "projects", "music", "2026", "archive")
        private val NAMES = listOf("IMG", "report", "invoice", "notes", "vacation", "budget", "song", "design")
        private val EXTS = listOf("jpg", "pdf", "docx", "mp3", "png", "txt", "zip")
    }
}
