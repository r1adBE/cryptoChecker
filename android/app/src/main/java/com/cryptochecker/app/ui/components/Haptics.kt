package com.cryptochecker.app.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptik-Katalog nach Ereignis (nicht nach API), gleich wie iOS `Haptics.swift` — beide Plattformen
 * vibrieren in denselben Momenten. Der einzige Ort, der `performHapticFeedback`
 * aufruft (HapticsUsageTest wacht darüber).
 *
 * | Ereignis      | wann                                                          | Android (API 30+ / 26–29) | iOS                     |
 * |---------------|---------------------------------------------------------------|---------------------------|-------------------------|
 * | SELECTION     | Auswahl ändert sich: Chip, Segment, Schalter, Picker,         | CLOCK_TICK                | Selection               |
 * |               | Chart-Schritt beim Scrubben, Wisch-Schwelle                   |                           |                         |
 * | LIGHT_IMPACT  | leichte Aktion: Ablegen beim Sortieren, Sprung, Gruppe zuweisen | KEYBOARD_TAP            | Impact light            |
 * | LONG_PRESS    | langes Drücken öffnet Modus/Menü, Ziehen beginnt              | LONG_PRESS                | Impact medium           |
 * | SUCCESS       | gespeichert, Sicherung/Wiederherstellung fertig, Alarm angelegt, | CONFIRM / VIRTUAL_KEY  | Notification success    |
 * |               | Rückgängig gemacht, Adresse kopiert                           |                           |                         |
 * | WARNING       | Grenze erreicht (2000 Paare, 10 Alarme), Speichern mit Fehlern | REJECT / LONG_PRESS      | Notification warning    |
 * | ERROR         | Vorgang fehlgeschlagen (Fehlerbanner)                         | REJECT / LONG_PRESS       | Notification error      |
 * | DESTRUCTIVE   | Löschen bestätigt                                             | LONG_PRESS                | Impact heavy            |
 *
 * Android kennt keine eigene Warnung: WARNING und ERROR teilen sich REJECT. DESTRUCTIVE nimmt den
 * kräftigen Einzelimpuls (LONG_PRESS), damit Löschen sich nicht wie «gespeichert» anfühlt.
 *
 * Eine eigene App-Einstellung für Haptik gibt es nicht: `performHapticFeedback` folgt ohne
 * FLAG_IGNORE_GLOBAL_SETTING der System-Einstellung «Haptisches Feedback». Nie im Hintergrund,
 * in Benachrichtigungen oder Widgets verwenden — nur als Antwort auf eine Handlung im Bildschirm.
 */
enum class HapticEvent { SELECTION, LIGHT_IMPACT, LONG_PRESS, SUCCESS, WARNING, ERROR, DESTRUCTIVE }

object Haptics {

    /** Reine Zuordnung Ereignis → `HapticFeedbackConstants` für [sdk] (testbar ohne Gerät). */
    // CONFIRM/REJECT stehen erst ab API 30 bereit; die Abfrage auf [sdk] davor sieht Lint nicht.
    @SuppressLint("InlinedApi")
    fun constantFor(event: HapticEvent, sdk: Int = Build.VERSION.SDK_INT): Int {
        val modern = sdk >= Build.VERSION_CODES.R
        return when (event) {
            HapticEvent.SELECTION -> HapticFeedbackConstants.CLOCK_TICK
            HapticEvent.LIGHT_IMPACT -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticEvent.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
            HapticEvent.SUCCESS -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            HapticEvent.WARNING, HapticEvent.ERROR ->
                if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            HapticEvent.DESTRUCTIVE -> HapticFeedbackConstants.LONG_PRESS
        }
    }

    /** Spielt [event] über [view] (folgt der System-Einstellung). */
    fun play(view: View, event: HapticEvent) {
        view.performHapticFeedback(constantFor(event))
    }
}

/** Haptik für Compose: hält die aktuelle View, damit auch Gesten-Lambdas `play` aufrufen können. */
@Immutable
class HapticPlayer internal constructor(private val view: View) {
    fun play(event: HapticEvent) = Haptics.play(view, event)
}

/** [HapticPlayer] der aktuellen View (LocalView). */
@Composable
fun rememberHaptics(): HapticPlayer {
    val view = LocalView.current
    return remember(view) { HapticPlayer(view) }
}
