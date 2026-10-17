package com.cryptochecker.app.widget

import java.util.concurrent.ConcurrentHashMap

/**
 * Stand der Zeilen eines Listen-Widgets, wenn sie über [PriceWidgetService] kommen (unter Android 12
 * und bei langen Listen). Reines Kotlin, testbar.
 *
 * Warum: Bleibt die Adapter-Intent gleich, behält der Launcher seinen Adapter samt zwischengespeicherten
 * Zeilen und lädt nur auf notifyAppWidgetViewDataChanged neu. Kommt diese Meldung an, bevor der Launcher
 * die neuen RemoteViews angewendet hat (asynchrones Anwenden, neu aufgebaute Liste aus dem Zwischenspeicher
 * des Launchers), geht sie verloren: Kopf mit neuer Uhrzeit, darunter die alten Kurse. Deshalb
 *  - bekommt jedes Zeichnen einen neuen Stand ([next]); die Intent trägt dessen [token], damit der
 *    Launcher einen frischen Adapter bindet statt des alten (Zwischenspeicher je Intent),
 *  - prüft die Fabrik bei jeder Abfrage ([isStale]), ob ihre Zeilen zum letzten Zeichnen gehören,
 *    und lädt sonst selbst neu — auch wenn sie als schon erstellte Fabrik keine Meldung bekommt.
 * Der Token läuft im Kreis ([TOKEN_CYCLE]), damit je Widget nur wenige Fabriken entstehen.
 */
class ListWidgetDataVersion {
    private val versions = ConcurrentHashMap<Int, Long>()

    /** Neuer Stand für [appWidgetId] — vor dem Zeichnen, damit Kopf und Zeilen zusammengehören. */
    fun next(appWidgetId: Int): Long = versions.merge(appWidgetId, 1L, Long::plus) ?: 1L

    /** Letzter gezeichneter Stand (0 = seit Prozessstart noch nicht gezeichnet). */
    fun current(appWidgetId: Int): Long = versions[appWidgetId] ?: 0L

    /** Zeilen mit Stand [loaded] veraltet? Noch nie geladen (null) gilt als veraltet. */
    fun isStale(appWidgetId: Int, loaded: Long?): Boolean = loaded == null || loaded != current(appWidgetId)

    /** Gelöschtes Widget vergessen. */
    fun remove(appWidgetId: Int) {
        versions.remove(appWidgetId)
    }

    companion object {
        /** Anzahl verschiedener Intents je Widget (und damit höchstens so viele Fabriken). */
        const val TOKEN_CYCLE = 4

        /** Teil der Adapter-Intent: wechselt bei jedem Zeichnen, wiederholt sich nach [TOKEN_CYCLE]. */
        fun token(version: Long): Long = Math.floorMod(version, TOKEN_CYCLE.toLong())
    }
}
