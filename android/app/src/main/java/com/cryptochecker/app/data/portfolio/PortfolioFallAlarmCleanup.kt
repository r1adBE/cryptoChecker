package com.cryptochecker.app.data.portfolio

import android.annotation.SuppressLint
import android.content.Context
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seit Build 105 (Review FUN3-01): Portfolio-Alarme «fällt um ≥ 100 %» ([PortfolioAlarmLogic.exceedsFall]) liessen sich
 * bis Build 104 speichern; sie lösen nie aus. Einmal je Installation ausschalten (eigener Merker), die Übersicht
 * kennzeichnet sie («Schwelle ungültig – bitte anpassen»). Wie [com.cryptochecker.app.data.FallAlarmCleanup] für die
 * Paar-Alarme und iOS `AppData.disableInvalidFallAlarmsOnce`. Nicht gegen eine Datenbank nur im Arbeitsspeicher (dann
 * beim nächsten Start erneut).
 */
@Singleton
class PortfolioFallAlarmCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PortfolioAlarmDao,
) {
    @Volatile
    private var checked = false

    /** Ein-/Ausgabe im Hintergrund; Fehler bleiben hier (nächster Prozess versucht es wieder). */
    suspend fun runOnce() {
        if (checked) return
        checked = true
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DONE, false)) return@withContext
            runCatchingCancellable {
                // Erst öffnen (Lesen), dann ist verlässlich, ob die Datei oder nur der Arbeitsspeicher dahintersteht
                dao.countEnabled()
                if (DatabaseEncryptionNotice.isInMemory(PortfolioDatabase.DB_NAME)) return@withContext
                val changed = dao.disableInvalidFallAlarms(PortfolioAlarmLogic.MAX_FALL_PERCENT)
                if (changed > 0) Timber.i("Portfolio-Alarme «fällt um ≥ 100 %%» ausgeschaltet: %d", changed)
                markDone(prefs)
            }.onFailure { Timber.w(it, "Portfolio-Alarme «fällt um ≥ 100 %» nicht bereinigt") }
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun markDone(prefs: android.content.SharedPreferences) {
        prefs.edit().putBoolean(KEY_DONE, true).commit()
    }

    private companion object {
        const val PREFS = "portfolio_fall_alarm_cleanup"
        const val KEY_DONE = "done_b105"
    }
}
