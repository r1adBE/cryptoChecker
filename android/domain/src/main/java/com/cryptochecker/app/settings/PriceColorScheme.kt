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
 * Knappste Werte: Börsenfarben je knapp über 4.5:1 bzw. 7:1 (z. B. #3EAE97 4.50 auf #353535,
 * #C3213A 4.50 auf #E2E2E2) — sie liegen so nah am Börsenton, wie der Kontrast erlaubt.
 * Siehe PriceColorSchemeTest.
 *
 * Feste Börsenfarben («Frisch», «Traditionell», «Farbsehschwäche»:
 * Farben wie bei allen Börsen, die Akzentfarbe soll nicht irritieren), geprüft mit
 * `android/tools/check_price_colors.py` und PriceColorSchemeTest:
 *  - je Farbe der Ton der Börsen-App, so nah wie möglich (CIEDE2000): «Frisch» = Binance
 *    #0ECB81 / #F6465D, «Traditionell» = TradingView #089981 / #F23645, «Farbsehschwäche» =
 *    Blau/Orange (hell #1565C0 / #E65100, dunkel #3D9BFF / #F7931A); bevorzugt gleicher Farbton
 *    (HSL ± 3°), nur die Helligkeit angepasst;
 *  - hart nur der Kontrast als Schrift (≥ 4.5:1, hoher Kontrast ≥ 7:1) auf allen Flächen;
 *  - «Frisch» und «Traditionell» je Farbe ΔE2000 ≥ 8 (sonst «Traditionell» dunkler/kräftiger);
 *  - nur «Farbsehschwäche»: steigend/fallend ΔE2000 ≥ 35 unter Protanopie, Deuteranopie und
 *    Tritanopie (Machado 2009) und Leuchtdichte-Verhältnis ≥ 1.4;
 *  - kein Abstand zur Akzentfarbe, keine Regel, welche Richtung heller ist.
 *
 * `inverted` tauscht nur die Farben (Ostasien: Rot = steigend), nie Vorzeichen oder Wörter.
 *
 * Akzentfarbe: Nur «Ruhig» und «Ruhig Farbsehschwäche» weichen aus. Liegt dort eine Kursfarbe neben der Akzentfarbe
 * ([AccentColor.primary], hell und dunkel) unter ΔE2000 [MIN_ACCENT_DELTA_E], gilt für diese
 * Kombination die nächste Fassung der Liste (Karmin statt Rot bei orangem Akzent, Blau statt Grün
 * bei grünem; Petrol statt Blau, Senfgelb statt Orange). «Frisch», «Traditionell» und
 * «Farbsehschwäche» haben je nur eine Fassung und bleiben
 * bei jedem Akzent die festen Börsenfarben. Geprüft
 * wird mit [ColorDifference]; hoher Kontrast nimmt dieselbe Fassung. Eine Fassung kann eine tiefere
 * eigene Schwelle haben ([PriceTone.minAccentLight] / [PriceTone.minAccentDark]). Wie iOS.
 *
 * Die Reihenfolge der Einträge ist die Reihenfolge der Karten in «Kursfarben»
 * (Ruhig, Frisch, Traditionell, Farbsehschwäche, Ruhig Farbsehschwäche); gespeichert wird der Name, nie die Position.
 * Standard: «Ruhig» ([CALM]). Ohne gespeicherte Wahl gilt er (auch für bestehende
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
     * die Richtung auch ohne Rot-Grün-Sehen erkennbar bleibt: Leuchtdichte 1.78:1 hell, 1.505:1
     * dunkel (Deuteranopie simuliert 1.54 / 1.66). Knappste Werte: #396E54 4.59 auf #E2E2E2,
     * #61AE87 4.62 auf #353535.
     */
    CALM(
        listOf(PriceTones.CALM_GREEN, PriceTones.CALM_BLUE),
        listOf(PriceTones.CALM_RED, PriceTones.CALM_CRIMSON, PriceTones.CALM_PLUM),
    ),

    /**
     * «Frisch»: Binance-Grün/-Rot (#0ECB81 / #F6465D). Wie «Traditionell»
     * und «Farbsehschwäche» feste Standardfarben wie an jeder Börse:
     * nur «Ruhig» und «Ruhig Farbsehschwäche» weichen je Akzent aus. Dunkel steigend genau der
     * Binance-Ton; sonst gleicher Farbton, so hell bzw. dunkel wie der Kontrast verlangt.
     */
    GREEN_RED(
        listOf(
            PriceTone(0xFF127248.toInt(), 0xFF0ECB81.toInt(), 0xFF025430.toInt(), 0xFF30DE92.toInt()),
        ),
        listOf(
            PriceTone(0xFFC3213A.toInt(), 0xFFFC6F7D.toInt(), 0xFF911127.toInt(), 0xFFFFAEB7.toInt()),
        ),
    ),

    /**
     * «Traditionell»: TradingView-Kerzen (#089981 / #F23645), nur so weit angepasst, wie der
     * Kontrast verlangt; je ΔE2000 ≥ 8 zu «Frisch». Fallend dunkel #FE7062 (≈ 6°) statt
     * Binance-nahem Rosa-Rot, damit es sich von «Frisch» abhebt.
     */
    TRADITIONAL(
        listOf(
            PriceTone(0xFF0A7262.toInt(), 0xFF3EAE97.toInt(), 0xFF005246.toInt(), 0xFF5AD8BE.toInt()),
        ),
        listOf(
            PriceTone(0xFFCC0416.toInt(), 0xFFFE7062.toInt(), 0xFF960208.toInt(), 0xFFFEB2A4.toInt()),
        ),
    ),

    /**
     * «Farbsehschwäche»: Blau steigend, Orange fallend wie die Farbsehschwäche-Option der
     * Börsen-Apps – feste Standardfarben, kein Ersatz je Akzent (ruhigere
     * Werte trägt [CALM_COLOR_VISION]). Nah an #1565C0 / #E65100 (hell) bzw.
     * #3D9BFF / #F7931A (dunkel); Helligkeit ≥ 1.4:1 getrennt und bei jeder simulierten
     * Farbsehschwäche ΔE2000 ≥ 35.
     */
    BLUE_ORANGE(
        listOf(
            PriceTone(0xFF064E93.toInt(), 0xFF4F9FFD.toInt(), 0xFF00335F.toInt(), 0xFFCFEAFF.toInt()),
        ),
        listOf(
            PriceTone(0xFFB73900.toInt(), 0xFFFFA73E.toInt(), 0xFF842A00.toInt(), 0xFFFFB46A.toInt()),
        ),
    ),

    /**
     * «Ruhig Farbsehschwäche»: gedämpftes Blau steigend, gedämpftes Orange fallend.
     * Weicht wie «Ruhig» je Akzent aus, auf der Blau-Gelb-Achse:
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
object PriceTones {
    val CALM_GREEN = PriceTone(0xFF396E54.toInt(), 0xFF61AE87.toInt(), 0xFF294F3C.toInt(), 0xFFA1CFB8.toInt())

    /**
     * «Ruhig» fallend: hell dunkles Rot (#771D23, ≥ 8.1:1 auf allen hellen Flächen), dunkel
     * Lachs-Rot (#FFB097, HSL 14°, C* 35.6 — das bunteste Rot 5–15°, das 1.5:1 heller als das Grün
     * bleibt, auch bei Deuteranopie, nicht das rosigere #FFAFA8; ΔE ≥ 39 zu Blau,
     * Grün, Marrs Green).
     */
    val CALM_RED = PriceTone(0xFF771D23.toInt(), 0xFFFFB097.toInt(), 0xFF5A0813.toInt(), 0xFFFFD1CF.toInt())

    /**
     * «Ruhig» fallend neben Orange (Standard-Akzent): Karmin, liest sich als Rot. Ein Rot neben Orange
     * erreicht ΔE 24 nicht (heller als L* ≈ 27 bzw. bunt genug nur bis ≈ 19 / 21), daher eigene
     * Schwelle 19 hell / 20 dunkel: ΔE 19.3 / 20.3 zu #A64827 / #ED835E, C* 40.3 / 31.4 (dunkel
     * #FFAFBB, HSL 351°: so rot, wie ΔE 20 zu Orange und C* ≥ 30 erlauben).
     * Hoher Kontrast: ΔE 15.2 / 15.0 zum Akzent mit hohem Kontrast.
     */
    val CALM_CRIMSON = PriceTone(
        0xFF77212F.toInt(), 0xFFFFAFBB.toInt(), 0xFF51000D.toInt(), 0xFFFFE3E7.toInt(),
        minAccentLight = 19.0, minAccentDark = 20.0,
    )

    /**
     * «Ruhig» fallend neben Rot: Pflaume (ΔE 27.4 / 25.2 zu #BE2B38 / #FF7173). Röter geht dunkel
     * nicht: jedes Rot mit ΔE ≥ 24 zum roten Akzent und 1.5:1 heller als das Grün ist fast weiss (C* ≤ 17).
     */
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
