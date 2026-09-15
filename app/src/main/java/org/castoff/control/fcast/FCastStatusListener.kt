package org.castoff.control.fcast

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * What the persistent status connection is doing, reported as it happens so the
 * control screen can tell the user the truth instead of showing a blank
 * playback area. See [FCastStatusListener.events].
 */
sealed interface StatusEvent {
    /** A connection attempt is in flight (also re-emitted on every automatic retry). */
    data object Connecting : StatusEvent

    /** The daemon accepted the TCP connection; pushes can now arrive. */
    data object Connected : StatusEvent

    /**
     * The link is down, either because the attempt never got through or because
     * an established connection was lost. [reason] is plain language and names
     * the host/port and the concrete cause where one is available; the listener
     * keeps retrying with backoff regardless.
     */
    data class Disconnected(val reason: String) : StatusEvent

    /** One daemon-pushed `PlaybackUpdate` frame. */
    data class Playback(val update: PlaybackUpdateMessage) : StatusEvent
}

/**
 * Long-lived status connection to the daemon dedicated to receiving
 * daemon-pushed `PlaybackUpdate` frames, kept open for as long as it's
 * collected (e.g. while the control screen is visible) and reconnected with
 * backoff on error or an unexpected close. It sends only the heartbeat's
 * `Ping`, never a command that changes playback.
 *
 * This is separate from [FCastClient]'s short-lived per-command connections,
 * which close right after each reply and would never see an unprompted push
 * arriving at an unpredictable time -- see [FCastClient]'s class doc and
 * AGENTS.md for why the two are kept apart rather than merged into one
 * shared connection.
 *
 * The connection carries a heartbeat: the daemon sends nothing at all while
 * idle or paused, so silence on its own cannot be told apart from a link the
 * OS silently killed (a backgrounded app's socket, a Wi-Fi transition). Every
 * [heartbeatIntervalMs] the listener sends the protocol's own `Ping`, and the
 * daemon's `Pong` keeps the reader's [livenessTimeoutMs] deadline from
 * expiring on an idle-but-healthy link; a link that stops answering is treated
 * exactly like any other connection error, so it becomes a reported
 * [StatusEvent.Disconnected] instead of an indefinitely "connected" stale
 * screen. The cadence is fixed rather than gated on silence, because a gate
 * that keys off the last received frame is reset by the very `Pong` it caused
 * and then skips a beat, letting the read timeout fire between Pongs; the
 * class body *requires* [livenessTimeoutMs] to exceed [heartbeatIntervalMs]
 * for the same reason. AGENTS.md records why this heartbeat, rather than a
 * plain read timeout, is what makes "connected" honest.
 *
 * FCast v2 has no connect-time "what is your current state?" request: a
 * freshly connected collector learns nothing about playback until the daemon
 * next pushes a state change (or, while playing, its ~1/s tick), so callers
 * must treat a new connection as "playback state unknown", never "idle".
 */
