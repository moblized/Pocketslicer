package org.moblized.pocketslicer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val serverUrl: String = "",
    val token: String = "",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val printer: String = "",
    val process: String = "",
    val filament: String = "",
    val bedType: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val server = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("api_token")
        val theme = stringPreferencesKey("theme")
        val printer = stringPreferencesKey("printer")
        val process = stringPreferencesKey("process")
        val filament = stringPreferencesKey("filament")
        val bedType = stringPreferencesKey("bed_type")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun saveServer(url: String, token: String) {
        context.dataStore.edit {
            it[Keys.server] = url.trim()
            it[Keys.token] = token.trim()
        }
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.theme] = mode.name }
    }

    suspend fun savePresets(printer: String, process: String, filament: String, bedType: String) {
        context.dataStore.edit {
            it[Keys.printer] = printer
            it[Keys.process] = process
            it[Keys.filament] = filament
            it[Keys.bedType] = bedType
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        serverUrl = this[Keys.server].orEmpty(),
        token = this[Keys.token].orEmpty(),
        theme = runCatching { ThemeMode.valueOf(this[Keys.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        printer = this[Keys.printer].orEmpty(),
        process = this[Keys.process].orEmpty(),
        filament = this[Keys.filament].orEmpty(),
        bedType = this[Keys.bedType].orEmpty(),
    )
}
