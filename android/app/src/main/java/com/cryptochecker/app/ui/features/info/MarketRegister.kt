package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.macro.MacroCalendar
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.market.MarketSection
import com.cryptochecker.app.domain.market.MarketSections
import java.time.ZoneId

/** Sprung von aussen an eine Stelle des Markt-Tabs. */
internal enum class MarketJump {
    /** Mitteilung «Wirtschaftstermine»: zum Wirtschaftsdaten-Hinweis («Jetzt» oder «Daten»). */
    MACRO,
}

/**
 * Gewähltes Register des Markt-Tabs («Jetzt» | «Einordnung» | «Daten»): gilt für die ganze
 * App-Sitzung, auch über Tab-Wechsel (wie früher das Auf-/Zuklappen; nicht gespeichert, beim
 * Start immer «Jetzt»). Lebt ausserhalb des ViewModels, damit die Navigation einen Sprung
 * ([jump]) setzen kann, ohne das ViewModel des Tabs vorzeitig zu erzeugen. Wie
 * `CycleViewModel.register` (iOS).
 */
internal object MarketRegister {
    var selected by mutableStateOf(MarketSections.DEFAULT)

    /** Offener Sprung; der Tab wechselt das Register, scrollt hin und löscht ihn. */
    var jump by mutableStateOf<MarketJump?>(null)

    /** Mindestweg für einen Wechsel durch Wischen. */
    val SWIPE_THRESHOLD = 56.dp
}

/** Register, in dem der Wirtschaftsdaten-Hinweis gerade steht (Termin in ±2 h: «Jetzt»). */
internal fun macroRegister(events: List<MacroEvent>, now: Long = System.currentTimeMillis()): MarketSection {
    val hint = MacroCalendar.hint(events, now, ZoneId.systemDefault())
    return MarketSections.macroSection(imminent = hint != null && MacroCalendar.isImminent(hint, now))
}

/**
 * Waagrecht wischen wechselt das Register ([MarketSections.swipeTarget], am Rand nichts).
 * Senkrechtes Scrollen hat Vorrang (es verbraucht die Bewegung zuerst); die Karten im Tab
 * reagieren nur auf Tippen, es gibt also keine Wischgesten darin, die sich in die Quere kämen.
 */
internal fun Modifier.registerSwipe(
    selected: MarketSection,
    rtl: Boolean,
    onSelect: (MarketSection) -> Unit,
): Modifier = pointerInput(selected, rtl) {
    val threshold = MarketRegister.SWIPE_THRESHOLD.toPx()
    var dx = 0f
    detectHorizontalDragGestures(
        onDragStart = { dx = 0f },
        onDragEnd = {
            MarketSections.swipeTarget(selected, dx, threshold, rtl)?.let(onSelect)
            dx = 0f
        },
        onDragCancel = { dx = 0f },
    ) { change, amount ->
        change.consume()
        dx += amount
    }
}
