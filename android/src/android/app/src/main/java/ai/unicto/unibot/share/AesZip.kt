package ai.unicto.unibot.share

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * [v12-D] Minimal WinZip AES-256 (AE-2) zip writer.
 *
 * Produces genuine `.zip` files that 7-Zip / WinZip / macOS Archive Utility
 * open with the password: compression method 99, the `0x9901` AES extra
 * field, PBKDF2-HMAC-SHA1 key derivation, AES-256-CTR data encryption and
 * a truncated HMAC-SHA1 authentication code — per the WinZip AES
 * specification (https://www.winzip.com/win/en/aes_info.html).
 *
 * AE-2 is used (no CRC stored; sizes in headers include the AES overhead),
 * which is what modern readers expect.
 *
 * Implemented with JCE only — no extra dependency in the APK.
 */
object AesZip {

    data class Entry(val name: String, val bytes: ByteArray)

    private const val PBKDF2_ITERATIONS = 1000
    private const val SALT_LEN = 16 // AES-256
    private const val AES_KEY_LEN = 32
    private const val HMAC_KEY_LEN = 20
    private const val VERIFIER_LEN = 2
    private const val AUTH_CODE_LEN = 10

    /**
     * Writes a WinZip AES-256 encrypted zip containing [entries] (stored
     * with DEFLATE compression under the AES layer) to [out].
     */
    fun writeEncryptedZip(out: OutputStream, entries: List<Entry>, password: String) {
        val centralDir = ByteArrayOutputStream()
        var offset = 0L

        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val deflated = deflate(entry.bytes)

            // --- Key derivation: PBKDF2-HMAC-SHA1(password, salt, 1000) ---
            val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
            val derived = pbkdf2Sha1(password, salt)
            val aesKey = derived.copyOfRange(0, AES_KEY_LEN)
            val hmacKey = derived.copyOfRange(AES_KEY_LEN, AES_KEY_LEN + HMAC_KEY_LEN)
            val verifier = derived.copyOfRange(AES_KEY_LEN + HMAC_KEY_LEN, AES_KEY_LEN + HMAC_KEY_LEN + VERIFIER_LEN)

            // --- Encrypt: AES-256-CTR, zero IV ---
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                IvParameterSpec(ByteArray(16)),
            )
            val encrypted = cipher.doFinal(deflated)

            // --- Authenticate: HMAC-SHA1(ciphertext), truncated to 10 bytes ---
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(hmacKey, "HmacSHA1"))
            val authCode = mac.doFinal(encrypted).copyOfRange(0, AUTH_CODE_LEN)

            val payloadLen = SALT_LEN + VERIFIER_LEN + encrypted.size + AUTH_CODE_LEN

            // --- Local file header ---
            val localHeader = ByteArrayOutputStream().apply {
                writeIntLE(0x04034B50) // signature
                writeShortLE(51) // version needed (AES)
                writeShortLE(0x0001) // general purpose flag: encrypted
                writeShortLE(99) // compression method: AES
                writeShortLE(0) // mod time
                writeShortLE(0x21) // mod date (1980-01-01)
                writeIntLE(0) // crc32: 0 for AE-2
                writeIntLE(payloadLen) // compressed size (incl. AES overhead)
                writeIntLE(payloadLen) // uncompressed size: same for AE-2
                writeShortLE(nameBytes.size)
                writeShortLE(11) // extra field length
                write(nameBytes)
                // AES extra field 0x9901
                writeShortLE(0x9901)
                writeShortLE(7)
                writeShortLE(1) // vendor version AE-1
                write("AE".toByteArray(Charsets.US_ASCII))
                write(3) // AES strength: 3 = 256-bit
                writeShortLE(8) // actual compression method: DEFLATE
            }
            out.write(localHeader.toByteArray())
            out.write(salt)
            out.write(verifier)
            out.write(encrypted)
            out.write(authCode)

            // --- Central directory entry ---
            centralDir.apply {
                writeIntLE(0x02014B50) // signature
                writeShortLE(51) // version made by
                writeShortLE(51) // version needed
                writeShortLE(0x0001) // flag: encrypted
                writeShortLE(99) // method: AES
                writeShortLE(0) // mod time
                writeShortLE(0x21) // mod date
                writeIntLE(0) // crc32: 0 for AE-2
                writeIntLE(payloadLen)
                writeIntLE(payloadLen)
                writeShortLE(nameBytes.size)
                writeShortLE(11) // extra length
                writeShortLE(0) // comment length
                writeShortLE(0) // disk number
                writeShortLE(0) // internal attrs
                writeIntLE(0) // external attrs
                writeIntLE(offset.toInt()) // local header offset
                write(nameBytes)
                writeShortLE(0x9901)
                writeShortLE(7)
                writeShortLE(1)
                write("AE".toByteArray(Charsets.US_ASCII))
                write(3)
                writeShortLE(8)
            }
            offset += localHeader.size() + payloadLen
        }

        // --- End of central directory ---
        val centralBytes = centralDir.toByteArray()
        ByteArrayOutputStream().apply {
            writeIntLE(0x06054B50) // signature
            writeShortLE(0) // disk number
            writeShortLE(0) // central dir start disk
            writeShortLE(entries.size)
            writeShortLE(entries.size)
            writeIntLE(centralBytes.size)
            writeIntLE(offset.toInt())
            writeShortLE(0) // comment length
        }.let { out.write(it.toByteArray()) }
        out.write(centralBytes)
        out.flush()
    }

    private fun pbkdf2Sha1(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, (AES_KEY_LEN + HMAC_KEY_LEN + VERIFIER_LEN) * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream(bytes.size)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShortLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLE(v: Long) = writeIntLE(v.toInt())

    @Suppress("unused")
    private fun crc32(bytes: ByteArray): Int {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toInt()
    }
}
