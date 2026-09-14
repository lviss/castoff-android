package org.castoff.control.fcast

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Long-lived, read-only TCP connection to the daemon dedicated to receiving
 * daemon-pushed `PlaybackUpdate` frames, kept open for as long as it's
 * collected (e.g. while the control screen is visible) and reconnected with
 * backoff on error or an unexpected close.
 *
 * This is separate from [FCastClient]'s short-lived per-command connections,
 * which close right after each reply and would never see an unprompted push
 * arriving at an unpredictable time -- see [FCastClient]'s class doc and
 * AGENTS.md for why the two are kept apart rather than merged into one
 * shared connection.
 *
 * Sends nothing after connecting; the daemon's dispatch loop is fine with a
 * connection that only ever reads pushes.
 */
class FCastStatusListener(private val host: String, private val port: Int = FCastClient.DEFAULT_PORT) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Emits each decoded [PlaybackUpdateMessage] pushed by the daemon on this
     * connection; other opcodes are ignored. Never completes on its own --
     * reconnects with exponential backoff (capped at [MAX_BACKOFF_MS]) after
     * any connection error, malformed frame, or clean close.
     *
     * Built on [channelFlow] rather than a plain [kotlinx.coroutines.flow.flow]
     * because the reader loop is a blocking (non-suspending) socket read: it
     * has no way to notice collection being cancelled on its own, so
     * `awaitClose` -- which fires as soon as collection is torn down, even
     * mid-read -- is what closes the socket to unblock it, so a cancelled
     * collector (e.g. the control screen going away) stops promptly rather
     * than lingering until [READ_TIMEOUT_MS]. That timeout otherwise exists
     * only to eventually notice a connection that's gone dead without a
     * clean close (e.g. the network dropping silently), which is treated the
     * same as any other transient error: reconnect.
     */
    fun playbackUpdates(): Flow<PlaybackUpdateMessage> = channelFlow {
        val currentSocket = AtomicReference<Socket?>(null)

        val readerJob = launch(Dispatchers.IO) {
            var backoffMs = INITIAL_BACKOFF_MS
            while (true) {
                try {
                    Socket().also { currentSocket.set(it) }.use { socket ->
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        backoffMs = INITIAL_BACKOFF_MS
                        socket.soTimeout = READ_TIMEOUT_MS
                        val input = socket.getInputStream()
                        while (true) {
                            val frame = FCastFrame.read(input) ?: break
                            if (frame.opcode == Opcode.PLAYBACK_UPDATE) {
                                send(
                                    json.decodeFromString(
                                        PlaybackUpdateMessage.serializer(),
                                        frame.body.toString(Charsets.UTF_8),
                                    ),
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Socket I/O failure, read timeout, or a malformed frame body: fall through
                    // to the backoff below and reconnect.
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }

        awaitClose {
            currentSocket.get()?.close()
            readerJob.cancel()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 30_000
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
