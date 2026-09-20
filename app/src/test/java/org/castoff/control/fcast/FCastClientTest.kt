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
 * opcodes 14/16/17/18/19) against a real loopback socket standing in for the
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

    @Test
    fun `clearQueue sends a bodyless ClearQueue frame and decodes the QueueState reply`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            val frame = FCastFrame.read(socket.getInputStream())
            assertEquals(Opcode.CLEAR_QUEUE, frame?.opcode)
            assertEquals(0, frame?.body?.size)
            FCastFrame.write(
                socket.getOutputStream(),
                Opcode.QUEUE_STATE,
                """{"generationTime":2,"items":[],"currentIndex":null}""".toByteArray(),
            )
        }

        val client = FCastClient("127.0.0.1", server.localPort)
        val state = withTimeout(5000) { client.clearQueue() }.getOrThrow()

        assertEquals(emptyList<QueueItemMessage>(), state.items)
        assertEquals(null, state.currentIndex)

        serverJob.join()
        server.close()
    }

    @Test
    fun `play sends the container alongside the url for an uploaded image`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            val frame = FCastFrame.read(socket.getInputStream())
            assertEquals(Opcode.PLAY, frame?.opcode)
            assertEquals(
                """{"container":"image/png","url":"http://tv-box:46900/images/abc.png"}""",
                frame?.body?.toString(Charsets.UTF_8),
            )
            FCastFrame.write(socket.getOutputStream(), Opcode.NONE)
        }

        val client = FCastClient("127.0.0.1", server.localPort)
        withTimeout(5000) {
            client.play(url = "http://tv-box:46900/images/abc.png", container = "image/png")
        }.getOrThrow()

        serverJob.join()
        server.close()
    }

    @Test
    fun `queueJumpToIndex sends a QueueJumpToIndex frame with the index and decodes the QueueState reply`() =
        runBlocking {
            val server = ServerSocket(0)
            val serverJob = launch(Dispatchers.IO) {
                val socket = server.accept()
                val frame = FCastFrame.read(socket.getInputStream())
                assertEquals(Opcode.QUEUE_JUMP_TO_INDEX, frame?.opcode)
                assertEquals("""{"index":0}""", frame?.body?.toString(Charsets.UTF_8))
                FCastFrame.write(socket.getOutputStream(), Opcode.QUEUE_STATE, queueBody.toByteArray())
            }

            val client = FCastClient("127.0.0.1", server.localPort)
            val state = withTimeout(5000) { client.queueJumpToIndex(0) }.getOrThrow()

            assertEquals(1, state.currentIndex)

            serverJob.join()
            server.close()
        }

    @Test
    fun `setImageWallpaper sends a SetImageWallpaper frame and decodes the ImageWallpaperUpdate reply`() =
        runBlocking {
            val server = ServerSocket(0)
            val serverJob = launch(Dispatchers.IO) {
                val socket = server.accept()
                val frame = FCastFrame.read(socket.getInputStream())
                assertEquals(Opcode.SET_IMAGE_WALLPAPER, frame?.opcode)
                assertEquals(
                    """{"id":"abc","wallpaper":true}""",
                    frame?.body?.toString(Charsets.UTF_8),
                )
                val replyBody = """{"generationTime":1,"id":"abc","wallpaper":true}"""
                FCastFrame.write(socket.getOutputStream(), Opcode.IMAGE_WALLPAPER_UPDATE, replyBody.toByteArray())
            }

            val client = FCastClient("127.0.0.1", server.localPort)
            val update = withTimeout(5000) { client.setImageWallpaper("abc", true) }.getOrThrow()

            assertEquals("abc", update.id)
            assertEquals(true, update.wallpaper)

            serverJob.join()
            server.close()
        }

    /**
     * Regression: the daemon's `ClearQueue` handler stops playback first
     * (`Player::queue_clear` -> `stop_locked`), which publishes a
     * `PlaybackUpdate` to every connection via the same writer-lock path the
     * dispatch loop's own `QueueState` reply uses -- so the two frames can
     * arrive on this connection in either order. A client that reads exactly
     * one frame and decodes it as `QueueStateMessage` crashes
     * (`items` required but missing) when the `PlaybackUpdate` wins the race.
     */
    @Test
    fun `clearQueue discards an interleaved PlaybackUpdate before decoding the QueueState reply`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            val frame = FCastFrame.read(socket.getInputStream())
            assertEquals(Opcode.CLEAR_QUEUE, frame?.opcode)
            FCastFrame.write(
                socket.getOutputStream(),
                Opcode.PLAYBACK_UPDATE,
                """{"generationTime":1,"state":0}""".toByteArray(),
            )
            FCastFrame.write(
                socket.getOutputStream(),
                Opcode.QUEUE_STATE,
                """{"generationTime":2,"items":[],"currentIndex":null}""".toByteArray(),
            )
        }

        val client = FCastClient("127.0.0.1", server.localPort)
        val state = withTimeout(5000) { client.clearQueue() }.getOrThrow()

        assertEquals(emptyList<QueueItemMessage>(), state.items)
        assertEquals(null, state.currentIndex)

        serverJob.join()
        server.close()
    }

    @Test
    fun `queueJumpToIndex discards multiple interleaved PlaybackUpdate frames before decoding the QueueState reply`() =
        runBlocking {
            val server = ServerSocket(0)
            val serverJob = launch(Dispatchers.IO) {
                val socket = server.accept()
                val frame = FCastFrame.read(socket.getInputStream())
                assertEquals(Opcode.QUEUE_JUMP_TO_INDEX, frame?.opcode)
                repeat(2) {
                    FCastFrame.write(
                        socket.getOutputStream(),
                        Opcode.PLAYBACK_UPDATE,
                        """{"generationTime":1,"state":1,"time":0.0}""".toByteArray(),
                    )
                }
                FCastFrame.write(socket.getOutputStream(), Opcode.QUEUE_STATE, queueBody.toByteArray())
            }

            val client = FCastClient("127.0.0.1", server.localPort)
            val state = withTimeout(5000) { client.queueJumpToIndex(1) }.getOrThrow()

            assertEquals(1, state.currentIndex)

            serverJob.join()
            server.close()
        }
}
