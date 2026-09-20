package org.castoff.control.upload

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the image upload client against a real loopback socket standing
 * in for the daemon's `/images` endpoint (`upload.rs` in lviss/castoff), the
 * same "real socket, no mocks" style [FCastClientTest] uses for the FCast
 * TCP port -- and the same raw-HTTP-over-a-socket approach the daemon's own
 * `upload.rs` test uses, since pulling in an HTTP client/server framework
 * just for this one endpoint would be more machinery than the request
 * itself.
 */
class ImageUploadClientTest {

    private class ReceivedRequest(val method: String, val path: String, val contentType: String?, val body: ByteArray)

    private fun readLine(input: InputStream): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun readRequest(input: InputStream): ReceivedRequest {
        val requestLine = readLine(input).split(" ")
        val method = requestLine[0]
        val path = requestLine[1]
        var contentType: String? = null
        var contentLength = 0
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val parts = line.split(":", limit = 2)
            when (parts[0].trim().lowercase()) {
                "content-type" -> contentType = parts[1].trim()
                "content-length" -> contentLength = parts[1].trim().toInt()
            }
        }
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n == -1) break
            read += n
        }
        return ReceivedRequest(method, path, contentType, body)
    }

    private fun writeResponse(output: OutputStream, status: Int, statusText: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    @Test
    fun `upload posts the image body with its content type and decodes the id-url-container reply`() = runBlocking {
        val server = ServerSocket(0)
        lateinit var received: ReceivedRequest
        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            received = readRequest(socket.getInputStream())
            writeResponse(
                socket.getOutputStream(),
                200,
                "OK",
                """{"id":"abc123","url":"http://127.0.0.1/images/abc123.png","container":"image/png"}""",
            )
            socket.close()
        }

        val client = ImageUploadClient("127.0.0.1", server.localPort)
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val uploaded = withTimeout(5000) { client.upload(imageBytes, "image/png") }.getOrThrow()

        serverJob.join()
        server.close()

        assertEquals("POST", received.method)
        assertEquals("/images", received.path)
        assertEquals("image/png", received.contentType)
        assertTrue(imageBytes.contentEquals(received.body))

        assertEquals("abc123", uploaded.id)
        assertEquals("http://127.0.0.1/images/abc123.png", uploaded.url)
        assertEquals("image/png", uploaded.container)
    }

    @Test
    fun `upload surfaces a failure for a non-2xx response instead of throwing`() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            val socket = server.accept()
            readRequest(socket.getInputStream())
            writeResponse(socket.getOutputStream(), 400, "Bad Request", "not an image MIME type")
            socket.close()
        }

        val client = ImageUploadClient("127.0.0.1", server.localPort)
        val result = withTimeout(5000) { client.upload(byteArrayOf(1, 2, 3), "text/plain") }

        serverJob.join()
        server.close()

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("expected the status code in: $message", message.contains("400"))
    }

    @Test
    fun `upload surfaces a failure when nothing is listening`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }

        val client = ImageUploadClient("127.0.0.1", port)
        val result = withTimeout(5000) { client.upload(byteArrayOf(1), "image/jpeg") }

        assertTrue(result.isFailure)
    }
}
