package org.castoff.control.fcast

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Minimal FCast v2 sender, talking to the castoff daemon's TCP control port
 * (default 46899). Each command opens a short-lived connection, writes one
 * frame, reads the daemon's immediate reply, then closes -- there is no
 * persistent session to keep alive across this app's activity lifecycle.
 */
class FCastClient(private val host: String, private val port: Int = DEFAULT_PORT) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun play(url: String): Result<Frame?> =
        sendCommand(Opcode.PLAY, json.encodeToString(PlayMessage(url = url)))

    suspend fun pause(): Result<Frame?> = sendCommand(Opcode.PAUSE)

    suspend fun resume(): Result<Frame?> = sendCommand(Opcode.RESUME)

    suspend fun stop(): Result<Frame?> = sendCommand(Opcode.STOP)

    suspend fun seek(timeSeconds: Double): Result<Frame?> =
        sendCommand(Opcode.SEEK, json.encodeToString(SeekMessage(time = timeSeconds)))

    suspend fun setVolume(volume: Double): Result<Frame?> =
        sendCommand(Opcode.SET_VOLUME, json.encodeToString(SetVolumeMessage(volume.coerceIn(0.0, 1.0))))

    suspend fun ping(): Result<Frame?> = sendCommand(Opcode.PING)

    /** castoff private extension: asks for the current play queue; replies with [QueueStateMessage]. */
    suspend fun requestQueue(): Result<QueueStateMessage> = sendQueueCommand(Opcode.REQUEST_QUEUE)

    /** castoff private extension: moves to the next queue item, if any, and plays it. */
    suspend fun queueJumpForward(): Result<QueueStateMessage> = sendQueueCommand(Opcode.QUEUE_JUMP_FORWARD)

    /** castoff private extension: moves to the previous queue item, if any, and plays it. */
    suspend fun queueJumpBackward(): Result<QueueStateMessage> = sendQueueCommand(Opcode.QUEUE_JUMP_BACKWARD)

    /** castoff private extension: empties the play queue, stopping playback first if it was current. */
    suspend fun clearQueue(): Result<QueueStateMessage> = sendQueueCommand(Opcode.CLEAR_QUEUE)

    /** castoff private extension: jumps straight to [index] in the queue; a no-op reply if out of range. */
    suspend fun queueJumpToIndex(index: Int): Result<QueueStateMessage> =
        sendQueueCommand(Opcode.QUEUE_JUMP_TO_INDEX, json.encodeToString(QueueJumpToIndexMessage(index)))

    private suspend fun sendQueueCommand(opcode: Opcode, jsonBody: String? = null): Result<QueueStateMessage> =
        sendCommand(opcode, jsonBody).mapCatching { frame ->
            val body = requireNotNull(frame?.body) { "expected a QueueState reply to $opcode" }
            json.decodeFromString(QueueStateMessage.serializer(), body.toString(Charsets.UTF_8))
        }

    private suspend fun sendCommand(opcode: Opcode, jsonBody: String? = null): Result<Frame?> =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    val body = jsonBody?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    FCastFrame.write(socket.getOutputStream(), opcode, body)
                    FCastFrame.read(socket.getInputStream())
                }
            }
        }

    companion object {
        const val DEFAULT_PORT = 46899
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
    }
}
