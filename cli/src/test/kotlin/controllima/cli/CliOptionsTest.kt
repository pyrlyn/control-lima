package controllima.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliOptionsTest {
    @Test
    fun defaults() = assertEquals(CliOptions(), CliOptions.parse(emptyList()))

    @Test
    fun flagsAndPassthrough() {
        val options = CliOptions.parse(listOf("--port", "9000", "--lima-home", "/tmp/lima", "--open", "--", "--debug", "--tty=false"))
        assertEquals(9000, options.port)
        assertEquals("/tmp/lima", options.limaHome)
        assertEquals(true, options.open)
        assertEquals(listOf("--debug", "--tty=false"), options.passthrough)
    }

    @Test
    fun unknownFlagFails() {
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(listOf("--debug")) }
    }

    @Test
    fun badPortFails() {
        assertFailsWith<IllegalArgumentException> { CliOptions.parse(listOf("--port", "0")) }
    }
}
