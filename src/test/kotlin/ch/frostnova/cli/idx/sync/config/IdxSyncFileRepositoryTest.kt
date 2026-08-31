package ch.frostnova.cli.idx.sync.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class IdxSyncFileRepositoryTest {

    private val repository = IdxSyncFileRepository()

    @Test
    fun `writes and reads back a source marker`(@TempDir dir: Path) {
        val source = IdxSyncFile(
            folderId = "4ec2840b-e80b-4498-a2cd-820f283ba2e0",
            folderName = "BACKUP Dev",
            excludePatterns = setOf("**/node-modules", "**/.git"),
            includeHidden = true,
            tags = setOf("BACKUP", "DEV"),
        )

        val path = repository.write(dir, source)
        assertThat(path.fileName.toString()).isEqualTo(".idxsync")

        val read = repository.readOrNull(dir)
        assertThat(read).isEqualTo(source)
    }

    @Test
    fun `reads a target marker`(@TempDir dir: Path) {
        val target = IdxSyncFile(
            folderId = "aaa",
            sourceFolderId = "4ec2840b-e80b-4498-a2cd-820f283ba2e0",
        )
        repository.write(dir, target)

        val read = repository.readOrNull(dir)!!
        assertThat(read.isTarget).isTrue()
        assertThat(read.isSource).isFalse()
        assertThat(read.sourceFolderId).isEqualTo("4ec2840b-e80b-4498-a2cd-820f283ba2e0")
    }

    @Test
    fun `parses the legacy README source example (backward compatibility)`(@TempDir dir: Path) {
        repository.resolve(dir).writeText(
            """
            folder-id: 4ec2840b-e80b-4498-a2cd-820f283ba2e0
            folder-name: BACKUP Dev
            exclude-patterns:
            - '**/node-modules'
            - '**/.git'
            - '${'$'}RECYCLE.BIN'
            - 'System Volume Information'
            include-hidden: false
            tags:
              - BACKUP
              - DEV
              - DAILY
            """.trimIndent(),
        )

        val read = repository.readOrNull(dir)!!
        assertThat(read.folderId).isEqualTo("4ec2840b-e80b-4498-a2cd-820f283ba2e0")
        assertThat(read.folderName).isEqualTo("BACKUP Dev")
        assertThat(read.includeHidden).isFalse()
        assertThat(read.excludePatterns).contains("**/node-modules", "\$RECYCLE.BIN", "System Volume Information")
        assertThat(read.tags).containsExactlyInAnyOrder("BACKUP", "DEV", "DAILY")
        assertThat(read.isSource).isTrue()
    }

    @Test
    fun `ignores unknown properties`(@TempDir dir: Path) {
        repository.resolve(dir).writeText(
            """
            folder-id: x
            some-future-field: 42
            """.trimIndent(),
        )
        assertThat(repository.readOrNull(dir)?.folderId).isEqualTo("x")
    }

    @Test
    fun `malformed marker reads as null`(@TempDir dir: Path) {
        repository.resolve(dir).writeText("this: is: not: valid: yaml: [")
        assertThat(repository.readOrNull(dir)).isNull()
    }

    @Test
    fun `absent marker reads as null`(@TempDir dir: Path) {
        assertThat(repository.readOrNull(dir)).isNull()
        assertThat(repository.exists(dir)).isFalse()
    }

    @Test
    fun `remove deletes the marker`(@TempDir dir: Path) {
        repository.write(dir, IdxSyncFile(folderId = "x"))
        assertThat(repository.exists(dir)).isTrue()
        assertThat(repository.remove(dir)).isTrue()
        assertThat(repository.exists(dir)).isFalse()
        assertThat(Files.exists(repository.resolve(dir))).isFalse()
    }
}
