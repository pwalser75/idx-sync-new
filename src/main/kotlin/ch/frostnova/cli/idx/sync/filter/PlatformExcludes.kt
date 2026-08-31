package ch.frostnova.cli.idx.sync.filter

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * Built-in, always-on exclusion of platform system/junk files that must never be part of a backup
 * (Windows, macOS, Linux) plus idx-sync's own control files. The rules are **not** hard-coded here — they
 * are loaded from the internal resource `platform-excludes.yaml`, grouped per platform.
 *
 * Matching is per path *segment* (a single file/directory name) and case-insensitive, so excluding a
 * directory name (e.g. `System Volume Information`) naturally prunes its whole subtree.
 */
class PlatformExcludes(
    exactNames: Set<String>,
    globs: List<String>,
) {
    private val exact: Set<String> = exactNames.map { it.lowercase() }.toSet()
    private val globMatchers: List<AntPathMatcher> = globs.map { AntPathMatcher(it, ignoreCase = true) }

    /** True if a single path segment [name] is a platform-excluded system/junk file. */
    fun excludes(name: String): Boolean =
        name.lowercase() in exact || globMatchers.any { it.matches(name) }

    operator fun plus(other: PlatformExcludes): PlatformExcludes = PlatformExcludes(
        exactNames = exact + other.exact,
        globs = (globMatchers + other.globMatchers).map { it.pattern },
    )

    /** One platform's section of the internal YAML config. */
    private data class Section(
        @param:JsonProperty("exact-names") @get:JsonProperty("exact-names")
        val exactNames: Set<String> = emptySet(),
        val globs: List<String> = emptyList(),
    )

    private data class Config(val platforms: Map<String, Section> = emptyMap())

    companion object {
        const val RESOURCE = "/platform-excludes.yaml"

        val NONE = PlatformExcludes(emptySet(), emptyList())

        /** Every platform's junk plus idx-sync's own files, loaded from [RESOURCE]. The default filter set. */
        val ALL: PlatformExcludes by lazy { load() }

        private fun load(): PlatformExcludes {
            val mapper = YAMLMapper().registerKotlinModule()
            val config = PlatformExcludes::class.java.getResourceAsStream(RESOURCE)
                ?.use { mapper.readValue<Config>(it) }
                ?: error("Missing internal resource $RESOURCE")
            return config.platforms.values.fold(NONE) { acc, s -> acc + PlatformExcludes(s.exactNames, s.globs) }
        }
    }
}
