package org.castoff.control.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.castoff.control.MainActivity
import org.castoff.control.fcast.FCastClient
import org.castoff.control.settings.HostSettings

/**
 * Handles `ACTION_SEND` from other apps' share sheets (e.g. sharing a YouTube
 * link) by sending an FCast `Play` with the shared URL to the configured TV
 * box. Invisible: it does its work and finishes, surfacing outcome via Toast.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare()
    }

    private fun handleShare() {
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        val URL_REGEX = Regex("""https?://\S+""")
    }
}

private fun <T : Parcelable> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}
