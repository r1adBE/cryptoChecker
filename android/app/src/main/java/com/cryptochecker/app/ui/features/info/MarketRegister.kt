package com.cryptochecker.app.ui.features.info

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Sprünge von aussen in den Markt-Tab (G-10: statt der Register «Jetzt» | «Einordnung» | «Daten»
 * zwei Unterseiten). Lebt ausserhalb des ViewModels, damit die Navigation einen Sprung ([jump])
 * setzen kann, ohne das ViewModel des Tabs vorzeitig zu erzeugen. Wie `AppRouter.marketJump` (iOS).
 */
internal object MarketRegister {
    /** Offener Sprung; der Tab entscheidet (oben bzw. Unterseite «Daten») und löscht ihn. */
    var jump by mutableStateOf<MarketJump?>(null)

    /** Unterseite «Daten» soll zum Wirtschaftsdaten-Hinweis scrollen (dann gelöscht). */
    var macroOnData by mutableStateOf(false)
}

/** Register, in dem der Wirtschaftsdaten-Hinweis gerade steht (Termin in ±2 h: «Jetzt»). */
internal fun macroRegister(events: List<MacroEvent>, now: Long = System.currentTimeMillis()): MarketSection {
    val hint = MacroCalendar.hint(events, now, ZoneId.systemDefault())
    return MarketSections.macroSection(imminent = hint != null && MacroCalendar.isImminent(hint, now))
}
