package ai.unicto.unibot.sync

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side end-to-end encryption for sync snapshots (P8).
 *
 * The cloud relay has no E2E sync channel (and no sync endpoint at all — see
 * [StubRelaySyncTransport]), so payloads are encrypted here, on the phone,
 * before they ever reach a transport: PBKDF2-HMAC-SHA256 (120,000 rounds)
 * derives a 256-bit key from the user's passphrase and a fresh random salt,
 * and AES-256-GCM seals the snapshot. A future relay transport will only
 * ever see the envelope — salt, IV and ciphertext — never plaintext.
 *
 * The passphrase is held in memory by [SessionSyncEngine] and never persisted:
 * it is wiped when sync is turned off and does not survive an app restart.
 */
object SyncCrypto {
    const val ENVELOPE_VERSION = 1
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Seal [plaintext] under a key derived from [passphrase]. Returns the
     * envelope as JSON: `{v, kdf, iter, salt, iv, ct}` (all binary fields
     * base64, no padding).
     */
    fun seal(passphrase: CharArray, plaintext: ByteArray): JSONObject {
        try {
            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val key = derive(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val ct = cipher.doFinal(plaintext)
            return JSONObject()
                .put("v", ENVELOPE_VERSION)
                .put("kdf", "pbkdf2-sha256")
                .put("iter", ITERATIONS)
                .put("salt", b64(salt))
                .put("iv", b64(iv))
                .put("ct", b64(ct))
        } catch (e: Exception) {
            throw CryptoException("Could not encrypt the sync snapshot", e)
        }
    }

    /**
     * Open an envelope produced by [seal]. Throws [CryptoException] when the
     * passphrase is wrong, the envelope is corrupt, or its version is unknown.
     */
    fun open(passphrase: CharArray, envelope: JSONObject): ByteArray {
        try {
            if (envelope.optInt("v", -1) != ENVELOPE_VERSION) {
                throw CryptoException("Unsupported sync envelope version ${envelope.optInt("v", -1)}")
            }
            val salt = unb64(envelope.getString("salt"))
            val iv = unb64(envelope.getString("iv"))
            val ct = unb64(envelope.getString("ct"))
            val key = derive(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ct)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            // Wrong passphrase and tampered ciphertext both land here (GCM tag
            // failure) — deliberately one message, no oracle.
            throw CryptoException("Could not decrypt the sync snapshot — wrong passphrase or damaged data", e)
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
