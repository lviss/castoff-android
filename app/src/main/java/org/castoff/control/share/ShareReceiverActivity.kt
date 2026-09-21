package org.castoff.control.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.castoff.control.MainActivity
import org.castoff.control.fcast.FCastClient
import org.castoff.control.settings.HostSettings
import org.castoff.control.settings.TvHost
import org.castoff.control.ui.theme.CastoffControlTheme
import org.castoff.control.upload.ImageUploadClient

/**
 * Handles `ACTION_SEND`/`ACTION_SEND_MULTIPLE` from other apps' share sheets
 * by sending the shared content to the configured TV box: a link (e.g.
 * sharing a YouTube video) becomes an FCast `Play` with that URL, and one or
 * more images are first uploaded to the daemon's `/images` HTTP endpoint
 * (`ImageUploadClient`, a separate transport from FCast's TCP framing --
 * see its doc comment) then, per the user's choice in [ImageSharePromptDialog],
 * added to the play queue (`Play`) and/or tagged for idle-screen wallpaper
 * rotation (`SetImageWallpaper`). The link path stays invisible and finishes
 * immediately; the image path shows that one dialog first since, unlike a
 * link, there is a real choice to make.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare()
    }

    private fun handleShare() {
        val images = extractImages(intent)
        if (images.isNotEmpty()) {
            handleImageShare(images)
            return
        }
        if (isImageShareIntent(intent)) {
            toast("No images found in the shared content")
            finish()
            return
        }

        val url = extractUrl(intent)
        if (url == null) {
            toast("No castable link found in the shared content")
            finish()
            return
        }

        lifecycleScope.launch {
            val hostSettings = HostSettings(applicationContext)
            val host = hostSettings.current()
            if (!host.isConfigured) {
                toast("Set a TV host in Castoff Control first")
                startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java))
                finish()
                return@launch
            }

            val client = FCastClient(host.address, host.port)
            val result = client.play(url)
            result.fold(
                onSuccess = { toast("Casting to ${host.address}") },
                onFailure = { e -> toast("Failed to cast: ${e.message}") },
            )
            finish()
        }
    }

    private fun handleImageShare(images: List<ImageRef>) {
        lifecycleScope.launch {
            val hostSettings = HostSettings(applicationContext)
            val host = hostSettings.current()
            if (!host.isConfigured) {
                toast("Set a TV host in Castoff Control first")
                startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java))
                finish()
                return@launch
            }

            val uiState = mutableStateOf(ImageShareUiState(imageCount = images.size))
            setContent {
                CastoffControlTheme {
                    ImageSharePromptDialog(
                        state = uiState.value,
                        onAddToQueueChange = { uiState.value = uiState.value.copy(addToQueue = it) },
                        onSetAsWallpaperChange = { uiState.value = uiState.value.copy(setAsWallpaper = it) },
                        onConfirm = {
                            val addToQueue = uiState.value.addToQueue
                            val setAsWallpaper = uiState.value.setAsWallpaper
                            uiState.value = uiState.value.copy(isSending = true)
                            lifecycleScope.launch {
                                val (succeeded, failed) = sendImages(images, host, addToQueue, setAsWallpaper)
                                toast(shareResultSummary(succeeded, failed))
                                finish()
                            }
                        },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    /** Uploads then sends each image per the user's choice; never throws -- a per-image failure only counts against it. */
    private suspend fun sendImages(
        images: List<ImageRef>,
        host: TvHost,
        addToQueue: Boolean,
        setAsWallpaper: Boolean,
    ): ShareOutcome = withContext(Dispatchers.IO) {
        val uploadClient = ImageUploadClient(host.address)
        val fcastClient = FCastClient(host.address, host.port)
        var succeeded = 0
        var failed = 0
        for (image in images) {
            val bytes = runCatching {
                contentResolver.openInputStream(image.uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                failed++
                continue
            }

            val uploaded = uploadClient.upload(bytes, image.mimeType).getOrNull()
            if (uploaded == null) {
                failed++
                continue
            }

            var ok = true
            if (addToQueue) {
                ok = fcastClient.play(uploaded.url, uploaded.container).isSuccess && ok
            }
            if (setAsWallpaper) {
                ok = fcastClient.setImageWallpaper(uploaded.id, true).isSuccess && ok
            }
            if (ok) succeeded++ else failed++
        }
        ShareOutcome(succeeded, failed)
    }

    private fun extractUrl(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null

        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            URL_REGEX.find(text)?.let { return it.value }
        }

        val streamUri = intent.getParcelableExtraCompat(Intent.EXTRA_STREAM, Uri::class.java)
        if (streamUri != null && (streamUri.scheme == "http" || streamUri.scheme == "https")) {
            return streamUri.toString()
        }

        return null
    }

    /** Resolves shared image URIs, along with a concrete (non-wildcard) `image` MIME type for each. */
    private fun extractImages(intent: Intent): List<ImageRef> {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            Intent.ACTION_SEND ->
                intent.getParcelableExtraCompat(Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) }
                    ?: emptyList()
            else -> emptyList()
        }
        return uris.mapNotNull { uri -> resolveImageMimeType(uri, intent.type)?.let { ImageRef(uri, it) } }
    }

    /**
     * The daemon rejects a wildcard image content type (a bare "image" MIME wildcard) as unplayable, so
     * a concrete resolved type (`ContentResolver.getType`) is preferred over
     * the intent's own possibly-generalized `type` field.
     */
    private fun resolveImageMimeType(uri: Uri, intentType: String?): String? {
        val resolved = contentResolver.getType(uri)
        if (resolved != null && isImageMimeType(resolved) && resolved != WILDCARD_IMAGE_TYPE) return resolved
        if (intentType != null && isImageMimeType(intentType) && intentType != WILDCARD_IMAGE_TYPE) return intentType
        return null
    }

    /** Whether this intent's action/type indicated an image share even though no usable image was resolved. */
    private fun isImageShareIntent(intent: Intent): Boolean =
        intent.action == Intent.ACTION_SEND_MULTIPLE || isImageMimeType(intent.type)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        val URL_REGEX = Regex("""https?://\S+""")
        const val WILDCARD_IMAGE_TYPE = "image/*"
    }
}

private data class ImageRef(val uri: Uri, val mimeType: String)

private data class ShareOutcome(val succeeded: Int, val failed: Int)

private fun <T : Parcelable> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}

private fun <T : Parcelable> Intent.getParcelableArrayListExtraCompat(name: String, clazz: Class<T>): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayListExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(name)
    }
}
