package org.castoff.control.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * State for the "what to do with these shared images" prompt: the two
 * independent choices the captain's intent calls for -- add to the play
 * queue (held until the queue advances, like a shared link) and/or tag for
 * idle-screen wallpaper rotation. Neither is required on its own and both
 * may be chosen together; [canSend] enforces "at least one".
 */
data class ImageShareUiState(
    val imageCount: Int,
    val addToQueue: Boolean = true,
    val setAsWallpaper: Boolean = false,
    val isSending: Boolean = false,
) {
    val canSend: Boolean get() = (addToQueue || setAsWallpaper) && !isSending
}

@Composable
fun ImageSharePromptDialog(
    state: ImageShareUiState,
    onAddToQueueChange: (Boolean) -> Unit,
    onSetAsWallpaperChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.isSending) onDismiss() },
        title = {
            Text(
                if (state.imageCount == 1) {
                    "Share image to Castoff"
                } else {
                    "Share ${state.imageCount} images to Castoff"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.addToQueue,
                        onCheckedChange = onAddToQueueChange,
                        enabled = !state.isSending,
                    )
                    Text("Add to queue")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.setAsWallpaper,
                        onCheckedChange = onSetAsWallpaperChange,
                        enabled = !state.isSending,
                    )
                    Text("Set as wallpaper")
                }
                if (state.isSending) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text("Sending…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = state.canSend) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSending) { Text("Cancel") }
        },
    )
}

/** Whether [mimeType] is an `image` MIME type (e.g. `image/jpeg`), as reported by `ContentResolver` or an intent's own type. */
internal fun isImageMimeType(mimeType: String?): Boolean = mimeType?.startsWith("image/") == true

/**
 * The final Toast text for an image-share attempt covering [succeeded] plus
 * [failed] uploads (whichever of add-to-queue/set-wallpaper was requested
 * counts as one success or failure per image, not per FCast command sent).
 */
internal fun shareResultSummary(succeeded: Int, failed: Int): String {
    val total = succeeded + failed
    return when {
        failed == 0 -> if (total == 1) "Shared 1 image" else "Shared $total images"
        succeeded == 0 -> if (total == 1) "Failed to share the image" else "Failed to share $total images"
        else -> "Shared $succeeded of $total images ($failed failed)"
    }
}
