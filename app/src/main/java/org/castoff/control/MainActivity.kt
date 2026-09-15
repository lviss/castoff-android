package org.castoff.control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.castoff.control.fcast.FCastClient
import org.castoff.control.fcast.FCastStatusListener
import org.castoff.control.fcast.PlaybackAnchor
import org.castoff.control.fcast.PlaybackDisplay
import org.castoff.control.fcast.PlaybackReport
import org.castoff.control.fcast.PlaybackState
import org.castoff.control.fcast.StatusEvent
import org.castoff.control.fcast.interpolatePosition
import org.castoff.control.fcast.playbackDisplayFor
import org.castoff.control.settings.HostSettings
import org.castoff.control.ui.ConnectionStatus
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

                    // The persisted host the status connection follows. A new
                    // emission (i.e. every Save) restarts the connection below.
                    val savedHost by hostSettings.hostFlow.collectAsState(initial = null)

                    LaunchedEffect(Unit) {
                        val saved = hostSettings.current()
                        uiState = uiState.copy(
                            hostAddress = saved.address,
                            hostPort = saved.port.toString(),
                        )
                    }

                    // What the status link is doing, so the screen can always say.
                    // Starts out "connecting" rather than "not connected" because
                    // the persisted host has not been read yet.
                    var connection by remember { mutableStateOf(ConnectionStatus.connecting("", 0)) }

                    // Bumped to force a fresh connection: the manual Connect
                    // button, and every return to the foreground (see below).
                    var reconnectToken by remember { mutableIntStateOf(0) }

                    // Anchor for interpolating the displayed position between pushed
                    // updates; null while not playing *or* while the link is down (see
                    // PlaybackAnchor's doc and the Disconnected branch below).
                    var anchor by remember { mutableStateOf<PlaybackAnchor?>(null) }

                    fun currentPlaybackDisplay() = PlaybackDisplay(
                        anchor = anchor,
                        isPlaying = uiState.isPlaying,
                        positionSeconds = uiState.positionSeconds,
                        durationSeconds = uiState.durationSeconds,
                    )

                    // Applies a display computed by playbackDisplayFor to both pieces of
                    // playback state, keeping anchor and uiState's fields in sync.
                    fun applyPlaybackDisplay(display: PlaybackDisplay) {
                        anchor = display.anchor
                        uiState = uiState.copy(
                            isPlaying = display.isPlaying,
                            positionSeconds = display.positionSeconds,
                            durationSeconds = display.durationSeconds,
                        )
                    }

                    // A link that is not up (a fresh attempt, or one that dropped)
                    // cannot speak for current playback: stop ticking and stop
                    // claiming playback is in progress, while keeping the last known
                    // position/duration frozen so they can still be shown as
                    // "(last known)". Every such reset goes through here so the
                    // sites can't drift apart again.
                    fun resetLivePlayback() {
                        anchor = null
                        uiState = uiState.copy(isPlaying = false, hasPlaybackReport = false)
                    }

                    // Persistent status connection. Restarts on a new saved host
                    // (every Save) and on reconnectToken (manual Connect, or a return
                    // to the foreground). Each restart begins from "we know nothing
                    // about playback yet" rather than showing the previous link's
                    // state, because the daemon does not send its current status on
                    // connect -- see FCastStatusListener's doc.
                    LaunchedEffect(savedHost, reconnectToken) {
                        val host = savedHost
                        if (host == null || !host.isConfigured) {
                            connection = ConnectionStatus.notConfigured
                            resetLivePlayback()
                            uiState = uiState.copy(
                                positionSeconds = null,
                                durationSeconds = null,
                            )
                            return@LaunchedEffect
                        }

                        resetLivePlayback()
                        connection = ConnectionStatus.connecting(host.address, host.port)

                        FCastStatusListener(host.address, host.port).events().collect { event ->
                            when (event) {
                                StatusEvent.Connecting -> {
                                    resetLivePlayback()
                                    connection = ConnectionStatus.connecting(host.address, host.port)
                                }

                                StatusEvent.Connected ->
                                    connection = ConnectionStatus.connected(host.address, host.port)

                                is StatusEvent.Disconnected -> {
                                    connection = ConnectionStatus.notConnected(
                                        host.address,
                                        host.port,
                                        event.reason,
                                    )
                                    resetLivePlayback()
                                }

                                is StatusEvent.Playback -> {
                                    uiState = uiState.copy(hasPlaybackReport = true)
                                    applyPlaybackDisplay(
                                        playbackDisplayFor(
                                            state = PlaybackState.fromInt(event.update.state)
                                                ?: PlaybackState.IDLE,
                                            current = currentPlaybackDisplay(),
                                            report = PlaybackReport(
                                                timeSeconds = event.update.time,
                                                durationSeconds = event.update.duration,
                                                speed = event.update.speed,
                                            ),
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Auto-connect on returning to the foreground. A socket the OS
                    // killed while the app was backgrounded can sit half-open, so the
                    // app's own "still connected" belief can't be trusted on resume:
                    // replace the link unconditionally instead of waiting out the
                    // listener's liveness timeout. Skipped on the first ON_START
                    // because the effect above already connects on composition.
                    val lifecycleOwner = LocalLifecycleOwner.current
                    var seenFirstStart by remember { mutableStateOf(false) }
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_START) {
                                if (seenFirstStart) reconnectToken++ else seenFirstStart = true
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                        connection = connection,
                        onConnect = { reconnectToken++ },
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
                                    onSuccess = {
                                        applyPlaybackDisplay(
                                            playbackDisplayFor(
                                                state = if (nowPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED,
                                                current = currentPlaybackDisplay(),
                                            )
                                        )
                                        uiState.copy(statusMessage = null)
                                    },
                                    onFailure = { e -> uiState.copy(statusMessage = "Error: ${e.message}") },
                                )
                            }
                        },
                        onStop = {
                            val client = clientOrNull() ?: return@ControlScreen
                            scope.launch {
                                val result = client.stop()
                                uiState = result.fold(
                                    onSuccess = {
                                        applyPlaybackDisplay(
                                            playbackDisplayFor(
                                                state = PlaybackState.IDLE,
                                                current = currentPlaybackDisplay(),
                                            )
                                        )
                                        uiState.copy(statusMessage = null)
                                    },
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
