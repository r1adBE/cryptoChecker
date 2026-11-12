package com.cryptochecker.app.data

import android.content.Context
import androidx.core.content.edit
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.marketdata.config.ExchangeRetirement
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mit ✕ geschlossene Hinweise über der Merkliste je Börse ([ExchangeRetirement]): der Hinweis nach dem Ende (Eintrag =
 * Name klein) und die Ankündigung davor ([ExchangeRetirement.advanceKey]) getrennt — wer die Ankündigung schliesst, sieht
 * den Hinweis am Stichtag trotzdem. Nur auf diesem Gerät (eigene SharedPreferences «retired_exchange_notice»), nicht in
 * der Systemsicherung (die Sicherungsregeln nehmen nur ausdrücklich Genanntes auf) und nicht in der App-Sicherung. Wie
 * iOS `RetiredNoticeDismissals`.
 */
@Singleton
class RetiredNoticeStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val _dismissed = MutableStateFlow<Set<String>?>(null)

    /** Geschlossene Einträge; null = noch nicht gelesen ([load]). */
    val dismissed: StateFlow<Set<String>?> = _dismissed.asStateFlow()

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Einmal lesen (nicht im Haupt-Thread). */
    suspend fun load() {
        if (_dismissed.value != null) return
        val stored = withContext(io) { prefs().getStringSet(KEY, null)?.toSet().orEmpty() }
        _dismissed.compareAndSet(null, stored)
    }

    /** ✕ am Hinweis nach dem Ende: für [exchange] schliessen. */
    fun dismiss(exchange: String) {
        if (exchange.isBlank()) return
        add(exchange.trim().lowercase())
    }

    /** ✕ an der Ankündigung: für [exchange] schliessen (der Hinweis nach dem Ende bleibt). */
    fun dismissAdvance(exchange: String) {
        if (exchange.isBlank()) return
        add(ExchangeRetirement.advanceKey(exchange))
    }

    private fun add(entry: String) {
        val updated = _dismissed.value.orEmpty() + entry
        _dismissed.value = updated
        prefs().edit { putStringSet(KEY, updated) }
    }

    private companion object {
        const val PREFS = "retired_exchange_notice"
        const val KEY = "dismissed"
    }
}
