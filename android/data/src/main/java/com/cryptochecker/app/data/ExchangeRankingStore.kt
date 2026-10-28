package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.market.ExchangeOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handelsvolumen der Börsen am Tag der Installation für die Reihenfolge der Börsenauswahl
 * ([ExchangeOrder]): einmal die beiden öffentlichen Börsenlisten von CoinGecko — für alle Nutzer dieselben Abfragen —,
 * danach nie wieder. Kleine Einstellungsdatei, nicht in der Sicherung (gilt je Gerät). Scheitert der Abruf, gilt die
 * bisherige Reihenfolge; nächster Versuch in der nächsten App-Sitzung. Wie iOS `ExchangeRankingStore`.
 */
@Singleton
class ExchangeRankingStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val state by lazy { MutableStateFlow(load()) }
    private val mutex = Mutex()

    /** In dieser Sitzung schon versucht (höchstens ein Abruf je Sitzung). */
    @Volatile private var tried = false

    /** Börsen-Schlüssel → Volumen (BTC); null = noch kein Ranking gespeichert. */
    val volumes: StateFlow<Map<String, Double>?> get() = state.asStateFlow()

    /** Holt das Ranking, falls keins gespeichert ist und in dieser Sitzung noch nicht versucht wurde. */
    suspend fun ensureLoaded() {
        if (state.value != null || tried) return
        mutex.withLock {
            if (state.value != null || tried) return
            tried = true
            try {
                val spot = httpClient.callExchange(ExchangeOrder.SPOT_URL, null, HttpBodyLimit.BULK_MAX_BYTES)
                val derivatives = httpClient.callExchange(ExchangeOrder.DERIVATIVES_URL, null, HttpBodyLimit.BULK_MAX_BYTES)
                val parsed = ExchangeOrder.parseVolumes(spot, derivatives)
                if (parsed.isEmpty()) return
                prefs.edit {
                    putString(KEY_VOLUMES, JSONObject(parsed as Map<*, *>).toString())
                    putLong(KEY_DATE, System.currentTimeMillis())
                }
                state.value = parsed
            } catch (e: Exception) {
                e.rethrowIfCritical()
                Timber.i("Börsen-Ranking nicht geladen: %s", e.message)
            }
        }
    }

    private fun load(): Map<String, Double>? {
        val raw = prefs.getString(KEY_VOLUMES, null) ?: return null
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getDouble(it) }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val PREFS = "exchange_ranking"
        const val KEY_VOLUMES = "volumes"
        const val KEY_DATE = "date"
    }
}
