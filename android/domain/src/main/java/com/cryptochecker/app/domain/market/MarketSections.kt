package com.cryptochecker.app.domain.market

import kotlin.math.abs

/**
 * Die drei Bereiche des Markt-Tabs: «Jetzt» ([NOW]) steht im Tab selbst, «Einordnung» ([CONTEXT])
 * und «Daten» ([DATA]) sind seit G-10 eigene Unterseiten. Wie `MarketSection` (iOS).
 */
enum class MarketSection {
    NOW,
    CONTEXT,
    DATA,
}

/**
 * Register im Markt-Tab (reines Kotlin, testbar): welcher Teil ([MarketRevealSlot]) in welchem
 * Register steht, wann unter einem Register noch die Ladezeile steht, wohin Wischen führt und in
 * welchem Register der Wirtschaftsdaten-Hinweis liegt. Das gewählte Register gilt für die
 * App-Sitzung (wie früher das Auf-/Zuklappen; nicht gespeichert, beim Start immer «Jetzt»).
 * Wie `MarketSections` (iOS).
 */
object MarketSections {
    /** Beim Start der App: «Jetzt». */
    val DEFAULT: MarketSection = MarketSection.NOW

    /** Register, in dem ein Teil steht (die früheren Überschriften-Teile zählen zu ihrem Register). */
    fun of(slot: MarketRevealSlot): MarketSection = when (slot) {
        MarketRevealSlot.PULSE, MarketRevealSlot.UNUSUAL -> MarketSection.NOW
        MarketRevealSlot.HEADER_CONTEXT, MarketRevealSlot.FEAR_GREED, MarketRevealSlot.PHASE,
        MarketRevealSlot.DOMINANCE, MarketRevealSlot.HALVING -> MarketSection.CONTEXT
        MarketRevealSlot.HEADER_DATA, MarketRevealSlot.MARKET_TOTALS, MarketRevealSlot.GAS,
        MarketRevealSlot.COIN -> MarketSection.DATA
    }

    /** Letzter Teil eines Registers (Reihenfolge [MarketRevealSlot]). */
    fun lastSlot(section: MarketSection): MarketRevealSlot =
        MarketRevealSlot.entries.last { of(it) == section }

    /** Ob unter dem Register noch die Ladezeile steht: sein letzter Teil ist noch nicht erschienen. */
    fun loading(section: MarketSection, shown: Set<MarketRevealSlot>): Boolean = lastSlot(section) !in shown

    /** Nachbar-Register; am Rand null (kein Umlauf). */
    fun neighbor(section: MarketSection, forward: Boolean): MarketSection? =
        MarketSection.entries.getOrNull(section.ordinal + if (forward) 1 else -1)

    /**
     * Ziel beim waagrechten Wischen über [dx] (Pixel, nach rechts positiv): nach links wischen
     * führt zum nächsten Register, nach rechts zum vorigen — bei Rechts-nach-links ([rtl])
     * umgekehrt (die Register stehen dann gespiegelt). Kürzer als [threshold] oder am Rand: null.
     */
    fun swipeTarget(section: MarketSection, dx: Float, threshold: Float, rtl: Boolean): MarketSection? {
        if (abs(dx) < threshold) return null
        return neighbor(section, forward = (dx < 0f) != rtl)
    }

    /**
     * Unterseite aus dem Namen in der Route (G-10): «Einordnung» oder «Daten»; Unbekanntes und
     * «Jetzt» (steht im Tab selbst) ergeben «Einordnung».
     */
    fun subpage(name: String?): MarketSection =
        MarketSection.entries.firstOrNull { it.name == name && it != MarketSection.NOW } ?: MarketSection.CONTEXT

    /** Register mit dem Wirtschaftsdaten-Hinweis: «Jetzt» bei einem Termin in ±2 h, sonst «Daten». */
    fun macroSection(imminent: Boolean): MarketSection = if (imminent) MarketSection.NOW else MarketSection.DATA
}
