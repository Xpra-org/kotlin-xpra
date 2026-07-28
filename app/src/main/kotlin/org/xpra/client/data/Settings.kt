package org.xpra.client.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "xpra")

/** A connection the user chose to keep. */
@Serializable
data class SavedConnection(
    val uri: String,
    val label: String = "",
)

/** The scaling factor and the list of saved connection URIs. */
class Settings(private val context: Context) {

    companion object {
        /**
         * Default 2: the server then lays out and renders at half the screen size, which is a
         * quarter of the pixels over the wire and makes remote UI elements finger-sized.
         */
        const val DEFAULT_SCALING_FACTOR = 2.0f

        val SCALING_CHOICES = listOf(1.0f, 1.5f, 2.0f, 3.0f)

        private val KEY_SCALING = floatPreferencesKey("scaling_factor")
        private val KEY_CONNECTIONS = stringPreferencesKey("saved_connections")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val scalingFactor: Flow<Float> =
        context.dataStore.data.map { it[KEY_SCALING] ?: DEFAULT_SCALING_FACTOR }

    val savedConnections: Flow<List<SavedConnection>> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONNECTIONS]?.let {
            runCatching { json.decodeFromString<List<SavedConnection>>(it) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setScalingFactor(value: Float) {
        context.dataStore.edit { it[KEY_SCALING] = value }
    }

    suspend fun save(connection: SavedConnection) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_CONNECTIONS]?.let {
                runCatching { json.decodeFromString<List<SavedConnection>>(it) }.getOrNull()
            } ?: emptyList()
            if (current.none { it.uri == connection.uri }) {
                prefs[KEY_CONNECTIONS] = json.encodeToString(current + connection)
            }
        }
    }

    suspend fun forget(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_CONNECTIONS]?.let {
                runCatching { json.decodeFromString<List<SavedConnection>>(it) }.getOrNull()
            } ?: emptyList()
            prefs[KEY_CONNECTIONS] = json.encodeToString(current.filterNot { it.uri == uri })
        }
    }
}
