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
 * allen hellen/dunklen Flächen bis #E2E2E2 bzw. #353535 (Änderungen als Schrift ohne Pille).
 * Knappste Werte: Korallrot dunkel #FF6E6E 4.50 und Himbeer dunkel #FF6A96 4.53 auf #353535.
 * Siehe PriceColorSchemeTest.
 *
 * `inverted` tauscht nur die Farben (Ostasien: Rot = steigend), nie Vorzeichen oder Wörter.
 *
 * Akzentfarbe: Nur «Ruhig» und «Ruhig Farbsehschwäche» weichen aus. Liegt dort eine Kursfarbe neben der Akzentfarbe
 * ([AccentColor.primary], hell und dunkel) unter ΔE2000 [MIN_ACCENT_DELTA_E], gilt für diese
 * Kombination die nächste Fassung der Liste (Karmin statt Rot bei orangem Akzent, Blau statt Grün
 * bei grünem; Petrol statt Blau, Senfgelb statt Orange). «Frisch», «Traditionell» und
 * «Farbsehschwäche» haben je nur eine Fassung und bleiben
 * bei jedem Akzent die festen Börsenfarben (Entscheid 16. Oktober 2026). Geprüft
 * wird mit [ColorDifference]; hoher Kontrast nimmt dieselbe Fassung. Eine Fassung kann eine tiefere
 * eigene Schwelle haben ([PriceTone.minAccentLight] / [PriceTone.minAccentDark]). Wie iOS.
 *
 * Die Reihenfolge der Einträge ist die Reihenfolge der Karten in «Kursfarben»
 * (Ruhig, Frisch, Traditionell, Farbsehschwäche, Ruhig Farbsehschwäche); gespeichert wird der Name, nie die Position.
 * Standard seit Build 46: «Ruhig» ([CALM]). Ohne gespeicherte Wahl gilt er (auch für bestehende
 * Installationen, die nie gewählt haben); eine gespeicherte Wahl («GREEN_RED» = Frisch) bleibt.
 */
