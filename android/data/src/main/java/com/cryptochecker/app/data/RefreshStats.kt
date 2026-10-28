package com.cryptochecker.app.data

import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.RefreshReport
import com.cryptochecker.app.domain.watch.ChangeStamp
import com.cryptochecker.app.domain.watch.RowPause
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
    private val _lastReport = MutableStateFlow(RefreshReportCodec.decode(prefs.getString(KEY_LAST_REPORT, null)))
    private val _changeStamp = MutableStateFlow(ChangeStamp.decode(prefs.getString(KEY_CHANGE_STAMP, null)))

    /** Dauer des letzten vollständigen Durchlaufs. */
    val lastDurationMillis: StateFlow<Long> = _lastDurationMillis.asStateFlow()

    /**
     * Zeitpunkt der letzten Kurse mit Uhrzeit für die Anzeige (Widget-Kopfzeile): vollständiger Durchlauf oder
     * Live-Takt. Die Sperre für «Aktualisieren» liest [lastFullRefresh].
     */
    val lastRefreshAt: StateFlow<Long> = _lastRefreshAt.asStateFlow()

    /**
     * Letzte VOLLE Prüfung (alle abfragbaren Paare versucht, die meisten mit Kurs) — nur für die Sperre
     * gegen doppelte Hintergrund-Läufe ([com.cryptochecker.app.domain.refresh.BackgroundSchedule.shouldSkip]).
     * Live-Takte und Teil-Läufe setzen nur [lastRefreshAt] (Uhrzeit im Widget). 0 = noch keine.
     */
    @Volatile
    private var lastFullCheckAt: Long = prefs.getLong(KEY_LAST_FULL_CHECK_AT, 0)

    fun lastFullCheck(): Long = lastFullCheckAt

    fun setLastFullCheck(at: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(KEY_LAST_FULL_CHECK_AT, at) }
        lastFullCheckAt = at
    }

    /** Bericht des letzten Durchlaufs (Börsen, Ablauf); null = noch keiner. */
    val lastReport: StateFlow<RefreshReport?> = _lastReport.asStateFlow()

    /**
     * Mit welcher %-Basis (und welchem Tagesbeginn) die gespeicherten Veränderungen zuletzt
     * gerechnet wurden; null = noch nie (gilt als rollend). Siehe `ChangeBasisMath.isCurrent`.
     */
    val changeStamp: StateFlow<ChangeStamp?> = _changeStamp.asStateFlow()

    /** Nach einem vollen Durchlauf: Basis und Tagesbeginn der neuen Werte. */
    fun setChangeStamp(stamp: ChangeStamp) {
        if (_changeStamp.value == stamp) return
        prefs.edit { putString(KEY_CHANGE_STAMP, stamp.encode()) }
        _changeStamp.value = stamp
    }

    /**
     * Ende der letzten VOLLSTÄNDIGEN Aktualisierung mit mindestens einem Kurs (App, Hintergrund, Widget-Knopf);
     * 0 = noch keine. Grundlage der 15-s-Sperre für «Aktualisieren» (`RefreshDebounce`, auch Widget-Knopf) —
     * Live-Takte setzen nur [lastRefreshAt] (Uhrzeit im Widget) und sperren das Aktualisieren von Hand so nicht.
     * Wie [lastRefreshAt] nicht in der Sicherung (refresh_stats).
     */
    @Volatile
    private var lastFullRefreshAt: Long = prefs.getLong(KEY_LAST_FULL_REFRESH_AT, 0)

    fun lastFullRefresh(): Long = lastFullRefreshAt

    /** Ende einer vollständigen Aktualisierung: Uhrzeit und Dauer ([setLastRefresh]) und Sperre zugleich. */
    fun setLastFullRefresh(durationMillis: Long, at: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(KEY_LAST_FULL_REFRESH_AT, at) }
        lastFullRefreshAt = at
        setLastRefresh(durationMillis, at)
    }

    fun setLastRefresh(
        durationMillis: Long,
        at: Long = System.currentTimeMillis(),
    ) {
        prefs.edit {
            putLong(KEY_LAST_DURATION, durationMillis)
            putLong(KEY_LAST_REFRESH_AT, at)
        }

        if (durationMillis > 0) _lastDurationMillis.value = durationMillis
        _lastRefreshAt.value = at
    }

    /** Ersetzt den Bericht (auch bei einem abgebrochenen Durchlauf). */
    fun setLastReport(report: RefreshReport) {
        prefs.edit {
            putString(KEY_LAST_REPORT, RefreshReportCodec.encode(report))
        }
        _lastReport.value = report
    }

    /**
     * Wartezeit zwischen Knopfdruck und Start und die Nummer des Versuchs ([RefreshReport.attempt])
     * nachtragen (WorkManager).
     */
    fun setWait(millis: Long, attempt: Int) {
        _lastReport.value?.let { setLastReport(it.copy(waitMillis = millis, attempt = attempt)) }
    }

    private val _appStartMillis = MutableStateFlow(prefs.getLong(KEY_APP_START, 0L).takeIf { it > 0 })

    /** App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen); null = noch nie. Nur lokal. */
    val appStartMillis: StateFlow<Long?> = _appStartMillis.asStateFlow()

    fun setAppStartMillis(millis: Long) {
        prefs.edit { putLong(KEY_APP_START, millis) }
        _appStartMillis.value = millis
    }

    /** Pausen je Börse (ExchangeBackoff), Schlüssel = Börsen-Kennung. Gespeichert, damit sie
     *  auch über einen Neustart des Prozesses (WorkManager) gelten. */
    @Synchronized
    fun backoffStates(): Map<String, ExchangeBackoff.State> =
        ExchangeBackoff.decode(prefs.getString(KEY_BACKOFF, null))

    private val _exchangePauses = MutableStateFlow(RowPause.pauses(backoffStates()))

    /**
     * Ende der Pause je Börsen-Schlüssel ([RowPause.pauses]) für die Zeilen der Merkliste («Binance · pausiert bis
     * 19:45»); ob eine Pause noch läuft, entscheidet die Zeile mit der aktuellen Zeit ([RowPause.shownUntil]).
     */
    val exchangePauses: StateFlow<Map<String, Long>> = _exchangePauses.asStateFlow()

    @Synchronized
    fun setBackoffStates(states: Map<String, ExchangeBackoff.State>) {
        prefs.edit {
            if (states.isEmpty()) remove(KEY_BACKOFF) else putString(KEY_BACKOFF, ExchangeBackoff.encode(states))
        }
        _exchangePauses.value = RowPause.pauses(states)
    }

    /** Für Aufrufer ohne Coroutine, etwa das Zeichnen des Widgets. */
    fun lastDuration(): Long = _lastDurationMillis.value

    fun lastRefresh(): Long = _lastRefreshAt.value

    private companion object {
        const val FILE_NAME = "refresh_stats"
        const val KEY_LAST_DURATION = "last_refresh_duration"
        const val KEY_LAST_REFRESH_AT = "last_refresh_at"
        const val KEY_LAST_FULL_CHECK_AT = "last_full_check_at"
        const val KEY_LAST_FULL_REFRESH_AT = "last_full_refresh_at"
        const val KEY_LAST_REPORT = "last_refresh_report"
        const val KEY_CHANGE_STAMP = "change_basis_stamp"
        const val KEY_APP_START = "app_start_millis"
        const val KEY_BACKOFF = "exchange_backoff"
    }
}
