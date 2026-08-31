package ch.frostnova.cli.idx.sync.filter

import java.nio.file.Path

/**
 * The effective exclusion policy for one sync pair: user-defined ant patterns + the hidden-file policy +
 * the built-in [PlatformExcludes]. Tested against a path **relative to the sync root**.
 *
 * User patterns match the full relative path (ant semantics); platform excludes and the hidden-file rule
 * are evaluated per path segment, so excluding a directory prunes its whole subtree.
 */
class ExcludeFilter(
    userPatterns: Set<String> = emptySet(),
    private val includeHidden: Boolean = false,
    private val platformExcludes: PlatformExcludes = PlatformExcludes.ALL,
) {
    // A slash-less, `**`-free pattern (e.g. `node_modules`, `*.tmp`) matches a single name at ANY depth
    // (gitignore-like). Patterns with `/` or `**` keep full-path ant semantics.
    private val segmentMatchers: List<AntPathMatcher> =
        userPatterns.filter { '/' !in it && !it.contains("**") }.map { AntPathMatcher(it) }
    private val pathMatchers: List<AntPathMatcher> =
        userPatterns.filter { '/' in it || it.contains("**") }.map { AntPathMatcher(it) }

    /** True if [relativePath] (relative to the sync root) must be excluded from synchronization. */
    fun excludes(relativePath: Path): Boolean {
        for (segment in relativePath) {
            val name = segment.toString()
            if (name.isEmpty()) continue
            if (platformExcludes.excludes(name)) return true
            if (!includeHidden && isHidden(name)) return true
            if (segmentMatchers.any { it.matches(name) }) return true
        }
        val normalized = relativePath.toString().replace('\\', '/')
        return pathMatchers.any { it.matches(normalized) }
    }

    /** Convenience negation for use as a keep-predicate. */
    fun includes(relativePath: Path): Boolean = !excludes(relativePath)

    private fun isHidden(name: String): Boolean =
        name.startsWith(".") && name != "." && name != ".."

    companion object {
        /** Build the filter for a resolved pair (merges the pair's patterns with defaults). */
        fun of(excludePatterns: Set<String>, includeHidden: Boolean): ExcludeFilter =
            ExcludeFilter(excludePatterns, includeHidden)
    }
}
