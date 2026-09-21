package org.castoff.control.fcast

import java.io.InputStream
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

    suspend fun play(url: String, container: String? = null): Result<Frame?> =
        sendCommand(Opcode.PLAY, json.encodeToString(PlayMessage(container = container, url = url)))

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

    /**
     * Queue commands reply with [QueueStateMessage], but not always as the
     * very next frame: e.g. `ClearQueue` stops playback first, which
     * publishes a `PlaybackUpdate` to every connection via the same
     * writer-lock path the dispatch loop's own `QueueState` reply uses, so
     * the two can arrive in either order on this connection. Reading exactly
     * one frame and decoding it as `QueueStateMessage` crashes when the
     * `PlaybackUpdate` wins that race. Mirrors the daemon's own
     * `read_queue_state` test helper (daemon/src/main.rs): keep reading,
     * discarding any interleaved `PlaybackUpdate`, until `QueueState` arrives.
     */
    private suspend fun sendQueueCommand(opcode: Opcode, jsonBody: String? = null): Result<QueueStateMessage> =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = READ_TIMEOUT_MS
                    val body = jsonBody?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    FCastFrame.write(socket.getOutputStream(), opcode, body)
                    readQueueStateReply(socket.getInputStream(), opcode)
                }
            }
        }

    private fun readQueueStateReply(input: InputStream, opcode: Opcode): QueueStateMessage {
        while (true) {
            val frame = requireNotNull(FCastFrame.read(input)) {
                "connection closed while awaiting a QueueState reply to $opcode"
            }
            if (frame.opcode == Opcode.PLAYBACK_UPDATE) continue
            require(frame.opcode == Opcode.QUEUE_STATE) {
                "expected a QueueState reply to $opcode, got ${frame.opcode}"
            }
            return json.decodeFromString(QueueStateMessage.serializer(), frame.body.toString(Charsets.UTF_8))
        }
    }

    /**
     * castoff private extension: tags or untags an uploaded image (by the
     * `id` the `/images` HTTP upload endpoint returned) for idle-screen
     * wallpaper rotation; replies with [ImageWallpaperUpdateMessage].
     */
    suspend fun setImageWallpaper(id: String, wallpaper: Boolean): Result<ImageWallpaperUpdateMessage> =
        sendCommand(Opcode.SET_IMAGE_WALLPAPER, json.encodeToString(SetImageWallpaperMessage(id, wallpaper)))
            .mapCatching { frame ->
                val body = requireNotNull(frame?.body) { "expected an ImageWallpaperUpdate reply to SetImageWallpaper" }
                json.decodeFromString(ImageWallpaperUpdateMessage.serializer(), body.toString(Charsets.UTF_8))
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
