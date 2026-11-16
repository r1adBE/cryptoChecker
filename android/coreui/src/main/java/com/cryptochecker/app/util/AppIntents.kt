package com.cryptochecker.app.util

import android.content.Context
import android.content.Intent

/**
 * Aufruf der App von aussen (Widgets, Mitteilungen): Start-Activity und ihre Extras. Die Activity liegt in :app; Module
 * darunter (Widgets) kennen sie nur beim Namen — `AppIntentsTest` (:app) prüft, dass der Name zur Klasse passt.
 */
object AppIntents {
    const val MAIN_ACTIVITY = "com.cryptochecker.app.ui.MainActivity"

    /** Paar (Watch-Id), das beim Öffnen gezeigt wird (Merkliste mit seinem Aktionsblatt). */
    const val EXTRA_WATCH_ID = "watch_id"

    /** Ziel beim Öffnen (Verknüpfung, Mitteilung, Widget); eigene Intents tragen dazu das Geheimnis ([withOpenToken]). */
    const val EXTRA_OPEN = "open"

    /** Intent auf die Start-Activity (wie `Intent(context, MainActivity::class.java)`). */
    fun main(context: Context): Intent = Intent().setClassName(context, MAIN_ACTIVITY)
}