enum class PriceColorScheme(
    /** Steigend: erste Fassung = Standard, weitere für Akzente, die ihr zu ähnlich sind. */
    private val rises: List<PriceTone>,
    /** Fallend: ebenso. */
    private val falls: List<PriceTone>,
) {
    /**
     * Standard «Ruhig»: gedämpftes Grün steigend, gedämpftes Rot fallend (weniger Sättigung als
     * «Frisch»). Fallend mit anderer Helligkeit als steigend (hell dunkler, dunkel heller), damit
     * die Richtung auch ohne Rot-Grün-Sehen erkennbar bleibt: Leuchtdichte 1.78:1 hell, 1.51:1
     * dunkel (Deuteranopie simuliert 1.54 / 1.68). Knappste Werte: #396E54 4.59 auf #E2E2E2,
     * #61AE87 4.62 auf #353535.
     */
    CALM(
        listOf(PriceTones.CALM_GREEN, PriceTones.CALM_BLUE),
        listOf(PriceTones.CALM_RED, PriceTones.CALM_CRIMSON, PriceTones.CALM_PLUM),
    ),

    /**
     * «Frisch»: Mintgrün steigend, Korallrot fallend (bis Build 45 der Standard). Wie «Traditionell» und
     * «Farbsehschwäche» feste Standardfarben wie an jeder Börse (Entscheid 16. Oktober 2026): nur «Ruhig»
     * und «Ruhig Farbsehschwäche» weichen je Akzent aus.
     */
    GREEN_RED(
        listOf(
            PriceTone(0xFF0A6D3E.toInt(), 0xFF3DD68C.toInt(), 0xFF004A27.toInt(), 0xFF7CF2B8.toInt()),
        ),
        listOf(
            PriceTone(0xFFB22727.toInt(), 0xFFFF6E6E.toInt(), 0xFF800B0B.toInt(), 0xFFFFB0B0.toInt()),
        ),
    ),

    /** «Traditionell»: Olivgrün steigend, Himbeer fallend. */
    TRADITIONAL(
        listOf(
            PriceTone(0xFF4A6410.toInt(), 0xFF8FB532.toInt(), 0xFF334509.toInt(), 0xFFB9DA6A.toInt()),
        ),
        listOf(
            PriceTone(0xFFAA1850.toInt(), 0xFFFF6A96.toInt(), 0xFF7A0F38.toInt(), 0xFFFFB8CE.toInt()),
        ),
    ),

    /**
     * «Farbsehschwäche»: kräftiges Blau steigend, kräftiges Orange fallend – feste Standardfarben wie an
     * jeder Börse, kein Ersatz je Akzent. So satt, wie AA (4.5:1) bzw. AAA (7:1) auf allen Flächen
     * erlauben (Entscheid 16. Oktober 2026; die früheren, ruhigeren Werte trägt [CALM_COLOR_VISION]).
     */
    BLUE_ORANGE(
        listOf(
            PriceTone(0xFF005ED6.toInt(), 0xFF52A0FF.toInt(), 0xFF00439D.toInt(), 0xFFA0C8FF.toInt()),
        ),
        listOf(
            PriceTone(0xFFB63900.toInt(), 0xFFFF7500.toInt(), 0xFF852800.toInt(), 0xFFFFB47C.toInt()),
        ),
    ),

    /**
     * «Ruhig Farbsehschwäche»: gedämpftes Blau steigend, gedämpftes Orange fallend (bis Build 52 die
     * Werte von «Farbsehschwäche»). Weicht wie «Ruhig» je Akzent aus, auf der Blau-Gelb-Achse:
     * Petrol bzw. Blau-Violett statt Blau, Senfgelb statt Orange.
     */
    CALM_COLOR_VISION(
        listOf(
            PriceTone(0xFF1460AB.toInt(), 0xFF64B5F6.toInt(), 0xFF093D83.toInt(), 0xFFA6D4FF.toInt()),
            PriceTones.PETROL, PriceTones.VIVID_BLUE,
        ),
        listOf(
            PriceTone(0xFF9F4300.toInt(), 0xFFFFA040.toInt(), 0xFF6C2E00.toInt(), 0xFFFFC685.toInt()),
            PriceTones.MUSTARD,
        ),
    ),
    ;

    /** Gewählte Fassung je Akzentfarbe (Reihenfolge [AccentColor.entries]), einmal gerechnet. */
    private val riseByAccent: List<PriceTone> by lazy { AccentColor.entries.map { pick(rises, it) } }
    private val fallByAccent: List<PriceTone> by lazy { AccentColor.entries.map { pick(falls, it) } }

    /**
     * Farbe für steigend; bei [inverted] die Fallend-Farbe des Schemas. [accent]: Akzentfarbe der
     * App — ist eine Kursfarbe ihr zu ähnlich (ΔE2000 < [MIN_ACCENT_DELTA_E]), gilt bei «Ruhig» und
     * «Ruhig Farbsehschwäche» die Ersatzfassung; die übrigen Schemata bleiben unverändert.
     */
    fun up(dark: Boolean, highContrast: Boolean = false, inverted: Boolean = false, accent: AccentColor? = null): Int =
        (if (inverted) fall(accent) else rise(accent)).of(dark, highContrast)

    /** Farbe für fallend; bei [inverted] die Steigend-Farbe des Schemas (mit [accent] wie bei [up]). */
    fun down(dark: Boolean, highContrast: Boolean = false, inverted: Boolean = false, accent: AccentColor? = null): Int =
        (if (inverted) rise(accent) else fall(accent)).of(dark, highContrast)

    /**
     * Mindestabstand (ΔE2000) zur Akzentfarbe, den die gewählte Fassung einhalten muss: meist
     * [MIN_ACCENT_DELTA_E], tiefer nur für Fassungen mit eigener Schwelle (Karmin neben Orange).
     */
    fun accentFloor(up: Boolean, dark: Boolean, accent: AccentColor): Double {
        val tone = if (up) rise(accent) else fall(accent)
        return if (dark) tone.minAccentDark else tone.minAccentLight
    }

    /** Weicht neben [accent] mindestens eine Kursfarbe vom Standard des Schemas ab (nur «Ruhig» und «Ruhig Farbsehschwäche»)? */
    fun clashesWith(accent: AccentColor?): Boolean =
        rise(accent) !== rises.first() || fall(accent) !== falls.first()

    private fun rise(accent: AccentColor?): PriceTone = if (accent == null) rises.first() else riseByAccent[accent.ordinal]

    private fun fall(accent: AccentColor?): PriceTone = if (accent == null) falls.first() else fallByAccent[accent.ordinal]

    companion object {
        val DEFAULT = CALM

        /** Mindestabstand (ΔE2000) jeder Kursfarbe zur Akzentfarbe, hell und dunkel je für sich. */
        const val MIN_ACCENT_DELTA_E = 24.0

        fun fromName(name: String?): PriceColorScheme =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        /** Kleinerer Abstand (hell, dunkel) von [tone] zur Akzentfarbe [accent] in normalem Kontrast. */
        fun accentDistance(tone: PriceTone, accent: AccentColor): Double = minOf(
            ColorDifference.deltaE2000(tone.light, accent.primary(dark = false)),
            ColorDifference.deltaE2000(tone.dark, accent.primary(dark = true)),
        )

        /** Hält [tone] neben [accent] seine Schwelle ein (hell und dunkel je für sich)? */
        private fun fitsBeside(tone: PriceTone, accent: AccentColor): Boolean =
            ColorDifference.deltaE2000(tone.light, accent.primary(dark = false)) >= tone.minAccentLight &&
                ColorDifference.deltaE2000(tone.dark, accent.primary(dark = true)) >= tone.minAccentDark

        /** Erste Fassung, die ihre Schwelle einhält; sonst die mit dem grössten Abstand. */
        private fun pick(tones: List<PriceTone>, accent: AccentColor): PriceTone =
            tones.firstOrNull { fitsBeside(it, accent) }
                ?: tones.maxBy { accentDistance(it, accent) }
    }
}

