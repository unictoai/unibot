package ai.unicto.unibot.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [unibot-connectors] GitHub + Telegram tools are only exposed when connected.
 */
class TokenConnectorGatingTest {

    private fun names(github: Boolean = false, telegram: Boolean = false): Set<String> =
        AgentTools.makeAgentTools(
            githubConnected = github,
            telegramConnected = telegram,
        ).map { it.name }.toSet()

    @Test
    fun `token tools absent when not connected`() {
        val n = names()
        assertFalse(n.contains(GitHubTool.REPOS_NAME))
        assertFalse(n.contains(GitHubTool.READ_NAME))
        assertFalse(n.contains(GitHubTool.ISSUES_NAME))
        assertFalse(n.contains(GitHubTool.CREATE_ISSUE_NAME))
        assertFalse(n.contains(TelegramTool.RESOLVE_NAME))
        assertFalse(n.contains(TelegramTool.SEND_NAME))
        assertFalse(n.contains(TelegramTool.READ_NAME))
        // Core tools still present.
        assertTrue(n.contains("shell_execute"))
    }

    @Test
    fun `github tools present when connected`() {
        val n = names(github = true)
        assertTrue(n.contains(GitHubTool.REPOS_NAME))
        assertTrue(n.contains(GitHubTool.READ_NAME))
        assertTrue(n.contains(GitHubTool.ISSUES_NAME))
        assertTrue(n.contains(GitHubTool.CREATE_ISSUE_NAME))
        // Telegram still absent.
        assertFalse(n.contains(TelegramTool.SEND_NAME))
    }

    @Test
    fun `telegram tools present when connected`() {
        val n = names(telegram = true)
        assertTrue(n.contains(TelegramTool.RESOLVE_NAME))
        assertTrue(n.contains(TelegramTool.SEND_NAME))
        assertTrue(n.contains(TelegramTool.READ_NAME))
        // GitHub still absent.
        assertFalse(n.contains(GitHubTool.REPOS_NAME))
    }

    @Test
    fun `definitions are well formed`() {
        val gh = GitHubTool.definitions().associateBy { it.name }
        assertTrue(gh.size == 4)
        assertTrue(gh[GitHubTool.READ_NAME]!!.required.containsAll(listOf("repo", "path")))
        assertTrue(gh[GitHubTool.CREATE_ISSUE_NAME]!!.required.containsAll(listOf("repo", "title")))
        val tg = TelegramTool.definitions().associateBy { it.name }
        assertTrue(tg.size == 3)
        assertTrue(tg[TelegramTool.SEND_NAME]!!.required.contains("text"))
        // Every definition carries a tool_title param first (app convention).
        (GitHubTool.definitions() + TelegramTool.definitions()).forEach {
            assertTrue(it.propertyOrdering.first() == "tool_title")
        }
    }
}
