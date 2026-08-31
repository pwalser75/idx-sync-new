package ch.frostnova.cli.idx.sync.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Ported from the original PathFilterTest to preserve exact wildcard semantics. */
class AntPathMatcherTest {

    private fun match(pattern: String, path: String) = AntPathMatcher(pattern).matches(path)

    @Test
    fun literal() {
        assertThat(match("test.txt", "test.txt")).isTrue()
        assertThat(match("test.txt", "test_txt")).isFalse()
        assertThat(match("foo/bla/test.txt", "foo/bla/test.txt")).isTrue()
    }

    @Test
    fun asterisk() {
        assertThat(match("*.txt", "test.txt")).isTrue()
        assertThat(match("*.txt", "test_txt")).isFalse()
        assertThat(match("te*.txt", "test.txt")).isTrue()
        assertThat(match("t*t.txt", "test.txt")).isTrue()
        assertThat(match("*t*e*s*t*.txt", "test.txt")).isTrue()

        assertThat(match("foo/bla/*.txt", "foo/bla/test.txt")).isTrue()
        assertThat(match("*/bla/test.txt", "foo/bla/test.txt")).isTrue()
        assertThat(match("*/*/test.txt", "foo/bla/test.txt")).isTrue()
        assertThat(match("*/test.txt", "foo/bla/test.txt")).isFalse()

        assertThat(match("*/\$Recycle.Bin", "C:/\$Recycle.Bin")).isTrue()
        assertThat(
            match(
                "**/node_modules/**/*.js",
                "/media/colin/workspace/demo-game/node_modules/phaser/src/renderer/webgl/shaders/FXDisplacement-frag.js",
            ),
        ).isTrue()
        assertThat(
            match(
                "**/node_modules/**",
                "/media/colin/workspace/demo-game/node_modules/phaser/src/renderer/webgl/shaders/FXDisplacement-frag.js",
            ),
        ).isTrue()
        assertThat(
            match(
                "**/node_modules/**/.js",
                "/media/colin/workspace/demo-game/node_modules/phaser/src/renderer/webgl/shaders/FXDisplacement-frag.js",
            ),
        ).isFalse()
    }

    @Test
    fun questionMark() {
        assertThat(match("?est.txt", "test.txt")).isTrue()
        assertThat(match("?est.txt", "test_txt")).isFalse()
        assertThat(match("t??t.txt", "test.txt")).isTrue()
        assertThat(match("???.???", "test.txt")).isFalse()
        assertThat(match("????.???", "test.txt")).isTrue()
        assertThat(match("foo?bla/test.txt", "foo/bla/test.txt")).isFalse()
    }

    @Test
    fun antPath() {
        assertThat(match("**/test.txt", "test.txt")).isFalse()
        assertThat(match("**/test.txt", "foo/test.txt")).isTrue()
        assertThat(match("**/test.txt", "foo/bla/test.txt")).isTrue()
        assertThat(match("foo/**/test.txt", "foo/bla/test.txt")).isTrue()
        assertThat(match("bla/**/test.txt", "foo/bla/test.txt")).isFalse()
        assertThat(match("**/**/test.txt", "foo/bla/test.txt")).isTrue()
    }

    @Test
    fun backslashesAreNormalized() {
        assertThat(match("foo/bar.txt", "foo\\bar.txt")).isTrue()
    }
}
