package org.castoff.control.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** UI-only snapshot of what the control screen shows; owned/updated by the caller. */
data class ControlUiState(
    val hostAddress: String,
    val hostPort: String,
    val isPlaying: Boolean,
    val volume: Float,
    val statusMessage: String?,
    /** Last known/interpolated playback position, from daemon-pushed `PlaybackUpdate`s. */
    val positionSeconds: Float? = null,
    /** Last known playback duration, from daemon-pushed `PlaybackUpdate`s. */
    val durationSeconds: Float? = null,
)

@Composable
fun ControlScreen(
    state: ControlUiState,
    onHostAddressChange: (String) -> Unit,
    onHostPortChange: (String) -> Unit,
    onSaveHost: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onStop: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(text = "Castoff Control", style = MaterialTheme.typography.headlineSmall)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "TV box", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.hostAddress,
                onValueChange = onHostAddressChange,
                label = { Text("Host or IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.hostPort,
                onValueChange = onHostPortChange,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onSaveHost, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Playback", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onPlayPauseToggle) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Resume",
                    )
                    Text(text = if (state.isPlaying) "  Pause" else "  Resume")
                }
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(imageVector = Icons.Filled.Stop, contentDescription = "Stop")
                    Text("  Stop")
                }
            }

            val duration = state.durationSeconds
            if (duration != null && duration > 0f) {
                val position = (state.positionSeconds ?: 0f).coerceIn(0f, duration)
                Slider(
                    value = position,
                    onValueChange = {},
                    valueRange = 0f..duration,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${formatPlaybackTime(position)} / ${formatPlaybackTime(duration)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Volume", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                Slider(
                    value = state.volume,
                    onValueChange = onVolumeChange,
                    onValueChangeFinished = onVolumeChangeFinished,
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp),
                )
            }
        }

        state.statusMessage?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatPlaybackTime(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val minutes = total / 60
    val secs = total % 60
    return "%d:%02d".format(minutes, secs)
}
