package ai.unicto.unibot.ui.privacy

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Calendar
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [v12-D] WinZip AES-256 (AE-2) encrypted-zip writer, used for
 * password-protected chat exports. No new dependencies — javax.crypto only.
 *
 * The output is a genuine `.zip` (compression method 99 + the `0x9901` AES
 * extra field), so any standard tool that speaks WinZip AES (7-Zip, WinZip,
 * pyzipper…) opens it with the password. Traditional ZipCrypto is
 * deliberately NOT used — it is trivially crackable.
 *
 * Wire format (AE-2), per entry:
 *   - PBKDF2-HMAC-SHA1(password UTF-8, 16-byte salt, 1000 rounds) -> 66 bytes:
 *     32-byte AES key | 32-byte HMAC key | 2-byte password-verification value.
 *   - Payload deflated (raw deflate), then AES-256-CTR. The 128-bit counter
 *     is little-endian and its FIRST value is 1, not 0 — verified against
 *     two independent implementations: 7-Zip's `C/Aes.c` `AesCtr_Code`
 *     (pre-increments `p[0]` before the first `Aes_Encode`) and pyzipper's
 *     `Counter.new(nbits=128, little_endian=True)` (default initial_value=1).
 *   - Data layout: salt | password-verification | ciphertext |
 *     HMAC-SHA1-80(auth key, ciphertext).
 *   - Headers: method 99, encrypted bit set, CRC 0 (AE-2), version-needed 51,
 *     extra field `0x9901` = {version 2, "AE", strength 3 (256-bit),
 *     actual method 8 (deflate)}.
 *
 * The algorithm was validated off-device: a Java port with identical JCE
 * calls round-tripped through pyzipper (decrypt with correct password,
 * reject with wrong password, byte-identical content) — see the [v12-D]
 * work notes. This Kotlin is a line-for-line port of that validated code.
 */
object AesZip {

    /** One file to store in the archive. */
    data class Entry(val name: String, val data: ByteArray)

    private const val SALT_LEN = 16
    private const val VERIFY_LEN = 2
    private const val AUTH_LEN = 10 // HMAC-SHA1 truncated to 80 bits
    private const val ITERATIONS = 1000
    private const val AES_EXTRA_ID = 0x9901

    /**
     * Write [entries] as a WinZip-AES-256 encrypted zip to [out].
     * Throws on crypto/IO failure; [out] is NOT closed.
     */
    fun writeEncryptedZip(out: OutputStream, entries: List<Entry>, password: String) {
        require(password.isNotEmpty()) { "password must not be empty" }
        val pwdBytes = password.toByteArray(Charsets.UTF_8)
        val dos = DataOutputStream(out)
        val offsets = ArrayList<Long>(entries.size)
        val compSizes = ArrayList<Long>(entries.size)
        val (dosTime, dosDate) = dosDateTime()
        val random = SecureRandom()

        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val deflated = deflate(entry.data)

            val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
            val keyMaterial = pbkdf2Sha1(pwdBytes, salt, ITERATIONS, 66)
            val encKey = keyMaterial.copyOfRange(0, 32)
            val macKey = keyMaterial.copyOfRange(32, 64)
            val verify = keyMaterial.copyOfRange(64, 66)

            val cipherText = ctrEncrypt(encKey, deflated)
            val auth = hmacSha1(macKey, cipherText).copyOf(AUTH_LEN)
            val compSize = (SALT_LEN + VERIFY_LEN + cipherText.size + AUTH_LEN).toLong()

            offsets += dos.size().toLong()
            compSizes += compSize

            // -- local file header ---------------------------------------
            dos.writeIntLE(0x04034b50)
            dos.writeShortLE(51) // version needed
            dos.writeShortLE(0x0001) // general-purpose flag: encrypted
            dos.writeShortLE(99) // compression method: WinZip AES
            dos.writeShortLE(dosTime)
            dos.writeShortLE(dosDate)
            dos.writeIntLE(0) // CRC: AE-2 stores 0
            dos.writeIntLE(compSize.toInt())
            dos.writeIntLE(entry.data.size)
            dos.writeShortLE(nameBytes.size)
            dos.writeShortLE(11) // AES extra field length
            dos.write(nameBytes)
            writeAesExtra(dos)
            dos.write(salt)
            dos.write(verify)
            dos.write(cipherText)
            dos.write(auth)
        }

