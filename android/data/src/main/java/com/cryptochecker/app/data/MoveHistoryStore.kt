package com.cryptochecker.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cryptochecker.app.domain.alarm.MoveWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kursverlauf je Paar (Watch-Id) für den Bewegungs-Alarm «x % in y Stunden» (gleitendes Fenster,
 * [MoveWindow]). Nur für Paare mit so einem Alarm; höchstens alle 2 Minuten ein Punkt, ältere
 * ausgedünnt, 31 Stunden lang (Spiegel: `MoveHistoryStore.swift`).
 *
 * Bewusst SharedPreferences statt Room: kleine, kurzlebige Daten (wie der
 * Open-Interest-Verlauf in [ActivityRepository]). Format: ein Schlüssel je Paar
 * («h_<watchId>» = [[zeit, kurs], …], [MoveHistoryCore]).
 * Fehlt der Verlauf (gelöscht, neu installiert), prüft der Alarm gegen seinen gespeicherten Bezug.
 */
@Singleton
class MoveHistoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Ein Schlüssel je Paar und Aufräumen: [MoveHistoryCore]. */
    private val core by lazy {
        MoveHistoryCore(object : MoveHistoryStorage {
            override fun read(key: String): String? = prefs.getString(key, null)
            override fun write(changes: Map<String, String?>) = prefs.edit {
                changes.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
            }
            override fun keys(): Set<String> = prefs.all.keys
        })
    }

    /** Gespeicherter Verlauf des Paars, älteste zuerst. */
    fun history(watchId: Long): List<MoveWindow.PricePoint> = core.history(watchId)

    /**
     * Punkt anhängen und aufräumen ([MoveWindow.append]); schreibt nur, wenn sich etwas ändert.
     * Verläufe anderer Paare, deren letzter Punkt älter als die Aufbewahrung ist (gelöschte Paare,
     * Alarm entfernt), fallen höchstens alle 30 Minuten weg.
     */
    fun append(watchId: Long, point: MoveWindow.PricePoint, now: Long) = core.append(watchId, point, now)

    private companion object {
        const val PREFS = "move_history"
    }
}
