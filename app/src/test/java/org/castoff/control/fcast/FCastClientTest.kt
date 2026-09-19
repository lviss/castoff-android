package org.castoff.control.fcast

import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the play-queue commands (castoff's private FCast extension,
 * opcodes 14/16/17) against a real loopback socket standing in for the
 * daemon, the same style [FCastStatusListenerTest] uses for the persistent
 * connection.
 */
class FCastClientTest {

    private val queueBody =
        """{"generationTime":1,"items":[{"url":"https://a"},{"url":"https://b"}],"currentIndex":1}"""

    @Test
    fun `requestQueue sends a bodyless RequestQueue frame and decodes the QueueState reply`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            val frame = FCastFrame.read(socket.getInputStream())
            assertEquals(Opcode.REQUEST_QUEUE, frame?.opcode)
            assertEquals(0, frame?.body?.size)
            FCastFrame.write(socket.getOutputStream(), Opcode.QUEUE_STATE, queueBody.toByteArray())
        }

        val client = FCastClient("127.0.0.1", server.localPort)
        val state = withTimeout(5000) { client.requestQueue() }.getOrThrow()

        assertEquals(2, state.items.size)
        assertEquals("https://b", state.items[1].url)
        assertEquals(1, state.currentIndex)

        serverJob.join()
        server.close()
    }

    @Test
    fun `queueJumpForward sends a bodyless QueueJumpForward frame and decodes the QueueState reply`() =
        runBlocking {
            val server = ServerSocket(0)
            val serverJob = launch(Dispatchers.IO) {
                val socket = server.accept()
                val frame = FCastFrame.read(socket.getInputStream())
                assertEquals(Opcode.QUEUE_JUMP_FORWARD, frame?.opcode)
                FCastFrame.write(socket.getOutputStream(), Opcode.QUEUE_STATE, queueBody.toByteArray())
            }

            val client = FCastClient("127.0.0.1", server.localPort)
            val state = withTimeout(5000) { client.queueJumpForward() }.getOrThrow()

            assertEquals(1, state.currentIndex)

            serverJob.join()
            server.close()
        }

    @Test
    fun `queueJumpBackward sends a bodyless QueueJumpBackward frame and decodes the QueueState reply`() =
        runBlocking {
            val server = ServerSocket(0)
            val serverJob = launch(Dispatchers.IO) {
                val socket = server.accept()
                val frame = FCastFrame.read(socket.getInputStream())
                assertEquals(Opcode.QUEUE_JUMP_BACKWARD, frame?.opcode)
                FCastFrame.write(socket.getOutputStream(), Opcode.QUEUE_STATE, queueBody.toByteArray())
            }

            val client = FCastClient("127.0.0.1", server.localPort)
            val state = withTimeout(5000) { client.queueJumpBackward() }.getOrThrow()

            assertEquals(1, state.currentIndex)

            serverJob.join()
            server.close()
        }
}