class FCastStatusListener(
    private val host: String,
    private val port: Int = FCastClient.DEFAULT_PORT,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val livenessTimeoutMs: Int = LIVENESS_TIMEOUT_MS,
    private val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS,
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
) {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(livenessTimeoutMs.toLong() > heartbeatIntervalMs) {
            "livenessTimeoutMs ($livenessTimeoutMs) must exceed heartbeatIntervalMs " +
                "($heartbeatIntervalMs) so the reader's deadline cannot expire between heartbeats"
        }
    }

    /**
     * Emits the connection's lifecycle ([StatusEvent.Connecting] -> [StatusEvent.Connected]
     * -> [StatusEvent.Playback]* -> [StatusEvent.Disconnected]* ...) for as long as
     * it's collected, reconnecting with exponential backoff (capped at
     * [maxBackoffMs]) after any connection error, unanswered heartbeat,
     * malformed frame, or clean close. Never completes on its own.
     *
     * Built on [channelFlow] rather than a plain [kotlinx.coroutines.flow.flow]
     * because the reader loop is a blocking (non-suspending) socket read: it
     * has no way to notice collection being cancelled on its own, so
     * `awaitClose` -- which fires as soon as collection is torn down, even
     * mid-read -- is what closes the socket to unblock it, so a cancelled
     * collector (e.g. the control screen going away) stops promptly rather
     * than lingering until the socket times out.
     */
    fun events(): Flow<StatusEvent> = channelFlow {
        val currentSocket = AtomicReference<Socket?>(null)

        val readerJob = launch(Dispatchers.IO) {
            var backoffMs = initialBackoffMs
            while (isActive) {
                emit(StatusEvent.Connecting)
                val socket = Socket()
                currentSocket.set(socket)
                var connected = false
                var heartbeatJob: Job? = null
                try {
                    socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                    connected = true
                    backoffMs = initialBackoffMs
                    socket.soTimeout = livenessTimeoutMs
                    val output = socket.getOutputStream()
                    heartbeatJob = launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(heartbeatIntervalMs)
                            try {
                                FCastFrame.write(output, Opcode.PING)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // A failed write means the link is gone. Close the
                                // socket so the reader's blocking read surfaces it
                                // on the same reported-disconnect-and-retry path as
                                // any other connection error, instead of this child
                                // coroutine's failure cancelling the whole flow.
                                runCatching { socket.close() }
                                break
                            }
                        }
                    }
                    emit(StatusEvent.Connected)

                    val input = socket.getInputStream()
                    while (true) {
                        val frame = FCastFrame.read(input) ?: break
                        if (frame.opcode == Opcode.PLAYBACK_UPDATE) {
                            emit(
                                StatusEvent.Playback(
                                    json.decodeFromString(
                                        PlaybackUpdateMessage.serializer(),
                                        frame.body.toString(Charsets.UTF_8),
                                    ),
                                ),
                            )
                        }
                    }
                    emit(StatusEvent.Disconnected("Lost connection to $host:$port: the daemon closed it"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        val cause = plainConnectionFailureReason(e)
                        emit(
                            StatusEvent.Disconnected(
                                if (connected) {
                                    "Lost connection to $host:$port: $cause"
                                } else {
                                    "Couldn't connect to $host:$port: $cause"
                                },
                            ),
                        )
                    }
                } finally {
                    heartbeatJob?.cancel()
                    runCatching { socket.close() }
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
        }

        awaitClose {
            readerJob.cancel()
            currentSocket.get()?.close()
        }
    }

    /**
     * [events] reduced to just the daemon's pushed playback frames, for callers
     * that only care about playback. A fresh collection opens a fresh
     * connection.
     */
    fun playbackUpdates(): Flow<PlaybackUpdateMessage> =
        events().filterIsInstance<StatusEvent.Playback>().map { it.update }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5000

        /**
         * The reader's per-read deadline on an established link, and the fixed
         * cadence at which the heartbeat sends `Ping`. Every `Ping` elicits a
         * `Pong` that resets the deadline on an otherwise silent link, so the
         * cadence must be strictly shorter than the deadline; the `require` in
         * the class body enforces that instead of relying on two independent
         * literals staying in sync. Both stay in the low tens of seconds so a
         * genuinely dead link is still detected promptly.
         */
        const val LIVENESS_TIMEOUT_MS = 15_000
        const val HEARTBEAT_INTERVAL_MS = 10_000L

        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 15_000L
    }
}

/**
 * A short, plain-language cause for a failed connection attempt, for the
 * control screen to show the user. Deliberately names the common socket
 * failures ("connection refused" when nothing is listening, "timed out" when
 * the host is unreachable or firewalled) rather than dumping a stack trace.
 */
internal fun plainConnectionFailureReason(error: Throwable): String = when (error) {
    is UnknownHostException -> "can't resolve that host name"
    is NoRouteToHostException -> "no route to host"
    is ConnectException -> "connection refused"
    is SocketTimeoutException -> "timed out"
    is SocketException -> error.message?.lowercase() ?: "network error"
    else -> error.message ?: error::class.simpleName?.lowercase() ?: "unknown error"
}

/** [send] that tolerates the channel already being closed by flow teardown. */
private suspend fun ProducerScope<StatusEvent>.emit(event: StatusEvent) {
    try {
        send(event)
    } catch (_: ClosedSendChannelException) {
        // The collector went away (or awaitClose ran); nothing to report to.
    }
}
