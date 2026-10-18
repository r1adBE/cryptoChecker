package com.cryptochecker.app.ui.features.info

import com.cryptochecker.app.domain.macro.MacroCalendar
import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.market.MarketSection
import com.cryptochecker.app.domain.market.MarketSections
import java.time.ZoneId

/**
 * Sprung von aussen an eine Stelle des Markt-Tabs (G-10: statt der Register «Jetzt» | «Einordnung» | «Daten»
 * zwei Unterseiten). Die Navigation merkt sich einen offenen Sprung gespeichert (übersteht Prozess-Neustart) und
 * gibt ihn dem Tab mit, ohne dessen ViewModel vorzeitig zu erzeugen; der Tab entscheidet (oben bzw. Unterseite
 * «Daten», die per Routen-Argument zum Hinweis scrollt) und meldet ihn als erledigt. Wie `AppRouter.marketJump` (iOS).
 */
enum class MarketJump {
    /** Mitteilung «Wirtschaftstermine»: zum Wirtschaftsdaten-Hinweis («Jetzt» oder «Daten»). */
    MACRO;

    companion object {
        /** Gespeicherter Name ([name]) zurück; null bzw. unbekannt = kein Sprung. */
        fun fromName(name: String?): MarketJump? = entries.firstOrNull { it.name == name }
    }
}

/** Register, in dem der Wirtschaftsdaten-Hinweis gerade steht (Termin in ±2 h: «Jetzt»). */
internal fun macroRegister(events: List<MacroEvent>, now: Long = System.currentTimeMillis()): MarketSection {
    val hint = MacroCalendar.hint(events, now, ZoneId.systemDefault())
    return MarketSections.macroSection(imminent = hint != null && MacroCalendar.isImminent(hint, now))
}
