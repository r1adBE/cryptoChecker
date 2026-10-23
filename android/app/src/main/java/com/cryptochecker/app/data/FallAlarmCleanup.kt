package com.cryptochecker.app.data

import android.annotation.SuppressLint
import android.content.Context
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.local.isDatabaseKeyUnavailable
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.diagnostics.BackgroundErrors
import com.cryptochecker.app.util.BackgroundErrorCounter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «fällt um ≥ 100 %»-Alarme (Kurs bzw. Open Interest, [AlarmJson.exceedsFall]) konnten ältere Versionen
 * speichern; sie lösen nie aus. Einmal je Installation ausschalten (Merker in eigenen SharedPreferences); die
 * Alarmliste kennzeichnet sie («Schwelle ungültig – bitte anpassen»). Keine Room-Migration: nur ein UPDATE im
 * Hintergrund. Nicht gegen eine Merkliste, die nur im Arbeitsspeicher liegt (dann beim nächsten Start erneut).
 * Wie iOS `AppData.disableInvalidFallAlarmsOnce`.
 */
@Singleton
class FallAlarmCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchDao: WatchDao,
) {
    @Volatile
    private var checked = false

    /** Ein-/Ausgabe im Hintergrund; Fehler bleiben hier (gezählt, nächster Prozess versucht es wieder). */
    suspend fun runOnce() {
        if (checked) return
        checked = true
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DONE, false)) return@withContext
            runCatchingCancellable {
                // Erst öffnen (Lesen), dann ist verlässlich, ob die Datei oder nur der Arbeitsspeicher dahintersteht
                watchDao.getAlarmIds()
                if (DatabaseEncryptionNotice.isInMemory(AppDatabase.DB_NAME)) return@withContext
                val changed = watchDao.disableInvalidFallAlarms(MAX_FALL_PERCENT)
                if (changed > 0) Timber.i("Alarme «fällt um ≥ 100 %%» ausgeschaltet: %d", changed)
                markDone(prefs)
            }.onFailure {
                // F-25: im Release sonst unsichtbar — zählen (Bereich «alarms»), Schlüssel fehlt: nur Hinweis
                if (!it.isDatabaseKeyUnavailable()) BackgroundErrorCounter.record(BackgroundErrors.ALARMS, error = it)
                Timber.w(it, "Alarme «fällt um ≥ 100 %» nicht bereinigt")
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun markDone(prefs: android.content.SharedPreferences) {
        prefs.edit().putBoolean(KEY_DONE, true).commit()
    }

    private companion object {
        const val PREFS = "fall_alarm_cleanup"
        const val KEY_DONE = "done_b104"
        /** Wie [AlarmJson.exceedsFall] (ab hier löst «fällt um» nie aus). */
        const val MAX_FALL_PERCENT = 100.0
    }
}
