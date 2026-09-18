package org.castoff.control.fcast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies the wire framing byte-for-byte against the daemon's own manual
 * protocol test in castoff's README (`daemon/src/fcast.rs`): a bodyless
 * `Ping` (opcode 12) encodes as length=1 -> `01 00 00 00 0c`.
 */
class FCastFrameTest {

    @Test
    fun `encodes bodyless frame matching daemon README example`() {
        val encoded = FCastFrame.encode(Opcode.PING)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x0c), encoded)
    }

    @Test
    fun `encodes frame with JSON body using little-endian length prefix`() {
        val body = """{"volume":0.5}""".toByteArray(Charsets.UTF_8)
        val encoded = FCastFrame.encode(Opcode.SET_VOLUME, body)

        val expectedLength = 1 + body.size
        assertEquals(expectedLength, encoded[0].toInt())
        assertEquals(0, encoded[1].toInt())
        assertEquals(0, encoded[2].toInt())
        assertEquals(0, encoded[3].toInt())
        assertEquals(Opcode.SET_VOLUME.value, encoded[4].toInt())
        assertArrayEquals(body, encoded.copyOfRange(5, encoded.size))
    }

    @Test
    fun `encodes a seek frame with the time field as its body`() {
        val body = """{"time":42.5}""".toByteArray(Charsets.UTF_8)
        val encoded = FCastFrame.encode(Opcode.SEEK, body)

        assertEquals(Opcode.SEEK.value, encoded[4].toInt())
        assertArrayEquals(body, encoded.copyOfRange(5, encoded.size))
    }

    @Test
    fun `round trips a frame through write and read`() {
        val body = """{"url":"https://example.com/video.mp4"}""".toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        FCastFrame.write(output, Opcode.PLAY, body)

        val frame = FCastFrame.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(Opcode.PLAY, frame?.opcode)
        assertArrayEquals(body, frame?.body)
    }

    @Test
    fun `read returns null on clean eof before a new frame`() {
        val frame = FCastFrame.read(ByteArrayInputStream(ByteArray(0)))
        assertNull(frame)
    }

    @Test
    fun `read rejects an unknown opcode`() {
        val bytes = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x63) // opcode 99, unknown
        assertThrows(java.io.IOException::class.java) {
            FCastFrame.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `read rejects a frame exceeding the max packet size`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x01, 0x00) // length = 0x00010000, over 32 KiB
        assertThrows(java.io.IOException::class.java) {
            FCastFrame.read(ByteArrayInputStream(bytes))
        }
    }
}
