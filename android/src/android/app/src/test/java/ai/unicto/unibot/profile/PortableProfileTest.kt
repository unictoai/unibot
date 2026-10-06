package ai.unicto.unibot.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 96 — secret redaction and profile round-trip.
 */
class PortableProfileTest {

    @Test
    fun `secret-looking keys are redacted`() {
        val (redacted, keys) = SecretRedactor.redact(
            mapOf(
                "quiet_hours.enabled" to "true",
                "sync.api_token" to "abc123",
                "backup.password" to "hunter2",
                "theme" to "dark",
            ),
        )
        assertEquals("__REDACTED__", redacted["sync.api_token"])
        assertEquals("__REDACTED__", redacted["backup.password"])
        assertEquals("true", redacted["quiet_hours.enabled"])
        assertEquals("dark", redacted["theme"])
        assertEquals(setOf("sync.api_token", "backup.password"), keys.toSet())
    }

    @Test
    fun `benign keys containing hints as substrings still redact conservatively`() {
        // "keyboard" contains "key" — conservative redaction is the safe default.
        assertTrue(SecretRedactor.isSecretKey("keyboard"))
        assertFalse(SecretRedactor.isSecretKey("quiet_hours.enabled"))
    }

    @Test
    fun `profile json round trip`() {
        val profile = PortableProfile(
            appVersion = "1.4.0",
            deviceLabel = "Test Device",
            settings = mapOf("quiet_hours.enabled" to "true"),
            redactedKeys = listOf("sync.api_token"),
            skills = listOf(ProfileSkill(name = "s", description = "d", skillMd = "---\nname: s\n---\nHi")),
            promptPresets = listOf(ProfilePromptPreset(name = "p", kind = "TEXT", content = "hello")),
            slashCommandsJson = """[{"trigger":"brief"}]""",
        )
        val restored = PortableProfile.parse(profile.toJson().toString())
        assertNotNull(restored)
        restored!!
        assertEquals(1, restored.version)
        assertEquals("1.4.0", restored.appVersion)
        assertEquals("true", restored.settings["quiet_hours.enabled"])
        assertEquals(listOf("sync.api_token"), restored.redactedKeys)
        assertEquals(1, restored.skills.size)
        assertEquals("s", restored.skills[0].name)
        assertEquals(1, restored.promptPresets.size)
        assertTrue(restored.slashCommandsJson.contains("brief"))
    }

    @Test
    fun `parse rejects non-profile json`() {
        assertEquals(null, PortableProfile.parse("""{"foo":"bar"}"""))
        assertEquals(null, PortableProfile.parse("not json at all"))
    }

    @Test
    fun `profile file name is safe`() {
        val name = profileFileName()
        assertTrue(name.startsWith("unibot-profile-"))
        assertTrue(name.endsWith(".json"))
        assertTrue(name.matches(Regex("[A-Za-z0-9._-]+")))
    }
}