/**
 * Eine Kursfarbe in vier Fassungen (ARGB): hell, dunkel, je normal und hoher Kontrast.
 * [minAccentLight] / [minAccentDark]: nötiger Abstand (ΔE2000) zur Akzentfarbe, sonst
 * [PriceColorScheme.MIN_ACCENT_DELTA_E].
 */
class PriceTone(
    val light: Int,
    val dark: Int,
    val lightHc: Int,
    val darkHc: Int,
    val minAccentLight: Double = PriceColorScheme.MIN_ACCENT_DELTA_E,
    val minAccentDark: Double = PriceColorScheme.MIN_ACCENT_DELTA_E,
) {
    fun of(dark: Boolean, highContrast: Boolean): Int = when {
        highContrast -> if (dark) darkHc else lightHc
        else -> if (dark) this.dark else light
    }
}

/**
 * Geteilte Fassungen. Alle AA (≥ 4.5:1) bzw. AAA (≥ 7:1) auf allen Flächen (PriceColorSchemeTest);
 * ΔE2000 ≥ 24 zu den Akzenten, für die sie einspringen. Wie iOS `PriceTone`.
 */
internal object PriceTones {
    val CALM_GREEN = PriceTone(0xFF396E54.toInt(), 0xFF61AE87.toInt(), 0xFF294F3C.toInt(), 0xFFA1CFB8.toInt())

    /**
     * «Ruhig» fallend: hell dunkles Rot (#771D23, ≥ 8.1:1 auf allen hellen Flächen), dunkel helles
     * Rot (#FFAFA8, C* 32.3; ΔE ≥ 37 zu Blau, Grün, Marrs Green).
     */
    val CALM_RED = PriceTone(0xFF771D23.toInt(), 0xFFFFAFA8.toInt(), 0xFF5A0813.toInt(), 0xFFFFD1CF.toInt())

    /**
     * «Ruhig» fallend neben Orange (Standard-Akzent): Karmin, liest sich als Rot. Ein Rot neben Orange
     * erreicht ΔE 24 nicht (heller als L* ≈ 27 bzw. bunt genug nur bis ≈ 19 / 21), daher eigene
     * Schwelle 19 hell / 20 dunkel: ΔE 19.3 / 20.9 zu #A64827 / #ED835E, C* 40.3 / 30.6.
     * Hoher Kontrast: ΔE 15.2 / 15.0 zum Akzent mit hohem Kontrast.
     */
    val CALM_CRIMSON = PriceTone(
        0xFF77212F.toInt(), 0xFFFFB1BE.toInt(), 0xFF51000D.toInt(), 0xFFFFE3E7.toInt(),
        minAccentLight = 19.0, minAccentDark = 20.0,
    )

    /** «Ruhig» fallend neben Rot: Pflaume (ΔE 27.4 / 25.2 zu #BE2B38 / #FF7173). */
    val CALM_PLUM = PriceTone(0xFF672B61.toInt(), 0xFFE7B4E0.toInt(), 0xFF4A1446.toInt(), 0xFFFFD6F9.toInt())

    /** «Ruhig» steigend neben Grün/Marrs Green: gedämpftes Blau, gleiche Helligkeit wie das Grün. */
    val CALM_BLUE = PriceTone(0xFF506299.toInt(), 0xFF8D9CD3.toInt(), 0xFF364673.toInt(), 0xFFB9C3F2.toInt())

    /** «Ruhig Farbsehschwäche» steigend neben Grün/Marrs Green: Blau mit leichtem Violett (ΔE ≥ 28 zu Marrs Green). */
    val VIVID_BLUE = PriceTone(0xFF2462BE.toInt(), 0xFF819BEA.toInt(), 0xFF03448D.toInt(), 0xFFB4C3FE.toInt())

    /**
     * «Ruhig Farbsehschwäche»: Petrol statt Blau für «steigend» bei blauer Akzentfarbe (knappster Kontrast
     * #0D6F73 4.58 auf #E5E2DB). Bleibt neben Orange bei jeder Farbsehschwäche unterscheidbar.
     */
    val PETROL = PriceTone(0xFF0D6F73.toInt(), 0xFF26C6CC.toInt(), 0xFF095053.toInt(), 0xFF80E6EA.toInt())

    /** «Ruhig Farbsehschwäche» fallend neben Orange/Rot: Senfgelb (Blau-Gelb-Achse). */
    val MUSTARD = PriceTone(0xFF786200.toInt(), 0xFFC9A718.toInt(), 0xFF564500.toInt(), 0xFFECC959.toInt())
}
