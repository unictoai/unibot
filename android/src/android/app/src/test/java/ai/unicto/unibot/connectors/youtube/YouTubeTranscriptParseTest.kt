package ai.unicto.unibot.connectors.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 38 — JVM tests for the caption-track parser (pure; no network).
 */
class YouTubeTranscriptParseTest {

    @Test
    fun `timedtext xml parses to plain lines`() {
        val xml = """<?xml version="1.0"?>
            <transcript>
            <text start="0.0" dur="2.5">Hello world</text>
            <text start="2.5" dur="3.0">second &amp; line</text>
            </transcript>"""
        assertEquals("Hello world\nsecond & line", YouTubeConnector.parseTimedText(xml))
    }

    @Test
    fun `html entities are decoded`() {
        val xml = "<transcript><text start=\"1\" dur=\"1\">&quot;quoted&quot; &lt;tag&gt;</text></transcript>"
        assertEquals("\"quoted\" <tag>", YouTubeConnector.parseTimedText(xml))
    }

    @Test
    fun `duplicate caption lines are collapsed`() {
        val xml = "<transcript><text start=\"0\">same</text><text start=\"1\">same</text></transcript>"
        assertEquals("same", YouTubeConnector.parseTimedText(xml))
    }

    @Test
    fun `empty xml gives empty string`() {
        assertEquals("", YouTubeConnector.parseTimedText("<transcript></transcript>"))
        assertEquals("", YouTubeConnector.parseTimedText(""))
    }

    @Test
    fun `inner tags are stripped`() {
        val xml = "<transcript><text start=\"0\">hi <b>there</b></text></transcript>"
        assertTrue(YouTubeConnector.parseTimedText(xml).contains("hi"))
        assertTrue(YouTubeConnector.parseTimedText(xml).contains("there"))
    }
}
