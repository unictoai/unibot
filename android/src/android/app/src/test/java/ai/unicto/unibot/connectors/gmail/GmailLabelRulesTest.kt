package ai.unicto.unibot.connectors.gmail

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 43 — JVM tests for the auto-label rule predicate and JSON codec.
 */
class GmailLabelRulesTest {

    private fun rule(
        name: String = "Bills",
        fromContains: String = "",
        subjectContains: String = "",
        query: String = "",
        labelName: String = "Bills",
        enabled: Boolean = true,
    ) = GmailLabelRule(
        id = "r1",
        name = name,
        fromContains = fromContains,
        subjectContains = subjectContains,
        query = query,
        labelName = labelName,
        enabled = enabled,
    )

    @Test
    fun `empty conditions match everything when enabled`() {
        assertTrue(rule().matches("bank", "statement"))
    }

    @Test
    fun `all set conditions are ANDed`() {
        val r = rule(fromContains = "bank", subjectContains = "statement")
        assertTrue(r.matches("alerts@bank.com", "Your statement"))
        assertFalse(r.matches("alerts@bank.com", "Newsletter"))
        assertFalse(r.matches("friend@mail.com", "Your statement"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(rule(fromContains = "BANK").matches("alerts@bank.com", "x"))
        assertTrue(rule(subjectContains = "INVOICE").matches("a", "your invoice here"))
    }

    @Test
    fun `gmailQuery always scopes to the lookback window`() {
        val q = rule(fromContains = "bank").gmailQuery()
        assertTrue(q.contains("newer_than:24h"))
    }

    @Test
    fun `gmailQuery appends the extra query`() {
        val q = rule(query = "has:attachment").gmailQuery()
        assertEquals("newer_than:24h (has:attachment)", q)
    }

    @Test
    fun `json round trip`() {
        val r = rule(fromContains = "bank", subjectContains = "statement", query = "has:attachment")
        val restored = GmailLabelRule.fromJson(r.toJson())!!
        assertEquals("r1", restored.id)
        assertEquals("Bills", restored.name)
        assertEquals("bank", restored.fromContains)
        assertEquals("statement", restored.subjectContains)
        assertEquals("has:attachment", restored.query)
        assertEquals("Bills", restored.labelName)
        assertTrue(restored.enabled)
    }

    @Test
    fun `fromJson rejects nameless or labelless rules`() {
        assertNull(GmailLabelRule.fromJson(JSONObject()))
        assertNull(GmailLabelRule.fromJson(JSONObject().put("name", "x")))
        assertNull(GmailLabelRule.fromJson(JSONObject().put("label_name", "x")))
    }
}
