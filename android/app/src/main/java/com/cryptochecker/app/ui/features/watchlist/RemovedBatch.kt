// Einzeln gelöschte Paare, solange ihr «Rückgängig»-Banner steht (reines Kotlin, testbar).
// Ein zweites Wisch-Löschen ersetzte früher den Banner, das erste war dann ohne Hinweis endgültig; jetzt fasst
// der Banner beide zusammen («2 Paare entfernt») und «Rückgängig» holt alle zurück (wie iOS).
package com.cryptochecker.app.ui.features.watchlist

import android.content.res.Resources
import com.cryptochecker.app.R

internal class RemovedBatch {
    /** Schlüssel des Rückgängig-Speichers für die laufende Sammlung (Id des ersten Paars); null = keine. */
    var undoKey: Long? = null
        private set

    /** Namen der Paare im aktuellen Banner, in Lösch-Reihenfolge. */
    var names: List<String> = emptyList()
        private set

    /** Zählt jeden Banner; ein spät gemeldetes Ende eines ersetzten Banners beendet so nicht die neue Sammlung. */
    var generation: Int = 0
        private set

    /** Paar [watchId] gelöscht: in die laufende Sammlung, sonst eine neue beginnen. Liefert den Rückgängig-Schlüssel. */
    fun add(watchId: Long, name: String): Long {
        val key = undoKey ?: watchId
        undoKey = key
        names = names + name
        generation++
        return key
    }

    /** Banner [ofGeneration] ist weg (abgelaufen, weggewischt, «Rückgängig» getippt oder durch ein anderes ersetzt). */
    fun closed(ofGeneration: Int) {
        if (ofGeneration != generation) return
        undoKey = null
        names = emptyList()
    }
}

/** Text des Lösch-Banners: ein Paar mit Namen, mehrere als Anzahl («2 Paare entfernt»). */
internal fun RemovedBatch.bannerText(resources: Resources): String =
    if (names.size <= 1) {
        resources.getString(R.string.watchlist_removed, names.firstOrNull().orEmpty())
    } else {
        resources.getQuantityString(R.plurals.watchlist_removed_count, names.size, names.size)
    }
