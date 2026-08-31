package ch.frostnova.cli.idx.sync.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class PlatformExcludesTest {

    @Test
    fun `excludes known system files case-insensitively`() {
        val all = PlatformExcludes.ALL
        assertThat(all.excludes("Thumbs.db")).isTrue()
        assertThat(all.excludes("thumbs.db")).isTrue()
        assertThat(all.excludes(".DS_Store")).isTrue()
        assertThat(all.excludes("System Volume Information")).isTrue()
        assertThat(all.excludes("\$RECYCLE.BIN")).isTrue()
        assertThat(all.excludes("lost+found")).isTrue()
        assertThat(all.excludes(".idxsync")).isTrue()
    }

    @Test
    fun `excludes glob-based junk`() {
        val all = PlatformExcludes.ALL
        assertThat(all.excludes("._resourcefork")).isTrue()
        assertThat(all.excludes(".Trash-1000")).isTrue()
        assertThat(all.excludes(".nfs0001")).isTrue()
        assertThat(all.excludes("data.idxtmp")).isTrue()
        assertThat(all.excludes(".secret.idxbak")).isTrue()
    }

    @Test
    fun `keeps normal files`() {
        val all = PlatformExcludes.ALL
        assertThat(all.excludes("report.pdf")).isFalse()
        assertThat(all.excludes("photo.jpg")).isFalse()
        assertThat(all.excludes("thumbs.database")).isFalse()
    }
}

class ExcludeFilterTest {

    private fun rel(p: String): Path = Path.of(p)

    @Test
    fun `platform excludes prune subtrees`() {
        val filter = ExcludeFilter(includeHidden = true)
        assertThat(filter.excludes(rel("photos/Thumbs.db"))).isTrue()
        assertThat(filter.excludes(rel("System Volume Information/x/y.dat"))).isTrue()
        assertThat(filter.excludes(rel("docs/report.pdf"))).isFalse()
    }

    @Test
    fun `hidden files excluded unless includeHidden`() {
        val hiddenExcluded = ExcludeFilter(includeHidden = false)
        assertThat(hiddenExcluded.excludes(rel(".git/config"))).isTrue()
        assertThat(hiddenExcluded.excludes(rel("src/.env"))).isTrue()
        assertThat(hiddenExcluded.excludes(rel("src/Main.kt"))).isFalse()

        val hiddenIncluded = ExcludeFilter(includeHidden = true)
        assertThat(hiddenIncluded.excludes(rel(".config/app.yaml"))).isFalse()
    }

    @Test
    fun `user patterns match full relative path`() {
        val filter = ExcludeFilter(userPatterns = setOf("**/node-modules", "build/**"), includeHidden = true)
        assertThat(filter.excludes(rel("web/node-modules"))).isTrue()
        assertThat(filter.excludes(rel("build/classes/Main.class"))).isTrue()
        assertThat(filter.excludes(rel("src/Main.kt"))).isFalse()
    }

    @Test
    fun `slash-less patterns exclude a name at any depth including root`() {
        val filter = ExcludeFilter(userPatterns = setOf("node_modules", "*.tmp"), includeHidden = true)
        assertThat(filter.excludes(rel("node_modules"))).isTrue()          // root
        assertThat(filter.excludes(rel("web/node_modules/x.js"))).isTrue() // nested
        assertThat(filter.excludes(rel("cache.tmp"))).isTrue()
        assertThat(filter.excludes(rel("a/b/cache.tmp"))).isTrue()
        assertThat(filter.excludes(rel("src/Main.kt"))).isFalse()
    }

    @Test
    fun `includes is the negation of excludes`() {
        val filter = ExcludeFilter(includeHidden = true)
        assertThat(filter.includes(rel("docs/report.pdf"))).isTrue()
        assertThat(filter.includes(rel("photos/Thumbs.db"))).isFalse()
    }
}
