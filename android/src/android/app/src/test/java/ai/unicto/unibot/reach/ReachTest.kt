package ai.unicto.unibot.reach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachTest {

    @Test fun `a computer knows its address`() {
        val c = Computers.Computer("id", "desk", "Linux", "hub-1")
        assertEquals("hub", c.address)
    }

    @Test fun `the help names every verb`() {
        for (verb in listOf("status", "run", "ls", "get", "put", "open", "screen")) {
            assertTrue(verb, ReachOffloadHandler.HELP.contains("unibot-pc $verb"))
        }
    }
}
