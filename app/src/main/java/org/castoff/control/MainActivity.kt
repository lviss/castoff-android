package org.castoff.control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.castoff.control.fcast.FCastClient
import org.castoff.control.fcast.FCastStatusListener
import org.castoff.control.fcast.PlaybackAnchor
import org.castoff.control.fcast.PlaybackState
import org.castoff.control.fcast.interpolatePosition
import org.castoff.control.settings.HostSettings
import org.castoff.control.ui.ControlScreen
import org.castoff.control.ui.ControlUiState
import org.castoff.control.ui.theme.CastoffControlTheme

/** How often the displayed position ticks between daemon-pushed `PlaybackUpdate`s. */
private const val POSITION_TICK_INTERVAL_MS = 200L

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hostSettings = HostSettings(applicationContext)

        setContent {
            CastoffControlTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val scope = rememberCoroutineScope()
                    var uiState by remember {
                        mutableStateOf(
                            ControlUiState(
                                hostAddress = "",
                                hostPort = FCastClient.DEFAULT_PORT.toString(),
                                isPlaying = false,
                                volume = 0.5f,
                                statusMessage = null,
                            )
                        )
                    }

                    LaunchedEffect(Unit) {
                        val saved = hostSettings.current()
                        uiState = uiState.copy(
                            hostAddress = saved.address,
                            hostPort = saved.port.toString(),
                        )
                    }

                    // Anchor for interpolating the displayed position between pushed updates;
                    // null while not playing (see PlaybackAnchor's doc for why).
                    var anchor by remember { mutableStateOf<PlaybackAnchor?>(null) }

                    // Persistent status connection: restarts on every hostFlow emission, i.e.
                    // once a host is saved and again on each subsequent save -- see
                    // FCastStatusListener's doc for why this is a separate connection from
                    // FCastClient's short-lived per-command ones.
                    LaunchedEffect(Unit) {
                        hostSettings.hostFlow.collectLatest { host ->
                            if (!host.isConfigured) return@collectLatest
                            FCastStatusListener(host.address, host.port).playbackUpdates().collect { update ->
                                val playing = PlaybackState.fromInt(update.state) == PlaybackState.PLAYING
                                anchor = if (playing) {
                                    PlaybackAnchor(
                                        reportedTimeSeconds = update.time ?: 0.0,
                                        anchorElapsedRealtimeNanos = System.nanoTime(),
                                        speed = update.speed ?: 1.0,
                                    )
                                } else {
                                    null
                                }
                                uiState = uiState.copy(
                                    isPlaying = playing,
                                    positionSeconds = update.time?.toFloat(),
                                    durationSeconds = update.duration?.toFloat(),
                                )
                            }
                        }
                    }

                    // Ticks the displayed position between pushed updates; restarts (or stops,
                    // when anchor is null) on every new anchor, i.e. on every pushed update.
                    LaunchedEffect(anchor) {
                        val currentAnchor = anchor ?: return@LaunchedEffect
                        while (isActive) {
                            uiState = uiState.copy(
                                positionSeconds = interpolatePosition(
                                    anchor = currentAnchor,
                                    nowElapsedRealtimeNanos = System.nanoTime(),
                                    durationSeconds = uiState.durationSeconds?.toDouble(),
                                ).toFloat(),
                            )
                            delay(POSITION_TICK_INTERVAL_MS)
                        }
                    }

                    fun clientOrNull(): FCastClient? {
                        val address = uiState.hostAddress
                        val port = uiState.hostPort.toIntOrNull()
                        if (address.isBlank() || port == null) {
                            uiState = uiState.copy(statusMessage = "Set a TV host and port first")
                            return null
                        }
                        return FCastClient(address, port)
                    }

                    ControlScreen(
                        state = uiState,
                        onHostAddressChange = { uiState = uiState.copy(hostAddress = it) },
                        onHostPortChange = { uiState = uiState.copy(hostPort = it.filter(Char::isDigit)) },
                        onSaveHost = {
                            val port = uiState.hostPort.toIntOrNull() ?: FCastClient.DEFAULT_PORT
                            scope.launch {
                                hostSettings.save(uiState.hostAddress, port)
                                uiState = uiState.copy(statusMessage = "Saved")
                            }
                        },
                        onPlayPauseToggle = {
                            val client = clientOrNull() ?: return@ControlScreen
                            val nowPlaying = !uiState.isPlaying
                            scope.launch {
                                val result = if (nowPlaying) client.resume() else client.pause()
                                uiState = result.fold(
                                    onSuccess = { uiState.copy(isPlaying = nowPlaying, statusMessage = null) },
                                    onFailure = { e -> uiState.copy(statusMessage = "Error: ${e.message}") },
                                )
                            }
                        },
                        onStop = {
                            val client = clientOrNull() ?: return@ControlScreen
                            scope.launch {
                                val result = client.stop()
                                uiState = result.fold(
                                    onSuccess = { uiState.copy(isPlaying = false, statusMessage = null) },
                                    onFailure = { e -> uiState.copy(statusMessage = "Error: ${e.message}") },
                                )
                            }
                        },
                        onVolumeChange = { uiState = uiState.copy(volume = it) },
                        onVolumeChangeFinished = {
                            val client = clientOrNull() ?: return@ControlScreen
                            val volume = uiState.volume
                            scope.launch {
                                val result = client.setVolume(volume.toDouble())
                                uiState = result.fold(
                                    onSuccess = { uiState.copy(statusMessage = null) },
                                    onFailure = { e -> uiState.copy(statusMessage = "Error: ${e.message}") },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
