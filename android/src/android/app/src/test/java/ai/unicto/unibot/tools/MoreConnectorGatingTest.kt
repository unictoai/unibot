package ai.unicto.unibot.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [unibot-connectors] YouTube + Discord + Slack tools are only exposed when connected.
 */
class MoreConnectorGatingTest {

    private fun names(
        youtube: Boolean = false,
        discord: Boolean = false,
        slack: Boolean = false,
    ): Set<String> = AgentTools.makeAgentTools(
        youtubeConnected = youtube,
        discordConnected = discord,
        slackConnected = slack,
    ).map { it.name }.toSet()

    @Test
    fun `new tools absent when not connected`() {
        val n = names()
        assertFalse(n.contains(YouTubeTool.SEARCH_NAME))
        assertFalse(n.contains(YouTubeTool.CHANNEL_NAME))
        assertFalse(n.contains(YouTubeTool.VIDEO_NAME))
        assertFalse(n.contains(DiscordTool.READ_NAME))
        assertFalse(n.contains(DiscordTool.SEND_NAME))
        assertFalse(n.contains(SlackTool.READ_NAME))
        assertFalse(n.contains(SlackTool.SEND_NAME))
        assertTrue(n.contains("shell_execute"))
    }

    @Test
    fun `each connector gates only its own tools`() {
        assertTrue(names(youtube = true).contains(YouTubeTool.CHANNEL_NAME))
        assertFalse(names(youtube = true).contains(DiscordTool.READ_NAME))
        assertTrue(names(discord = true).contains(DiscordTool.SEND_NAME))
        assertFalse(names(discord = true).contains(SlackTool.SEND_NAME))
        assertTrue(names(slack = true).contains(SlackTool.READ_NAME))
        assertFalse(names(slack = true).contains(YouTubeTool.SEARCH_NAME))
    }

    @Test
    fun `definitions are well formed`() {
        val yt = YouTubeTool.definitions().associateBy { it.name }
        assertTrue(yt.size == 3)
        assertTrue(yt[YouTubeTool.SEARCH_NAME]!!.required.contains("query"))
        assertTrue(yt[YouTubeTool.VIDEO_NAME]!!.required.contains("video_id"))
        val dc = DiscordTool.definitions().associateBy { it.name }
        assertTrue(dc.size == 2)
        assertTrue(dc[DiscordTool.SEND_NAME]!!.required.containsAll(listOf("channel_id", "text")))
        val sl = SlackTool.definitions().associateBy { it.name }
        assertTrue(sl.size == 2)
        assertTrue(sl[SlackTool.READ_NAME]!!.required.contains("channel_id"))
        (YouTubeTool.definitions() + DiscordTool.definitions() + SlackTool.definitions()).forEach {
            assertTrue(it.propertyOrdering?.first() == "tool_title")
        }
    }
}
