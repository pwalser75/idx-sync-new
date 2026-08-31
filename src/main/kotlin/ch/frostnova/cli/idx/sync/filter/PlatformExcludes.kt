package ch.frostnova.cli.idx.sync.filter

/**
 * Built-in, always-on exclusion of platform system/junk files that must never be part of a backup
 * (Windows, macOS, Linux) plus idx-sync's own control files. Matching is per path *segment* (a single
 * file/directory name) and case-insensitive, so excluding a directory name (e.g. `System Volume
 * Information`) naturally prunes its whole subtree.
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

    companion object {
        /** idx-sync's own files: the marker and any in-flight temp/backup files (see AtomicFileWriter). */
        val IDX = PlatformExcludes(
            exactNames = setOf(".idxsync"),
            globs = listOf("*.idxtmp", "*.idxbak", ".*.idxtmp", ".*.idxbak"),
        )

        val WINDOWS = PlatformExcludes(
            exactNames = setOf(
                "thumbs.db", "ehthumbs.db", "ehthumbs_vista.db", "desktop.ini",
                "pagefile.sys", "hiberfil.sys", "swapfile.sys",
                "\$recycle.bin", "recycler", "system volume information",
                "msocache", "\$windows.~bt", "\$windows.~ws",
            ),
            globs = emptyList(),
        )

        val MACOS = PlatformExcludes(
            exactNames = setOf(
                ".ds_store", ".appledouble", ".lsoverride", ".documentrevisions-v100",
                ".fseventsd", ".spotlight-v100", ".temporaryitems", ".trashes",
                ".volumeicon.icns", ".com.apple.timemachine.donotpresent",
                ".appledb", ".appledesktop", ".apdisk",
                "network trash folder", "temporary items",
            ),
            globs = listOf("._*"),
        )

        val LINUX = PlatformExcludes(
            exactNames = setOf(".directory", "lost+found"),
            globs = listOf(".trash-*", ".nfs*", ".fuse_hidden*"),
        )

        /** Every platform's junk plus idx-sync's own files. This is the default filter set. */
        val ALL: PlatformExcludes = IDX + WINDOWS + MACOS + LINUX

        val NONE = PlatformExcludes(emptySet(), emptyList())
    }
}
