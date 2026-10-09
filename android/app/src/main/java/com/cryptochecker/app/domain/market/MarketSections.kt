package com.cryptochecker.app.domain.market

/**
 * Die drei Abschnitte des Markt-Tabs. «Jetzt» ([NOW]) bleibt immer offen; «Einordnung»
 * ([CONTEXT]) und «Daten» ([DATA]) lassen sich zuklappen. Wie `MarketSection` (iOS).
 */
enum class MarketSection {
    NOW,
    CONTEXT,
    DATA;

    /** Lässt sich zuklappen (alles ausser «Jetzt»). */
    val collapsible: Boolean get() = this != NOW
}

/**
 * Auf- und Zuklappen der Abschnitte im Markt-Tab (reines Kotlin, testbar):
 * «Einordnung» und «Daten» beginnen immer zugeklappt, auch beim allerersten Besuch — oben
 * steht nur «Jetzt», darunter je eine Überschrift mit kurzer Zusammenfassung. Fachbegriffe
 * erscheinen erst, wenn der Nutzer aufklappt. Was er in dieser App-Sitzung gewählt hat, gilt
 * beim erneuten Öffnen weiter. Wie `MarketSections` (iOS).
 */
object MarketSections {
    /** Trenner zwischen den Teilen einer Zusammenfassung («Gier 72 · Neutral»). */
    const val SUMMARY_SEPARATOR = " · "

    /**
     * Ob ein Abschnitt offen ist.
     *
     * @param sessionChoice zuletzt in dieser App-Sitzung gewählter Zustand; null = nie getippt (zu)
     */
    fun expanded(section: MarketSection, sessionChoice: Boolean?): Boolean =
        !section.collapsible || (sessionChoice ?: false)

    /** Zusammenfassung für eine zugeklappte Überschrift: vorhandene, nicht leere Teile mit « · ». */
    fun summary(parts: List<String?>): String =
        parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(SUMMARY_SEPARATOR)
}
