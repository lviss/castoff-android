package org.castoff.control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    /**
     * Whether the daemon has pushed *any* playback state since this connection
     * was established. False right after connecting (its current state is then
     * genuinely unknown) and while no link exists; lets the screen say so
     * instead of looking like a working-but-idle player.
     */
    val hasPlaybackReport: Boolean = false,
)

@Composable
fun ControlScreen(
    state: ControlUiState,
    connection: ConnectionStatus,
    onConnect: () -> Unit,
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

        ConnectionRow(connection = connection, onConnect = onConnect)

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
                    text = buildString {
                        append(formatPlaybackTime(position))
                        append(" / ")
                        append(formatPlaybackTime(duration))
                        // Only live while a link exists and it has reported this
                        // connection's state: a dropped link (or one just
                        // re-established) leaves the last thing the daemon said, not a
                        // live reading, so say so rather than let a frozen bar read as
                        // a current one.
                        if (connection.phase != ConnectionPhase.CONNECTED || !state.hasPlaybackReport) {
                            append("  (last known)")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (state.isPlaying && state.hasPlaybackReport) {
                // Honest middle ground: the daemon says something is playing but
                // has not reported a timeline (a web page has none, and mpv takes a
                // moment to resolve one), so show that rather than an empty area.
                // Gated on hasPlaybackReport so an optimistic local Resume cannot
                // make this claim on behalf of a daemon that has said nothing.
                Text(
                    text = "Playing — the daemon hasn't reported a duration yet",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (connection.phase == ConnectionPhase.CONNECTED && !state.hasPlaybackReport) {
                // Connecting tells us nothing about playback on its own: the daemon
                // pushes only on a state change, plus about once a second while
                // playing. Say so instead of implying the player is idle.
                Text(
                    text = "No playback state from the daemon yet — it reports on change, and about once a second while playing.",
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

/**
 * The always-visible answer to "is this app talking to the daemon?": one
 * coloured dot and a plain-language line, plus the concrete failure reason and
 * a Connect action when the link is down. Never a spinner that never resolves --
 * [ConnectionPhase.CONNECTING] is a distinct, momentary state, and every other
 * phase states its outcome in words.
 */
@Composable
private fun ConnectionRow(connection: ConnectionStatus, onConnect: () -> Unit) {
    val dotColor: Color = when (connection.phase) {
        ConnectionPhase.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionPhase.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionPhase.NOT_CONNECTED -> MaterialTheme.colorScheme.error
        ConnectionPhase.NOT_CONFIGURED -> MaterialTheme.colorScheme.outline
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color = dotColor, shape = CircleShape),
            )
            Text(text = connection.headline, style = MaterialTheme.typography.titleSmall)
        }
        connection.detail?.let { detail ->
            Text(text = detail, style = MaterialTheme.typography.bodySmall)
        }
        if (connection.canConnect) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text("Connect")
            }
        }
    }
}

private fun formatPlaybackTime(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val minutes = total / 60
    val secs = total % 60
    return "%d:%02d".format(minutes, secs)
}
