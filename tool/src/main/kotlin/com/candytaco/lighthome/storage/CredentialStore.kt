package com.candytaco.lighthome.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.candytaco.lighthome.net.ServerConfig
import kotlinx.coroutines.flow.first

/**
 * Server address and credentials. Secrets are encrypted with [SecretCipher]; the URL and the
 * Cloudflare client id are identifiers, not secrets, and are stored as-is.
 */
class CredentialStore(private val dataStore: DataStore<Preferences>) {

    suspend fun loadServer(): ServerConfig? {
        val prefs = dataStore.data.first()
        val url = prefs[SERVER_URL] ?: return null
        val cfSecret = prefs[CF_CLIENT_SECRET]?.let(::decryptOrNull).orEmpty()
        return ServerConfig.create(url, prefs[CF_CLIENT_ID].orEmpty(), cfSecret)
    }

    suspend fun saveServer(server: ServerConfig) {
        dataStore.edit { prefs ->
            prefs[SERVER_URL] = server.baseUrl
            val cf = server.cfAccess
            if (cf != null) {
                prefs[CF_CLIENT_ID] = cf.clientId
                prefs[CF_CLIENT_SECRET] = SecretCipher.encrypt(cf.clientSecret)
            } else {
                prefs.remove(CF_CLIENT_ID)
                prefs.remove(CF_CLIENT_SECRET)
            }
        }
    }

    suspend fun loadRefreshToken(): String? =
        dataStore.data.first()[HA_REFRESH_TOKEN]?.let(::decryptOrNull)

    suspend fun saveRefreshToken(token: String) {
        dataStore.edit { it[HA_REFRESH_TOKEN] = SecretCipher.encrypt(token) }
    }

    suspend fun clearRefreshToken() {
        dataStore.edit { it.remove(HA_REFRESH_TOKEN) }
    }

    /** URL path of the dashboard shown on Home; null = HA's default dashboard. */
    suspend fun loadDashboardPath(): String? = dataStore.data.first()[DASHBOARD_PATH]

    suspend fun saveDashboardPath(path: String?) {
        dataStore.edit { prefs ->
            if (path == null) prefs.remove(DASHBOARD_PATH) else prefs[DASHBOARD_PATH] = path
        }
    }

    /** A secret we can't decrypt (lost keystore key, corrupt blob) is treated as absent. */
    private fun decryptOrNull(encoded: String): String? = runCatching { SecretCipher.decrypt(encoded) }.getOrNull()

    private companion object {
        val SERVER_URL = stringPreferencesKey("lighthome.server_url")
        val CF_CLIENT_ID = stringPreferencesKey("lighthome.cf_client_id")
        val CF_CLIENT_SECRET = stringPreferencesKey("lighthome.cf_client_secret")
        val HA_REFRESH_TOKEN = stringPreferencesKey("lighthome.ha_refresh_token")
        val DASHBOARD_PATH = stringPreferencesKey("lighthome.dashboard_path")
    }
}
