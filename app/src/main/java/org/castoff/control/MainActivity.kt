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
import kotlinx.coroutines.launch
import org.castoff.control.fcast.FCastClient
import org.castoff.control.settings.HostSettings
import org.castoff.control.ui.ControlScreen
import org.castoff.control.ui.ControlUiState
import org.castoff.control.ui.theme.CastoffControlTheme

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