        // -- central directory -------------------------------------------
        val centralStart = dos.size().toLong()
        for ((index, entry) in entries.withIndex()) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val compSize = compSizes[index]
            dos.writeIntLE(0x02014b50)
            dos.writeShortLE((3 shl 8) or 51) // made by: Unix, version 5.1
            dos.writeShortLE(51)
            dos.writeShortLE(0x0001)
            dos.writeShortLE(99)
            dos.writeShortLE(dosTime)
            dos.writeShortLE(dosDate)
            dos.writeIntLE(0)
            dos.writeIntLE(compSize.toInt())
            dos.writeIntLE(entry.data.size)
            dos.writeShortLE(nameBytes.size)
            dos.writeShortLE(11)
            dos.writeShortLE(0) // comment length
            dos.writeShortLE(0) // disk number
            dos.writeShortLE(0) // internal attributes
            dos.writeIntLE(0) // external attributes
            dos.writeIntLE(offsets[index].toInt())
            dos.write(nameBytes)
            writeAesExtra(dos)
        }
        val centralSize = dos.size().toLong() - centralStart

        // -- end of central directory ------------------------------------
        dos.writeIntLE(0x06054b50)
        dos.writeShortLE(0)
        dos.writeShortLE(0)
        dos.writeShortLE(entries.size)
        dos.writeShortLE(entries.size)
        dos.writeIntLE(centralSize.toInt())
        dos.writeIntLE(centralStart.toInt())
        dos.writeShortLE(0)
        dos.flush()
    }

    private fun writeAesExtra(dos: DataOutputStream) {
        dos.writeShortLE(AES_EXTRA_ID)
        dos.writeShortLE(7)
        dos.writeShortLE(2) // AE-2: no CRC
        dos.writeByte('A'.code)
        dos.writeByte('E'.code)
        dos.writeByte(3) // 256-bit strength
        dos.writeShortLE(8) // actual compression method: deflate
    }

    /**
     * AES-256-CTR with a 128-bit little-endian counter whose first value is
     * 1 (see the header KDoc for why — 7-Zip and pyzipper agree).
     * Implemented block-by-block over AES/ECB so the counter endianness is
     * explicit and provider-independent (JCE CTR uses big-endian counters).
     */
    private fun ctrEncrypt(encKey: ByteArray, data: ByteArray): ByteArray {
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"))
        val out = ByteArray(data.size)
        val counter = ByteArray(16)
        val blocks = (data.size + 15) / 16
        for (b in 0 until blocks) {
            counter.fill(0)
            var v = b.toLong() + 1 // first counter value is 1 (see KDoc)
            for (i in 0 until 8) {
                counter[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            val keystream = ecb.doFinal(counter)
            val off = b * 16
            val n = minOf(16, data.size - off)
            for (i in 0 until n) {
                out[off + i] = (data[off + i].toInt() xor keystream[i].toInt()).toByte()
            }
        }
        return out
    }

    private fun pbkdf2Sha1(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(password, "HmacSHA1"))
        val dk = ByteArray(dkLen)
        val block = ByteArray(salt.size + 4)
        salt.copyInto(block)
        val blocks = (dkLen + 19) / 20
        for (i in 1..blocks) {
            block[salt.size] = (i ushr 24).toByte()
            block[salt.size + 1] = (i ushr 16).toByte()
            block[salt.size + 2] = (i ushr 8).toByte()
            block[salt.size + 3] = i.toByte()
            var u = mac.doFinal(block)
            val t = u.clone()
            for (round in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val off = (i - 1) * 20
            val n = minOf(20, dkLen - off)
            t.copyInto(dk, off, 0, n)
        }
        return dk
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // raw deflate
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream(data.size)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun dosDateTime(): Pair<Int, Int> {
        val c = Calendar.getInstance()
        val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or
            (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        val date = ((c.get(Calendar.YEAR) - 1980) shl 9) or
            ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
        return time to date
    }

    private fun DataOutputStream.writeShortLE(v: Int) {
        writeByte(v and 0xFF)
        writeByte((v ushr 8) and 0xFF)
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        writeByte(v and 0xFF)
        writeByte((v ushr 8) and 0xFF)
        writeByte((v ushr 16) and 0xFF)
        writeByte((v ushr 24) and 0xFF)
    }
}
