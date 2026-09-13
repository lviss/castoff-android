package org.castoff.control.fcast

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Max FCast v2 packet size (docs.fcast.org/protocol/v2): 32 KiB. */
const val MAX_PACKET_SIZE = 32 * 1024

/** A decoded frame: an opcode plus its raw (still-encoded) JSON body, if any. */
data class Frame(val opcode: Opcode, val body: ByteArray)

/**
 * FCast v2 wire framing: a 4-byte little-endian length prefix, then a 1-byte
 * opcode, then an optional UTF-8 JSON body. `length` counts the opcode byte
 * plus the body, so a body-less message has `length == 1`. Matches the
 * daemon's framing in daemon/src/fcast.rs (lviss/castoff) byte for byte.
 */
object FCastFrame {

    fun encode(opcode: Opcode, body: ByteArray = ByteArray(0)): ByteArray {
        val len = 1 + body.size
        require(len <= MAX_PACKET_SIZE) { "frame too large: $len bytes" }
        val buffer = ByteBuffer.allocate(4 + len).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(len)
        buffer.put(opcode.value.toByte())
        buffer.put(body)
        return buffer.array()
    }

    fun write(output: OutputStream, opcode: Opcode, body: ByteArray = ByteArray(0)) {
        output.write(encode(opcode, body))
        output.flush()
    }

    /** Reads one frame from [input]. Returns `null` on clean EOF before any new frame starts. */
    fun read(input: InputStream): Frame? {
        val lengthBytes = ByteArray(4)
        val firstByte = input.read()
        if (firstByte == -1) return null
        lengthBytes[0] = firstByte.toByte()
        readFully(input, lengthBytes, 1, 3)

        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (length <= 0 || length > MAX_PACKET_SIZE) {
            throw IOException("invalid FCast frame length $length")
        }

        val payload = ByteArray(length)
        readFully(input, payload, 0, length)

        val opcodeValue = payload[0].toInt() and 0xFF
        val opcode = Opcode.fromInt(opcodeValue) ?: throw IOException("unknown opcode $opcodeValue")
        val body = payload.copyOfRange(1, payload.size)
        return Frame(opcode, body)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, offset + read, length - read)
            if (n == -1) throw EOFException("unexpected end of stream")
            read += n
        }
    }
}
