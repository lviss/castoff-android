package org.castoff.control.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * The Settings tab: the TV box host/port fields and Save button, moved as-is
 * off the main Control screen onto their own destination -- no behavior
 * change to how they work, just where they live.
 */
@Composable
fun SettingsScreen(
    state: ControlUiState,
    onHostAddressChange: (String) -> Unit,
    onHostPortChange: (String) -> Unit,
    onSaveHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSaveHost, modifier = Modifier.fillMaxWidth()) {
            Text("Save")
        }
        state.settingsStatusMessage?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
