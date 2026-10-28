// Einstellungen › Alarme › Kursbenachrichtigungen › Paare: Erlaubnis-Abfrage nach dem Schreiben als Einmal-Ereignis
// für den GERADE gezeigten Bildschirm (Muster wie `WatchUiEvents`/`AlarmEditorEffects`).
// Vorher ein Rückruf in die Komposition, die beim Tippen bestand: nach dem Drehen während der Rückfrage «nicht gespeichert» traf er
// den abgemeldeten Launcher.
package com.cryptochecker.app.ui.features.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cryptochecker.app.ui.components.ScreenEvents

/** Ereignisse der Seite «Paare» (Kursbenachrichtigungen je Paar); ein ViewModel des Nav-Eintrags, übersteht das Neuerstellen der Activity. */
internal class SettingsUiEventsHolder : ViewModel() {
    /** Mitteilungen eingeschaltet und geschrieben: jetzt die Erlaubnis fragen. */
    val requestNotifications = ScreenEvents<Unit>()
}

/** [SettingsUiEventsHolder] der Seite (eigener Schlüssel, genau ein Sammler). */
@Composable
internal fun rememberSettingsUiEvents(): SettingsUiEventsHolder =
    viewModel(key = SETTINGS_UI_EVENTS) { SettingsUiEventsHolder() }

internal const val SETTINGS_UI_EVENTS = "settings_price_pairs_ui_events"

/**
 * Rückruf für `setPairNotification`/`setAllPairNotifications`: nur wenn eingeschaltet ([enabled]) UND geschrieben
 * (nicht bei «Abbrechen» bzw. gesperrt) genau ein Ereignis an [events]; die Abfrage läuft im aktuellen Bildschirm.
 */
internal fun askPermissionAfterWrite(events: ScreenEvents<Unit>, enabled: Boolean): (Boolean) -> Unit = { written ->
    if (written && enabled) events.send(Unit)
}
