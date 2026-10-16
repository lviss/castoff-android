package org.castoff.control.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The app's two top-level destinations, reachable from the bottom navigation bar. */
enum class AppTab(val label: String) {
    CONTROL("Control"),
    SETTINGS("Settings"),
}

/**
 * The app's navigation shell: a connection indicator hoisted into a shared top
 * bar above both [AppTab]s -- so it's structurally present on every screen
 * rather than duplicated per screen -- and a bottom navigation bar switching
 * between [ControlScreen] (queue + playback, pinned controls) and
 * [SettingsScreen] (TV box host/port).
 */
@Composable
fun CastoffApp(
    state: ControlUiState,
    connection: ConnectionStatus,
    onConnect: () -> Unit,
    onHostAddressChange: (String) -> Unit,
    onHostPortChange: (String) -> Unit,
    onSaveHost: () -> Unit,
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
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.CONTROL) }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(text = "Castoff Control", style = MaterialTheme.typography.headlineSmall)
                ConnectionRow(connection = connection, onConnect = onConnect)
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == AppTab.CONTROL,
                    onClick = { selectedTab = AppTab.CONTROL },
                    icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text(AppTab.CONTROL.label) },
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.SETTINGS,
                    onClick = { selectedTab = AppTab.SETTINGS },
                    icon = { Icon(imageVector = Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(AppTab.SETTINGS.label) },
                )
            }
        },
    ) { contentPadding ->
        when (selectedTab) {
            AppTab.CONTROL -> ControlScreen(
                state = state,
                connection = connection,
                onPlayPauseToggle = onPlayPauseToggle,
                onStop = onStop,
                onQueueJumpBackward = onQueueJumpBackward,
                onQueueJumpForward = onQueueJumpForward,
                onClearQueue = onClearQueue,
                onQueueItemClick = onQueueItemClick,
                onSeek = onSeek,
                onSeekFinished = onSeekFinished,
                onVolumeChange = onVolumeChange,
                onVolumeChangeFinished = onVolumeChangeFinished,
                modifier = Modifier.padding(contentPadding),
            )

            AppTab.SETTINGS -> SettingsScreen(
                state = state,
                onHostAddressChange = onHostAddressChange,
                onHostPortChange = onHostPortChange,
                onSaveHost = onSaveHost,
                modifier = Modifier.padding(contentPadding),
            )
        }
    }
}
