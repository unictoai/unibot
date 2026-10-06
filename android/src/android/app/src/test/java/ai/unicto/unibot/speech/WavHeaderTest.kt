package ai.unicto.unibot.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Voice theme item 32: the WAV writer produces a valid 44-byte header with
 * correct size fields, so whisper.cpp and provider endpoints accept the file.
 */
class WavHeaderTest {

    @Test
    fun headerIs44BytesWithMagic() {
        val out = ByteArrayOutputStream()
        WavHeader.write(out, dataBytes = 100, sampleRate = 16_000)
        val bytes = out.toByteArray()
        assertEquals(44, bytes.size)
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
        assertEquals("WAVE", String(bytes.copyOfRange(8, 12)))
        assertEquals("data", String(bytes.copyOfRange(36, 40)))
    }

    @Test
    fun sizeFieldsAreLittleEndianCorrect() {
        val out = ByteArrayOutputStream()
        WavHeader.write(out, dataBytes = 32000, sampleRate = 16_000)
        val b = out.toByteArray()
        fun le32(off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
        // RIFF chunk size = 36 + data; data chunk size = data.
        assertEquals(36 + 32000, le32(4))
        assertEquals(32000, le32(40))
        // 16-bit mono @ 16kHz: byte rate 32000, block align 2.
        assertEquals(32000, le32(28))
    }

    @Test
    fun wavRoundTripThroughReader() {
        val pcm = shortArrayOf(0, 1000, -1000, 32767, -32768)
        val out = ByteArrayOutputStream()
        WavHeader.write(out, dataBytes = pcm.size * 2, sampleRate = 16_000)
        for (s in pcm) {
            out.write(s.toInt() and 0xFF)
            out.write((s.toInt() shr 8) and 0xFF)
        }
        val file = java.io.File.createTempFile("note", ".wav")
        try {
            file.writeBytes(out.toByteArray())
            val read = VoiceNoteTranscriber.readWavPcm16(file)
            assertArrayEquals(pcm.toTypedArray(), read!!.toTypedArray())
        } finally {
            file.delete()
        }
    }
}
