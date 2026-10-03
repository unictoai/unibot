package ai.unicto.unibot.share

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
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

    private class KeyMaterial(
        val salt: ByteArray,
        val verifier: ByteArray,
        val aesKey: ByteArray,
        val hmacKey: ByteArray,
    )

    /**
     * Writes a WinZip AES-256 encrypted zip containing [entries] (stored
     * with DEFLATE compression under the AES layer) to [out].
     */
    fun writeEncryptedZip(out: OutputStream, entries: List<Entry>, password: String) {
        val centralDir = ByteArrayOutputStream()
        var offset = 0

        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val encrypted = encryptEntry(entry.bytes, password)
            val payloadLen = SALT_LEN + VERIFIER_LEN + encrypted.data.size + AUTH_CODE_LEN

            val localHeader = buildLocalHeader(nameBytes, payloadLen)
            out.write(localHeader)
            out.write(encrypted.salt)
            out.write(encrypted.verifier)
            out.write(encrypted.data)
            out.write(encrypted.authCode)

            writeCentralEntry(centralDir, nameBytes, payloadLen, offset)
            offset += localHeader.size + payloadLen
        }

        val centralBytes = centralDir.toByteArray()
        out.write(buildEndOfCentralDirectory(entries.size, centralBytes.size, offset))
        out.write(centralBytes)
        out.flush()
    }

    private class EncryptedData(
        val salt: ByteArray,
        val verifier: ByteArray,
        val data: ByteArray,
        val authCode: ByteArray,
    )

    private fun encryptEntry(plain: ByteArray, password: String): EncryptedData {
        val deflated = deflate(plain)
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val keys = deriveKeys(password, salt)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keys.aesKey, "AES"),
            IvParameterSpec(ByteArray(16)),
        )
        val encrypted = cipher.doFinal(deflated)

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keys.hmacKey, "HmacSHA1"))
        val authCode = mac.doFinal(encrypted).copyOfRange(0, AUTH_CODE_LEN)

        return EncryptedData(salt, keys.verifier, encrypted, authCode)
    }

    private fun deriveKeys(password: String, salt: ByteArray): KeyMaterial {
        val totalBits = (AES_KEY_LEN + HMAC_KEY_LEN + VERIFIER_LEN) * 8
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, totalBits)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return KeyMaterial(
            salt = salt,
            verifier = derived.copyOfRange(AES_KEY_LEN + HMAC_KEY_LEN, AES_KEY_LEN + HMAC_KEY_LEN + VERIFIER_LEN),
            aesKey = derived.copyOfRange(0, AES_KEY_LEN),
            hmacKey = derived.copyOfRange(AES_KEY_LEN, AES_KEY_LEN + HMAC_KEY_LEN),
        )
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

    private fun buildLocalHeader(nameBytes: ByteArray, payloadLen: Int): ByteArray {
        val o = ByteArrayOutputStream()
        writeInt(o, 0x04034B50) // signature
        writeShort(o, 51) // version needed (AES)
        writeShort(o, 0x0001) // general purpose flag: encrypted
        writeShort(o, 99) // compression method: AES
        writeShort(o, 0) // mod time
        writeShort(o, 0x21) // mod date (1980-01-01)
        writeInt(o, 0) // crc32: 0 for AE-2
        writeInt(o, payloadLen) // compressed size (incl. AES overhead)
        writeInt(o, payloadLen) // uncompressed size: same for AE-2
        writeShort(o, nameBytes.size)
        writeShort(o, 11) // extra field length
        o.write(nameBytes)
        writeAesExtraField(o)
        return o.toByteArray()
    }

    private fun writeCentralEntry(
        centralDir: ByteArrayOutputStream,
        nameBytes: ByteArray,
        payloadLen: Int,
        offset: Int,
    ) {
        writeInt(centralDir, 0x02014B50) // signature
        writeShort(centralDir, 51) // version made by
        writeShort(centralDir, 51) // version needed
        writeShort(centralDir, 0x0001) // flag: encrypted
        writeShort(centralDir, 99) // method: AES
        writeShort(centralDir, 0) // mod time
        writeShort(centralDir, 0x21) // mod date
        writeInt(centralDir, 0) // crc32: 0 for AE-2
        writeInt(centralDir, payloadLen)
        writeInt(centralDir, payloadLen)
        writeShort(centralDir, nameBytes.size)
        writeShort(centralDir, 11) // extra length
        writeShort(centralDir, 0) // comment length
        writeShort(centralDir, 0) // disk number
        writeShort(centralDir, 0) // internal attrs
        writeInt(centralDir, 0) // external attrs
        writeInt(centralDir, offset) // local header offset
        centralDir.write(nameBytes)
        writeAesExtraField(centralDir)
    }

    private fun writeAesExtraField(o: ByteArrayOutputStream) {
        writeShort(o, 0x9901)
        writeShort(o, 7)
        writeShort(o, 1) // vendor version AE-1
        o.write("AE".toByteArray(Charsets.US_ASCII))
        o.write(3) // AES strength: 3 = 256-bit
        writeShort(o, 8) // actual compression method: DEFLATE
    }

    private fun buildEndOfCentralDirectory(
        entryCount: Int,
        centralSize: Int,
        centralOffset: Int,
    ): ByteArray {
        val o = ByteArrayOutputStream()
        writeInt(o, 0x06054B50) // signature
        writeShort(o, 0) // disk number
        writeShort(o, 0) // central dir start disk
        writeShort(o, entryCount)
        writeShort(o, entryCount)
        writeInt(o, centralSize)
        writeInt(o, centralOffset)
        writeShort(o, 0) // comment length
        return o.toByteArray()
    }

    private fun writeShort(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v ushr 8) and 0xFF)
    }

    private fun writeInt(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v ushr 8) and 0xFF)
        o.write((v ushr 16) and 0xFF)
        o.write((v ushr 24) and 0xFF)
    }
}
