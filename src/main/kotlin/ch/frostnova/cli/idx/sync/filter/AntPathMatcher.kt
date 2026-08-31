package ch.frostnova.cli.idx.sync.filter

import java.nio.file.Path

/**
 * Matches paths against an ant-style wildcard pattern. Semantics (kept identical to the original tool):
 *  - `?` matches exactly one non-separator character,
 *  - `*` matches any run (0..n) of non-separator characters,
 *  - `**` matches any path sequence (across separators).
 *
 * Paths are normalized to `/` separators before matching, so a single matcher works on both platforms.
 * Note (as in the original): `**` maps to `.*` and does *not* absorb a following `/`, so `**​/test.txt`
 * matches `foo/test.txt` but not a root-level `test.txt`.
 */
class AntPathMatcher(val pattern: String, ignoreCase: Boolean = false) {

    private val regex: Regex = compile(pattern, ignoreCase)

    fun matches(path: String): Boolean = regex.matches(path.replace('\\', '/'))

    fun matches(path: Path): Boolean = matches(path.toString())

    companion object {
        /** Regex metacharacters (other than the wildcards we translate) that must be escaped. */
        private const val ESCAPE = ".\\+()[]{}^\$|"

        private fun compile(pattern: String, ignoreCase: Boolean): Regex {
            val src = pattern.replace('\\', '/')
            val sb = StringBuilder("^")
            var i = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '*' && i + 1 < src.length && src[i + 1] == '*' -> {
                        sb.append(".*"); i += 2
                    }
                    c == '*' -> {
                        sb.append("[^/]*"); i++
                    }
                    c == '?' -> {
                        sb.append("[^/]"); i++
                    }
                    c in ESCAPE -> {
                        sb.append('\\').append(c); i++
                    }
                    else -> {
                        sb.append(c); i++
                    }
                }
            }
            sb.append('$')
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return Regex(sb.toString(), options)
        }
    }
}
