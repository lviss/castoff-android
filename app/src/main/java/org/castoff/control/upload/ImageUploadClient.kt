package org.castoff.control.upload

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One successfully uploaded image, as returned by the daemon's `/images`
 * endpoint: `url`/`container` are ready to drop straight into an ordinary
 * FCast `Play`, and `id` is what `SetImageWallpaper` references.
 */
@Serializable
data class UploadedImage(
    val id: String,
    val url: String,
    val container: String,
)

/**
 * Client for the castoff daemon's image upload endpoint: a plain local HTTP
 * `POST /images`, deliberately separate from the FCast TCP control port
 * (`FCastClient`) since FCast's own frame cap is 32 KiB, nowhere near enough
 * for a phone photo -- see the daemon's `upload.rs` and README's "Image
 * uploads (private extension)". Defaults to port 46900, the daemon's
 * `CASTOFF_IMAGE_PORT`-overridable default, independent of the FCast control
 * port.
 */
class ImageUploadClient(private val host: String, private val port: Int = DEFAULT_PORT) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun upload(bytes: ByteArray, contentType: String): Result<UploadedImage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL("http://$host:$port/images").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("Content-Type", contentType)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }

                    val status = connection.responseCode
                    if (status !in 200..299) {
                        val error = connection.errorStream?.readBytes()?.toString(Charsets.UTF_8)
                        throw IOException("upload failed ($status): ${error ?: "no response body"}")
                    }
                    val responseBody = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                    json.decodeFromString(UploadedImage.serializer(), responseBody)
                } finally {
                    connection.disconnect()
                }
            }
        }

    companion object {
        /** Matches the daemon's `upload::DEFAULT_UPLOAD_PORT`; the daemon overrides it via `CASTOFF_IMAGE_PORT`. */
        const val DEFAULT_PORT = 46900
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 15000
    }
}
