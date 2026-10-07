package com.cryptochecker.app.data

import androidx.core.content.edit
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kennzahlen der letzten Aktualisierung. Wird an zwei Stellen gebraucht:
 * im Widget (synchron beim Zeichnen) und in der Watchlist (als Flow).
 * Deshalb SharedPreferences plus StateFlow statt DataStore.
 */
@Singleton
class RefreshStats @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs get() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _lastDurationMillis = MutableStateFlow(prefs.getLong(KEY_LAST_DURATION, 0))
    private val _lastRefreshAt = MutableStateFlow(prefs.getLong(KEY_LAST_REFRESH_AT, 0))
    private val _lastReport = MutableStateFlow(prefs.getString(KEY_LAST_REPORT, "") ?: "")

    /** Dauer des letzten vollständigen Durchlaufs. */
    val lastDurationMillis: StateFlow<Long> = _lastDurationMillis.asStateFlow()

    /** Zeitpunkt des letzten vollständigen Durchlaufs. */
    val lastRefreshAt: StateFlow<Long> = _lastRefreshAt.asStateFlow()

    /** Aufschlüsselung des letzten Durchlaufs: wo die Zeit hingegangen ist. */
    val lastReport: StateFlow<String> = _lastReport.asStateFlow()

    fun setLastRefresh(
        durationMillis: Long,
        at: Long = System.currentTimeMillis(),
        report: String? = null,
    ) {
        prefs.edit {
            putLong(KEY_LAST_DURATION, durationMillis)
            putLong(KEY_LAST_REFRESH_AT, at)
            if (report != null) putString(KEY_LAST_REPORT, report)
        }

        if (durationMillis > 0) _lastDurationMillis.value = durationMillis
        _lastRefreshAt.value = at
        if (report != null) _lastReport.value = report
    }

    /** Ersetzt nur die Aufschlüsselung, z. B. durch eine Fehlermeldung. */
    fun setLastReport(report: String) {
        prefs.edit { putString(KEY_LAST_REPORT, report) }
        _lastReport.value = report
    }

    /** Stellt der Aufschlüsselung eine Zeile voran (z. B. Wartezeit bis zum Start). */
    fun prependToReport(line: String) {
        setLastReport(if (_lastReport.value.isEmpty()) line else line + "\n" + _lastReport.value)
    }

    /** Für Aufrufer ohne Coroutine, etwa das Zeichnen des Widgets. */
    fun lastDuration(): Long = _lastDurationMillis.value

    fun lastRefresh(): Long = _lastRefreshAt.value

    private companion object {
        const val FILE_NAME = "refresh_stats"
        const val KEY_LAST_DURATION = "last_refresh_duration"
        const val KEY_LAST_REFRESH_AT = "last_refresh_at"
        const val KEY_LAST_REPORT = "last_refresh_report"
    }
}
