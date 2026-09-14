package org.castoff.control.fcast

import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the listener against a real loopback socket (no daemon needed):
 * a fake server plays the role of the castoff daemon, writing frames
 * unprompted the way a daemon push would arrive.
 */
class FCastStatusListenerTest {

    @Test
    fun `decodes a playback update pushed on the connection`() = runBlocking {
        val server = ServerSocket(0)
        val listener = FCastStatusListener("127.0.0.1", server.localPort)

        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            val body = """{"generationTime":1,"state":1,"time":5.0,"duration":100.0,"speed":1.0}"""
            FCastFrame.write(socket.getOutputStream(), Opcode.PLAYBACK_UPDATE, body.toByteArray())
        }

        val update = withTimeout(5000) { listener.playbackUpdates().first() }

        assertEquals(PlaybackState.PLAYING, PlaybackState.fromInt(update.state))
        assertEquals(5.0, update.time)
        assertEquals(100.0, update.duration)

        serverJob.cancel()
        server.close()
    }

    @Test
    fun `ignores opcodes other than playback update`() = runBlocking {
        val server = ServerSocket(0)
        val listener = FCastStatusListener("127.0.0.1", server.localPort)

        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            val out = socket.getOutputStream()
            FCastFrame.write(out, Opcode.PONG)
            FCastFrame.write(out, Opcode.PLAYBACK_UPDATE, """{"generationTime":2,"state":2,"time":3.0}""".toByteArray())
        }

        val update = withTimeout(5000) { listener.playbackUpdates().first() }

        assertEquals(PlaybackState.PAUSED, PlaybackState.fromInt(update.state))

        serverJob.cancel()
        server.close()
    }
}
