package org.castoff.control.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.castoff.control.fcast.FCastClient

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "castoff_settings")

data class TvHost(val address: String, val port: Int) {
    val isConfigured: Boolean get() = address.isNotBlank()
}

/** Persists the user-configured castoff daemon host/port (SharedPreferences-equivalent via DataStore). */
class HostSettings(private val context: Context) {

    val hostFlow: Flow<TvHost> = context.dataStore.data.map { prefs ->
        TvHost(
            address = prefs[ADDRESS_KEY] ?: "",
            port = prefs[PORT_KEY] ?: FCastClient.DEFAULT_PORT,
        )
    }

    suspend fun current(): TvHost = hostFlow.first()

    suspend fun save(address: String, port: Int) {
        context.dataStore.edit { prefs ->
            prefs[ADDRESS_KEY] = address.trim()
            prefs[PORT_KEY] = port
        }
    }

    private companion object {
        val ADDRESS_KEY = stringPreferencesKey("tv_host_address")
        val PORT_KEY = intPreferencesKey("tv_host_port")
    }
}
