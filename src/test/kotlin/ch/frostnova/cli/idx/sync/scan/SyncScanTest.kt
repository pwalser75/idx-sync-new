package ch.frostnova.cli.idx.sync.scan

import ch.frostnova.cli.idx.sync.config.IdxSyncFile
import ch.frostnova.cli.idx.sync.config.IdxSyncFileRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SyncScanTest {

    private val repository = IdxSyncFileRepository()
    private val scanner = SyncFolderScanner(repository)
    private val resolver = SyncPairResolver()

    private fun dir(root: Path, rel: String): Path = root.resolve(rel).createDirectories()

    @Test
    fun `finds source and target markers and resolves the pair`(@TempDir root: Path) {
        val sourceDir = dir(root, "media/backup-source")
        val targetDir = dir(root, "usb/backup-target")
        repository.write(
            sourceDir,
            IdxSyncFile(folderId = "id-1", folderName = "My Docs", excludePatterns = setOf("**/tmp"), includeHidden = true),
        )
        repository.write(targetDir, IdxSyncFile(folderId = "id-2", sourceFolderId = "id-1"))

        val markers = scanner.scan(roots = listOf(root))
        assertThat(markers).hasSize(2)

        val pairs = resolver.resolve(markers)
        assertThat(pairs).hasSize(1)
        with(pairs.single()) {
            assertThat(name).isEqualTo("My Docs")
            assertThat(source).isEqualTo(sourceDir)
            assertThat(target).isEqualTo(targetDir)
            assertThat(excludePatterns).contains("**/tmp")
            assertThat(includeHidden).isTrue()
        }
    }

    @Test
    fun `target without a present source resolves to no pair`(@TempDir root: Path) {
        val targetDir = dir(root, "usb/orphan-target")
        repository.write(targetDir, IdxSyncFile(folderId = "t", sourceFolderId = "missing-source"))

        val pairs = resolver.resolve(scanner.scan(roots = listOf(root)))
        assertThat(pairs).isEmpty()
    }

    @Test
    fun `merges exclude patterns from both sides`(@TempDir root: Path) {
        val sourceDir = dir(root, "s")
        val targetDir = dir(root, "t")
        repository.write(sourceDir, IdxSyncFile(folderId = "s1", folderName = "S", excludePatterns = setOf("a")))
        repository.write(targetDir, IdxSyncFile(folderId = "t1", sourceFolderId = "s1", excludePatterns = setOf("b")))

        val pair = resolver.resolve(scanner.scan(roots = listOf(root))).single()
        assertThat(pair.excludePatterns).containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun `does not descend past max depth`(@TempDir root: Path) {
        val shallow = SyncFolderScanner(repository, maxDepth = 2)
        val deepDir = dir(root, "a/b/c/d/e")
        repository.write(deepDir, IdxSyncFile(folderId = "deep"))

        assertThat(shallow.scan(roots = listOf(root))).isEmpty()
    }

    @Test
    fun `skips platform-excluded and hidden directories`(@TempDir root: Path) {
        val junkDir = dir(root, "System Volume Information/inner")
        repository.write(junkDir, IdxSyncFile(folderId = "junk"))
        val hiddenDir = dir(root, ".hidden/inner")
        repository.write(hiddenDir, IdxSyncFile(folderId = "hidden"))

        assertThat(scanner.scan(roots = listOf(root))).isEmpty()
    }

    @Test
    fun `ignores malformed markers`(@TempDir root: Path) {
        val d = dir(root, "broken")
        repository.resolve(d).writeText("not: [valid")
        assertThat(scanner.scan(roots = listOf(root))).isEmpty()
    }
}
