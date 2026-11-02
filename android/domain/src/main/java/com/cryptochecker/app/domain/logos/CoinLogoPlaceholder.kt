package com.cryptochecker.app.domain.logos

/**
 * Ersatz-Kreis ohne Logo (unbekannt, Fehler, noch nicht geladen; auch TradFi ohne Logo und DEX-Pools): farbiger Kreis
 * mit den Initialen ([CoinLogos.initials]). Die Farbe folgt fest aus dem Kürzel — dasselbe Kürzel hat überall
 * dieselbe Farbe (App, Widgets, Android und iOS), verschiedene Kürzel verteilen sich auf die Palette. Gleich in iOS
 * `CoinLogoPlaceholder.swift`, gemeinsame Testfälle `testdata/parity/coin_placeholder.json`.
 *
 * Kontrast: Schrift [TEXT_LIGHT] auf jeder Füllung von [FILLS_LIGHT] bzw. [TEXT_DARK] auf [FILLS_DARK] mindestens
 * 4.5:1 (WCAG AA, geprüft im Test).
 */
object CoinLogoPlaceholder {
    /** Füllungen hell: kräftige, eher dunkle Töne (blau, petrol, grün, violett, beere, rost, braun, indigo). */
    val FILLS_LIGHT = intArrayOf(
        0xFF2B5FC8.toInt(), 0xFF00796B.toInt(), 0xFF2E7D32.toInt(), 0xFF6A3FB5.toInt(),
        0xFFAD1457.toInt(), 0xFFB3401A.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt(),
    )

    /** Füllungen dunkel: dieselben Töne hell und gedämpft. */
    val FILLS_DARK = intArrayOf(
        0xFF9DBBF5.toInt(), 0xFF7FCBC2.toInt(), 0xFFA5D6A7.toInt(), 0xFFC6AEF2.toInt(),
        0xFFF4A3C0.toInt(), 0xFFFFAE8F.toInt(), 0xFFC9B3AA.toInt(), 0xFFA7B0E6.toInt(),
    )

    /** Schrift hell (weiss) bzw. dunkel (fast schwarz). */
    const val TEXT_LIGHT = 0xFFFFFFFF.toInt()
    const val TEXT_DARK = 0xFF141414.toInt()

    /**
     * Kürzel, aus dem die Farbe folgt: wie die Initialen — Krypto ohne Hebel-Präfix ([CoinLogos.stripMultiplier]:
     * «1000PEPE» wie «PEPE»), TradFi ohne [CoinLogos.TRADFI_PREFIX] («TRADFI:NVDA» wie «NVDA»), gross.
     */
    fun key(symbol: String): String =
        if (symbol.startsWith(CoinLogos.TRADFI_PREFIX)) CoinLogos.displaySymbol(symbol).trim().uppercase()
        else CoinLogos.stripMultiplier(symbol)

    /** Stelle in der Palette: FNV-1a (32 Bit) über die UTF-8-Bytes von [key], modulo Anzahl Farben. */
    fun index(symbol: String): Int {
        var hash = FNV_OFFSET
        for (b in key(symbol).encodeToByteArray()) {
            hash = (hash xor (b.toLong() and 0xFF)) * FNV_PRIME and 0xFFFFFFFFL
        }
        return (hash % FILLS_LIGHT.size).toInt()
    }

    /** Füllung (ARGB) des Kreises für [symbol]. */
    fun fill(symbol: String, dark: Boolean): Int = (if (dark) FILLS_DARK else FILLS_LIGHT)[index(symbol)]

    /** Farbe der Initialen (ARGB). */
    fun text(dark: Boolean): Int = if (dark) TEXT_DARK else TEXT_LIGHT

    /** Dauer des Einblendens, wenn das Logo kommt (ohne Animation bei «Bewegung reduzieren»/Animator-Dauer 0). */
    const val FADE_MILLIS = 220

    private const val FNV_OFFSET = 0x811C9DC5L
    private const val FNV_PRIME = 0x01000193L
}
