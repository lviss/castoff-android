package org.castoff.control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One entry in the play queue list, as shown on the control screen.
 * Mirrors the daemon's `QueueItemMessage`: [title]/[durationSecs] are `null`
 * until the daemon's background lookup resolves them (or forever, if it
 * fails or the item isn't a lookup-able URL), and may arrive later via a
 * `QueueState` push for an item already shown.
 */
data class QueueItemUi(val url: String, val title: String? = null, val durationSecs: Double? = null)

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
     * Position the user is currently dragging the playback slider to, shown in
     * place of [positionSeconds] while non-null so the thumb doesn't fight the
     * position ticking forward underneath the drag. Set on every drag tick and
     * cleared as soon as the drag finishes, even when no seek command is sent
     * (e.g. an unparseable host field), so a dropped command can't leave the
     * thumb frozen at the dragged position.
     */
    val seekPositionSeconds: Float? = null,
    /**
     * Whether the daemon has pushed *any* playback state since this connection
     * was established. False right after connecting (its current state is then
     * genuinely unknown) and while no link exists; lets the screen say so
     * instead of looking like a working-but-idle player.
     */
    val hasPlaybackReport: Boolean = false,
    /** The play queue, in order, from the daemon's most recent `QueueState`. */
    val queueItems: List<QueueItemUi> = emptyList(),
    /**
     * Index into [queueItems] of the current (playing/paused/most-recently-played)
     * item, straight from the daemon's `QueueState` -- `null` when the queue is
     * empty or nothing has ever played from it. Next/Previous enablement is
     * derived from this and [queueItems]' bounds, not guessed from a local count.
     */
    val queueCurrentIndex: Int? = null,
) {
    /** Whether there is a next queue item to jump forward to. */
    val canQueueJumpForward: Boolean
        get() = queueCurrentIndex != null && queueCurrentIndex < queueItems.lastIndex

    /** Whether there is a previous queue item to jump backward to. */
    val canQueueJumpBackward: Boolean
        get() = queueCurrentIndex != null && queueCurrentIndex > 0
}

/**
 * The Control tab: the play queue (scrollable, taking whatever vertical space
 * it's given) with the playback controls pinned below it -- prev/play-pause/
 * stop/next, seek, volume -- so they stay visible and reachable regardless of
 * how far the queue is scrolled. The shared connection indicator and the host
 * settings now live outside this screen (see [org.castoff.control.ui.CastoffApp]
 * and [SettingsScreen]).
 */
@Composable
fun ControlScreen(
    state: ControlUiState,
    connection: ConnectionStatus,
    onPlayPauseToggle: () -> Unit,
    onStop: () -> Unit,
    onQueueJumpBackward: () -> Unit,
    onQueueJumpForward: () -> Unit,
    onClearQueue: () -> Unit,
    onQueueItemClick: (Int) -> Unit,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.queueItems.isNotEmpty()) {
            QueueList(
                items = state.queueItems,
                currentIndex = state.queueCurrentIndex,
                onClearQueue = onClearQueue,
                onItemClick = onQueueItemClick,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onQueueJumpBackward, enabled = state.canQueueJumpBackward) {
                    Icon(imageVector = Icons.Filled.SkipPrevious, contentDescription = "Previous")
                }
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
                IconButton(onClick = onQueueJumpForward, enabled = state.canQueueJumpForward) {
                    Icon(imageVector = Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }

            val duration = state.durationSeconds
            if (duration != null && duration > 0f) {
                val position = (state.seekPositionSeconds ?: state.positionSeconds ?: 0f).coerceIn(0f, duration)
                Slider(
                    value = position,
                    onValueChange = onSeek,
                    onValueChangeFinished = onSeekFinished,
                    valueRange = 0f..duration,
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

            state.statusMessage?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


/**
 * The play queue, in order, with the current item bolded so it's
 * distinguishable from the ones still waiting. Fed live from the daemon's
 * `QueueState` pushes (`FCastStatusListener.queueUpdates`) plus an initial
 * `RequestQueue` on connect. Tapping a row jumps straight to it
 * ([onItemClick]) without disturbing the Next/Previous controls above.
 */
@Composable
private fun QueueList(
    items: List<QueueItemUi>,
    currentIndex: Int?,
    onClearQueue: () -> Unit,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Queue", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onClearQueue) {
                Icon(imageVector = Icons.Filled.ClearAll, contentDescription = "Clear queue")
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items.size) { index ->
                val item = items[index]
                val isCurrent = index == currentIndex
                val color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                Column(modifier = Modifier.clickable { onItemClick(index) }) {
                    Text(
                        text = queueItemDisplayTitle(item),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = color,
                    )
                    queueItemDisplayDuration(item)?.let { duration ->
                        Text(
                            text = duration,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                    }
                }
            }
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
internal fun ConnectionRow(connection: ConnectionStatus, onConnect: () -> Unit) {
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

/** The queue row's title text: the daemon-resolved title, or the URL while it's still unresolved. */
internal fun queueItemDisplayTitle(item: QueueItemUi): String = item.title ?: item.url

/** The queue row's length text (formatted like the playback progress display), or `null` while unresolved. */
internal fun queueItemDisplayDuration(item: QueueItemUi): String? =
    item.durationSecs?.let { formatPlaybackTime(it.toFloat()) }

private fun formatPlaybackTime(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val minutes = total / 60
    val secs = total % 60
    return "%d:%02d".format(minutes, secs)
}
