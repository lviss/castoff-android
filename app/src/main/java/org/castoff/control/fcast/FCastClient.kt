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

    suspend fun setVolume(volume: Double): Result<Frame?> =
        sendCommand(Opcode.SET_VOLUME, json.encodeToString(SetVolumeMessage(volume.coerceIn(0.0, 1.0))))

    suspend fun ping(): Result<Frame?> = sendCommand(Opcode.PING)

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
