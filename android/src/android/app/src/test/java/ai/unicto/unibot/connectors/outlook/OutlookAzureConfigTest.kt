package ai.unicto.unibot.connectors.outlook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 35 — JVM tests for the configurable Azure app slot's JSON
 * round-trip (the EncryptedSharedPreferences wrapper is intentionally
 * thin and untested here).
 */
class OutlookAzureConfigTest {

    @Test
    fun `default config is not configured`() {
        val cfg = OutlookAzureConfig()
        assertFalse(cfg.isConfigured)
        assertEquals(OutlookAzureConfig.DEFAULT_TENANT, cfg.tenant)
    }

    @Test
    fun `client id marks configured`() {
        val cfg = OutlookAzureConfig(clientId = "some-client-id")
        assertTrue(cfg.isConfigured)
    }

    @Test
    fun `json round trip preserves all fields`() {
        val cfg = OutlookAzureConfig(
            clientId = "cid-123",
            tenant = "tenant-456",
            redirectUri = "unibot://oauth/outlook",
        )
        val restored = OutlookAzureConfig.fromJson(cfg.toJson())
        assertEquals("cid-123", restored.clientId)
        assertEquals("tenant-456", restored.tenant)
        assertEquals("unibot://oauth/outlook", restored.redirectUri)
        assertTrue(restored.isConfigured)
    }

    @Test
    fun `blank tenant falls back to default on read`() {
        val cfg = OutlookAzureConfig(clientId = "x", tenant = "")
        val restored = OutlookAzureConfig.fromJson(cfg.toJson())
        assertEquals(OutlookAzureConfig.DEFAULT_TENANT, restored.tenant)
    }

    @Test
    fun `blank and corrupt json give defaults`() {
        assertFalse(OutlookAzureConfig.fromJson(null).isConfigured)
        assertFalse(OutlookAzureConfig.fromJson("").isConfigured)
        assertFalse(OutlookAzureConfig.fromJson("{not json").isConfigured)
    }
}
