package org.castoff.control.fcast

import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the listener against a real loopback socket (no daemon needed):
 * a fake server plays the role of the castoff daemon, writing frames
 * unprompted the way a daemon push would arrive.
 *
 * Also covers what the control screen now depends on: that every connection
 * failure is *reported* with a plain-language reason rather than swallowed,
 * that the listener reconnects after a dropped link, and that the heartbeat
 * distinguishes an idle-but-alive daemon (silence, which is normal) from a
 * link that is actually dead.
 */
class FCastStatusListenerTest {

    /** Short timeouts/backoff so the liveness and reconnect tests stay fast. */
    private fun listenerFor(
        server: ServerSocket,
        livenessTimeoutMs: Int = 2000,
        heartbeatIntervalMs: Long = 500,
    ) = FCastStatusListener(
        host = "127.0.0.1",
        port = server.localPort,
        connectTimeoutMs = 1000,
        livenessTimeoutMs = livenessTimeoutMs,
        heartbeatIntervalMs = heartbeatIntervalMs,
        initialBackoffMs = 50,
        maxBackoffMs = 200,
    )

    private val playingBody =
        """{"generationTime":1,"state":1,"time":5.0,"duration":100.0,"speed":1.0}"""

    @Test
    fun `reports connecting then connected then the pushed playback update`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            FCastFrame.write(socket.getOutputStream(), Opcode.PLAYBACK_UPDATE, playingBody.toByteArray())
            delay(10_000)
        }

        val events = withTimeout(5000) { listenerFor(server).events().take(3).toList() }

        assertEquals(StatusEvent.Connecting, events[0])
        assertEquals(StatusEvent.Connected, events[1])
        val update = (events[2] as StatusEvent.Playback).update
        assertEquals(PlaybackState.PLAYING, PlaybackState.fromInt(update.state))
        assertEquals(5.0, update.time)
        assertEquals(100.0, update.duration)

        serverJob.cancel()
        server.close()
    }

    @Test
    fun `playbackUpdates convenience emits only playback frames`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            val out = socket.getOutputStream()
            FCastFrame.write(out, Opcode.PONG)
            FCastFrame.write(out, Opcode.PLAYBACK_UPDATE, playingBody.toByteArray())
            delay(10_000)
        }

        val update = withTimeout(5000) { listenerFor(server).playbackUpdates().take(1).toList() }.single()

        assertEquals(PlaybackState.PLAYING, PlaybackState.fromInt(update.state))

        serverJob.cancel()
        server.close()
    }

    @Test
    fun `a refused connection is reported in plain language, naming the host`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // free the port so nothing is listening
        val listener = FCastStatusListener(
            host = "127.0.0.1",
            port = port,
            connectTimeoutMs = 1000,
            initialBackoffMs = 50,
            maxBackoffMs = 50,
        )

        val events = withTimeout(5000) { listener.events().take(2).toList() }

        assertEquals(StatusEvent.Connecting, events[0])
        val reason = (events[1] as StatusEvent.Disconnected).reason
        assertTrue("expected the host in: $reason", reason.contains("127.0.0.1:$port"))
        assertTrue("expected a connect failure in: $reason", reason.contains("Couldn't connect"))
        assertTrue("expected the concrete cause in: $reason", reason.contains("connection refused"))
    }

    @Test
    fun `the daemon closing the connection is reported as a lost link`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            server.accept().close()
        }

        val events = withTimeout(5000) { listenerFor(server).events().take(3).toList() }

        assertEquals(StatusEvent.Connecting, events[0])
        assertEquals(StatusEvent.Connected, events[1])
        val reason = (events[2] as StatusEvent.Disconnected).reason
        assertTrue("expected a lost link in: $reason", reason.contains("Lost connection"))
        assertTrue("expected the close in: $reason", reason.contains("closed"))

        serverJob.cancel()
        server.close()
    }

    @Test
    fun `reconnects after the daemon drops the link and reports the second connection`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            server.accept().close()
            val second = server.accept()
            FCastFrame.write(second.getOutputStream(), Opcode.PLAYBACK_UPDATE, playingBody.toByteArray())
            delay(10_000)
        }

        val events = withTimeout(10_000) { listenerFor(server).events().take(6).toList() }

        assertEquals(StatusEvent.Connecting, events[0])
        assertEquals(StatusEvent.Connected, events[1])
        assertTrue(events[2] is StatusEvent.Disconnected)
        assertEquals(StatusEvent.Connecting, events[3])
        assertEquals(StatusEvent.Connected, events[4])
        assertTrue("expected the reconnected link to deliver the push", events[5] is StatusEvent.Playback)

        serverJob.cancel()
        server.close()
    }

    @Test
    fun `an idle daemon that answers the heartbeat stays connected`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            while (true) {
                val frame = FCastFrame.read(input) ?: break
                if (frame.opcode == Opcode.PING) {
                    FCastFrame.write(socket.getOutputStream(), Opcode.PONG)
                }
            }
        }

        val collected = mutableListOf<StatusEvent>()
        val collectJob = launch { listenerFor(server, livenessTimeoutMs = 300, heartbeatIntervalMs = 200).events().collect { collected.add(it) } }
        // Long enough for several heartbeats at this cadence: without the
        // heartbeat the read timeout would have fired by now.
        delay(1500)
        collectJob.cancelAndJoin()

        assertTrue("expected Connected in $collected", collected.contains(StatusEvent.Connected))
        assertFalse(
            "an idle daemon that answers Pings must not look disconnected: $collected",
            collected.any { it is StatusEvent.Disconnected },
        )

        serverJob.cancel()
        server.close()
    }

    @Test
    fun `a heartbeat that goes unanswered drops and reports the link`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            // Accept and then go silent: the socket stays open, so only the
            // heartbeat can reveal that nothing is on the other end.
            server.accept()
            delay(10_000)
        }

        val events = withTimeout(5000) {
            listenerFor(server, livenessTimeoutMs = 500, heartbeatIntervalMs = 150).events().take(3).toList()
        }

        assertEquals(StatusEvent.Connecting, events[0])
        assertEquals(StatusEvent.Connected, events[1])
        val reason = (events[2] as StatusEvent.Disconnected).reason
        assertTrue("expected a lost link in: $reason", reason.contains("Lost connection"))
        assertTrue("expected the timeout in: $reason", reason.contains("timed out"))

        serverJob.cancel()
        server.close()
    }

    @Test
    fun `plain language reasons cover the common socket failures`() {
        assertEquals(
            "can't resolve that host name",
            plainConnectionFailureReason(UnknownHostException("tv-box.local")),
        )
        assertEquals("connection refused", plainConnectionFailureReason(ConnectException("Connection refused")))
        assertEquals("timed out", plainConnectionFailureReason(SocketTimeoutException("connect timed out")))
        assertEquals("socket closed", plainConnectionFailureReason(SocketException("Socket closed")))
        assertEquals("boom", plainConnectionFailureReason(IllegalStateException("boom")))
    }
}
