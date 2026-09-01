package ch.frostnova.cli.idx.sync.cli

import ch.frostnova.cli.idx.sync.core.SyncPair
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SyncApplicationTest {

    private fun pair(name: String, id: String?) =
        SyncPair(name = name, source = Path.of("/s/$name"), target = Path.of("/t/$name"), sourceId = id)

    private val pairs = listOf(
        pair("Documents", "id-1"),
        pair("Photos", "id-2"),
        pair("Photos", "id-3"), // same name, different id
        pair("id-2", "id-4"),   // a name that collides with another pair's id
    )

    @Test
    fun `matches a single pair by folder-id`() {
        assertThat(SyncApplication.matchPairs(pairs, "id-1")).extracting<String> { it.name }
            .containsExactly("Documents")
    }

    @Test
    fun `matches all pairs sharing a folder name`() {
        assertThat(SyncApplication.matchPairs(pairs, "Photos")).hasSize(2)
            .allMatch { it.name == "Photos" }
    }

    @Test
    fun `matches by id or name, taking the union`() {
        // "id-2" is both id-2's folder-id and another pair's folder name -> both are selected.
        assertThat(SyncApplication.matchPairs(pairs, "id-2")).extracting<String?> { it.sourceId }
            .containsExactlyInAnyOrder("id-2", "id-4")
    }

    @Test
    fun `returns nothing when neither an id nor a name matches`() {
        assertThat(SyncApplication.matchPairs(pairs, "does-not-exist")).isEmpty()
    }

    @Test
    fun `matches a pair by its source or target path`() {
        val p = SyncPair("X", Path.of("/data/src"), Path.of("/data/tgt"), sourceId = "id-x")
        assertThat(SyncApplication.matchPairs(listOf(p), "/data/src")).containsExactly(p)
        assertThat(SyncApplication.matchPairs(listOf(p), "/data/tgt")).containsExactly(p)
        assertThat(SyncApplication.matchPairs(listOf(p), "/data/other")).isEmpty()
    }
}
