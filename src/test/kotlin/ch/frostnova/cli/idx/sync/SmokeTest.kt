package ch.frostnova.cli.idx.sync

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SmokeTest {

    @Test
    fun `app version is resolvable`() {
        assertThat(appVersion()).isNotBlank()
    }
}
