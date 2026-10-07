package com.cryptochecker.app.settings

/**
 * Kursfarben für steigend/fallend. Blau/Orange bleibt bei jeder Farbsehschwäche
 * (Rot-Grün und Blau-Gelb) gut unterscheidbar; Grün/Rot nur bei Blau-Gelb.
 * Die Richtung steht zusätzlich immer als + / − (bei völliger Farbenblindheit
 * trägt die Farbe keine Bedeutung).
 *
 * Werte als ARGB, damit App (Compose) und Widgets (RemoteViews) dieselben
 * Farben nutzen. Im Dunkeln jeweils hellere Fassungen, im Hellen dunklere.
 * Normal: mindestens 4.5:1 (WCAG AA), hoher Kontrast: mindestens 7:1 (AAA) — auf
 * allen hellen/dunklen Flächen und in der getönten Pille. Siehe PriceColorSchemeTest.
 *
 * `inverted` tauscht nur die Farben (Ostasien: Rot = steigend), nie Vorzeichen oder Wörter.
 */
enum class PriceColorScheme(
    private val upLight: Int,
    private val upDark: Int,
    private val downLight: Int,
    private val downDark: Int,
    private val upLightHc: Int,
    private val upDarkHc: Int,
    private val downLightHc: Int,
    private val downDarkHc: Int,
) {
    /** Standard: Grün steigend, Rot fallend. */
    GREEN_RED(
        0xFF0A6D3E.toInt(), 0xFF3DD68C.toInt(), 0xFFB22727.toInt(), 0xFFFF6B6B.toInt(),
        0xFF004A27.toInt(), 0xFF7CF2B8.toInt(), 0xFF800B0B.toInt(), 0xFFFFB0B0.toInt(),
    ),

    /** Blau steigend, Orange fallend. */
    BLUE_ORANGE(
        0xFF1460AB.toInt(), 0xFF64B5F6.toInt(), 0xFF9F4300.toInt(), 0xFFFFA040.toInt(),
        0xFF093D83.toInt(), 0xFFA6D4FF.toInt(), 0xFF6C2E00.toInt(), 0xFFFFC685.toInt(),
    ),
    ;

    /** Farbe für steigend; bei [inverted] die Fallend-Farbe des Schemas. */
    fun up(dark: Boolean, highContrast: Boolean = false, inverted: Boolean = false): Int =
        if (inverted) rawDown(dark, highContrast) else rawUp(dark, highContrast)

    /** Farbe für fallend; bei [inverted] die Steigend-Farbe des Schemas. */
    fun down(dark: Boolean, highContrast: Boolean = false, inverted: Boolean = false): Int =
        if (inverted) rawUp(dark, highContrast) else rawDown(dark, highContrast)

    private fun rawUp(dark: Boolean, highContrast: Boolean): Int = when {
        highContrast -> if (dark) upDarkHc else upLightHc
        else -> if (dark) upDark else upLight
    }

    private fun rawDown(dark: Boolean, highContrast: Boolean): Int = when {
        highContrast -> if (dark) downDarkHc else downLightHc
        else -> if (dark) downDark else downLight
    }

    companion object {
        val DEFAULT = GREEN_RED

        fun fromName(name: String?): PriceColorScheme =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
