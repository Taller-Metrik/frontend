package com.metrik.app.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metrik.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class Session(
    val token: String? = null,
    val userName: String? = null,
    val baseUrl: String = BuildConfig.API_BASE_URL,
    val odometryMode: String = "auto", // auto | arcore | steps
    val stepLengthM: Double = 0.70, // largo de paso calibrado
) {
    val loggedIn: Boolean get() = !token.isNullOrBlank()
}

/** Sesión y preferencias persistidas con DataStore. */
interface SessionStore {
    val session: StateFlow<Session>
    suspend fun saveLogin(token: String, userName: String)
    suspend fun logout()
    suspend fun setBaseUrl(url: String)
    suspend fun setOdometryMode(mode: String)
    suspend fun setStepLength(meters: Double) {}
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "metrik_session")

class DataStoreSessionStore(context: Context, scope: CoroutineScope) : SessionStore {
    private val store = context.dataStore
    private val kToken = stringPreferencesKey("token")
    private val kName = stringPreferencesKey("user_name")
    private val kUrl = stringPreferencesKey("base_url")
    private val kMode = stringPreferencesKey("odometry_mode")
    private val kStep = androidx.datastore.preferences.core.doublePreferencesKey("step_length_m")

    override val session: StateFlow<Session> = store.data
        .map { p ->
            Session(
                token = p[kToken],
                userName = p[kName],
                baseUrl = p[kUrl] ?: BuildConfig.API_BASE_URL,
                odometryMode = p[kMode] ?: "auto",
                stepLengthM = p[kStep] ?: 0.70,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, Session())

    override suspend fun saveLogin(token: String, userName: String) {
        store.edit { it[kToken] = token; it[kName] = userName }
    }

    override suspend fun logout() {
        store.edit { it.remove(kToken); it.remove(kName) }
    }

    override suspend fun setBaseUrl(url: String) {
        store.edit { it[kUrl] = url.trim() }
    }

    override suspend fun setOdometryMode(mode: String) {
        store.edit { it[kMode] = mode }
    }

    override suspend fun setStepLength(meters: Double) {
        store.edit { it[kStep] = meters }
    }
}
