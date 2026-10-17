package com.cryptochecker.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG-Kontrast der Kursfarben: normal ≥ 4.5:1 (AA), hoher Kontrast ≥ 7:1 (AAA).
 * Gegen alle hellen Flächen #FFFFFF … #E2E2E2 und alle dunklen #0E0E0E … #353535 (bis
 * surfaceContainerHighest) — die Änderungen stehen als farbige Schrift ohne Pille direkt auf diesen Flächen.
 * #EEEEEE/#1F1F1F sind auch die Grundfarben der Widgets (WidgetColors).
 */
class PriceColorSchemeTest {

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun required(highContrast: Boolean) = if (highContrast) 7.0 else 4.5

    private data class Case(
        val scheme: PriceColorScheme,
        val highContrast: Boolean,
        val up: Boolean,
        val dark: Boolean,
        val accent: AccentColor? = null,
    ) {
        val color: Int get() =
            if (up) scheme.up(dark, highContrast, accent = accent) else scheme.down(dark, highContrast, accent = accent)
        fun key(background: Int) =
            "${scheme.name}/${accent?.name}/${if (highContrast) "hc" else "normal"}/${if (up) "up" else "down"}/" +
                "%06X".format(background and 0xFFFFFF)
    }

    // Jede Akzentfarbe (und keine): auch die Ersatzfassungen der «Ruhig»-Schemata halten den Kontrast
    private val cases = PriceColorScheme.entries.flatMap { scheme ->
        (AccentColor.entries + listOf<AccentColor?>(null)).flatMap { accent ->
            listOf(false, true).flatMap { hc ->
                listOf(true, false).flatMap { up -> listOf(false, true).map { dark -> Case(scheme, hc, up, dark, accent) } }
            }
        }
    }

    @Test
    fun `Werte wie in der Spezifikation`() {
        // Feste Börsenfarben (Entscheid 18. Oktober 2026): hell, dunkel, je normal und hoher Kontrast
        assertTones(PriceColorScheme.GREEN_RED, 0xFF005A30, 0xFF18EA96, 0xFF00421E, 0xFF81FFBA, up = true)
        assertTones(PriceColorScheme.GREEN_RED, 0xFFCC0015, 0xFFFF6F69, 0xFF960000, 0xFFFFB1AB, up = false)
        assertTones(PriceColorScheme.TRADITIONAL, 0xFF033C00, 0xFF87FF7E, 0xFF002100, 0xFFE4FFE4, up = true)
        assertTones(PriceColorScheme.TRADITIONAL, 0xFF990000, 0xFFFF9C99, 0xFF630000, 0xFFF9D2CD, up = false)
        assertTones(PriceColorScheme.BLUE_ORANGE, 0xFF003372, 0xFF96D8FF, 0xFF002A5A, 0xFFC9E7FF, up = true)
        assertTones(PriceColorScheme.BLUE_ORANGE, 0xFFAE4200, 0xFFED810C, 0xFF7B3300, 0xFFFFB469, up = false)
        // «Ruhig Farbsehschwäche»: die früheren Werte von «Farbsehschwäche»
        assertEquals(0xFF1460AB.toInt(), PriceColorScheme.CALM_COLOR_VISION.up(dark = false))
        assertEquals(0xFF9F4300.toInt(), PriceColorScheme.CALM_COLOR_VISION.down(dark = false))
        assertEquals(0xFF6C2E00.toInt(), PriceColorScheme.CALM_COLOR_VISION.down(dark = false, highContrast = true))
        // Ohne Angabe: normale Werte, nicht getauscht
        assertEquals(PriceColorScheme.GREEN_RED.up(true, false, false), PriceColorScheme.GREEN_RED.up(true))
    }

    private fun assertTones(scheme: PriceColorScheme, light: Long, dark: Long, lightHc: Long, darkHc: Long, up: Boolean) {
        fun c(d: Boolean, hc: Boolean) = if (up) scheme.up(d, hc) else scheme.down(d, hc)
        val key = "${scheme.name}/${if (up) "up" else "down"}"
        assertEquals(key, light.toInt(), c(false, false))
        assertEquals(key, dark.toInt(), c(true, false))
        assertEquals(key, lightHc.toInt(), c(false, true))
        assertEquals(key, darkHc.toInt(), c(true, true))
    }

    @Test
    fun `Tauschen vertauscht nur die Farben`() {
        for (scheme in PriceColorScheme.entries) for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
            assertEquals(scheme.down(dark, hc), scheme.up(dark, hc, inverted = true))
            assertEquals(scheme.up(dark, hc), scheme.down(dark, hc, inverted = true))
        }
    }

    /** Alle hellen bzw. dunklen Flächen (Schrift ohne Pille): streng, ohne Ausnahmen. */
    @Test
    fun `Kontrast auf allen Flaechen`() {
        for (c in cases) {
            val backgrounds = if (c.dark) DARK_SURFACES else LIGHT_SURFACES
            for (bg in backgrounds) {
                val ratio = contrast(c.color, bg)
                assertTrue("${c.key(bg)}: %.2f".format(ratio), ratio >= required(c.highContrast))
            }
        }
    }

    /** Reihenfolge der Karten: Ruhig, Frisch, Traditionell, Farbsehschwäche, Ruhig Farbsehschwäche; Standard «Ruhig». */
    @Test
    fun `Reihenfolge und Standard`() {
        assertEquals(
            listOf(PriceColorScheme.CALM, PriceColorScheme.GREEN_RED, PriceColorScheme.TRADITIONAL, PriceColorScheme.BLUE_ORANGE,
                PriceColorScheme.CALM_COLOR_VISION),
            PriceColorScheme.entries.toList()
        )
        assertEquals(PriceColorScheme.CALM, PriceColorScheme.DEFAULT)
        // Nie gewählt (nichts gespeichert) → neuer Standard; gespeicherte Wahl «Frisch» bleibt
        assertEquals(PriceColorScheme.CALM, PriceColorScheme.fromName(null))
        assertEquals(PriceColorScheme.GREEN_RED, PriceColorScheme.fromName("GREEN_RED"))
    }

    /** «Ruhig»: Werte und auf #131313 (Grundfläche dunkel) deutlich über AA. */
    @Test
    fun `Ruhig Werte und Kontrast`() {
        assertEquals(0xFF396E54.toInt(), PriceColorScheme.CALM.up(dark = false))
        assertEquals(0xFF61AE87.toInt(), PriceColorScheme.CALM.up(dark = true))
        assertEquals(0xFF771D23.toInt(), PriceColorScheme.CALM.down(dark = false))
        assertEquals(0xFFFFAFA8.toInt(), PriceColorScheme.CALM.down(dark = true))
        for (up in listOf(true, false)) {
            val color = if (up) PriceColorScheme.CALM.up(dark = true) else PriceColorScheme.CALM.down(dark = true)
            assertTrue(contrast(color, 0xFF131313.toInt()) >= 6.5)
        }
    }

    /**
     * «Ruhig»: steigend und fallend auch in der Helligkeit getrennt (hell fallend dunkler, dunkel
     * heller) — sonst bei Deuteranopie kaum unterscheidbar (früher 1.16:1). Normal ≥ 1.5:1
     * (Leuchtdichte und simuliert), hoher Kontrast ≥ 1.25:1; für jede Akzentfarbe.
     */
    @Test
    fun `Ruhig steigend und fallend in der Helligkeit getrennt`() {
        for (accent in AccentColor.entries + listOf<AccentColor?>(null)) for (dark in listOf(false, true)) {
            for (hc in listOf(false, true)) {
                val up = PriceColorScheme.CALM.up(dark, hc, accent = accent)
                val down = PriceColorScheme.CALM.down(dark, hc, accent = accent)
                val min = if (hc) 1.25 else 1.5
                val key = "${accent?.name}/dark=$dark/hc=$hc"
                assertTrue("$key: %.2f".format(contrast(up, down)), contrast(up, down) >= min)
                assertTrue("$key deut: %.2f".format(deuteranopeContrast(up, down)), deuteranopeContrast(up, down) >= min)
                // Richtung der Trennung: fallend hell dunkler, dunkel heller
                assertTrue(key, if (dark) luminance(down) > luminance(up) else luminance(down) < luminance(up))
            }
        }
        // Belegt: die früheren Werte lagen gleich hell
        assertTrue(deuteranopeContrast(0xFF396E54.toInt(), 0xFFA74340.toInt()) < 1.2)
    }

    /** Steigend und fallend klar verschieden, in jedem Stil und jeder Fassung. */
    @Test
    fun `Steigend und fallend verschieden`() {
        for (scheme in PriceColorScheme.entries) for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
            assertTrue("${scheme.name}/$dark/$hc", scheme.up(dark, hc) != scheme.down(dark, hc))
        }
    }

    @Test
    fun `fromName faellt auf den Standard zurueck`() {
        assertEquals(PriceColorScheme.BLUE_ORANGE, PriceColorScheme.fromName("BLUE_ORANGE"))
        assertEquals(PriceColorScheme.CALM_COLOR_VISION, PriceColorScheme.fromName("CALM_COLOR_VISION"))
        assertEquals(PriceColorScheme.TRADITIONAL, PriceColorScheme.fromName("TRADITIONAL"))
        assertEquals(PriceColorScheme.GREEN_RED, PriceColorScheme.fromName("GREEN_RED"))
        assertEquals(PriceColorScheme.DEFAULT, PriceColorScheme.fromName(null))
        assertEquals(PriceColorScheme.DEFAULT, PriceColorScheme.fromName("BLUE_YELLOW"))
    }

    /**
     * «Frisch», «Traditionell» und «Farbsehschwäche»: feste Börsenfarben — bei jeder Akzentfarbe,
     * hell/dunkel, normal/hoher Kontrast und getauscht genau der Standard (Entscheid 16. Oktober 2026).
     */
    @Test
    fun `Boersenschemata bleiben bei jedem Akzent Standard`() {
        val fixed = listOf(PriceColorScheme.GREEN_RED, PriceColorScheme.TRADITIONAL, PriceColorScheme.BLUE_ORANGE)
        for (scheme in fixed) {
            for (accent in AccentColor.entries) for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
                for (inv in listOf(false, true)) {
                    val key = "${scheme.name}/${accent.name}/$dark/$hc/$inv"
                    assertEquals(key, scheme.up(dark, hc, inv), scheme.up(dark, hc, inv, accent))
                    assertEquals(key, scheme.down(dark, hc, inv), scheme.down(dark, hc, inv, accent))
                }
            }
            for (accent in AccentColor.entries + listOf<AccentColor?>(null)) assertTrue(!scheme.clashesWith(accent))
        }
    }

    /** Blaue Akzentfarbe + «Ruhig Farbsehschwäche»: Steigend in Petrol, AA/AAA auf allen Flächen (auch getauscht). */
    @Test
    fun `Blauer Akzent Petrol Kontrast auf allen Flaechen`() {
        for (hc in listOf(false, true)) for (dark in listOf(false, true)) for (inv in listOf(false, true)) {
            val up = PriceColorScheme.CALM_COLOR_VISION.up(dark, hc, inv, AccentColor.BLUE)
            val down = PriceColorScheme.CALM_COLOR_VISION.down(dark, hc, inv, AccentColor.BLUE)
            for (color in listOf(up, down)) for (bg in if (dark) DARK_SURFACES else LIGHT_SURFACES) {
                val ratio = contrast(color, bg)
                assertTrue("petrol/$hc/$dark/$inv/%06X: %.2f".format(bg and 0xFFFFFF, ratio), ratio >= required(hc))
            }
            assertTrue(up != down)
        }
    }

    /** Petrol klar verschieden vom blauen Akzent (normal und hoher Kontrast): ΔE2000 ≥ 15. */
    @Test
    fun `Blauer Akzent Steigend klar verschieden`() {
        for (hc in listOf(false, true)) for (dark in listOf(false, true)) {
            val rise = PriceColorScheme.CALM_COLOR_VISION.up(dark, hc, accent = AccentColor.BLUE)
            val accents = if (dark) BLUE_ACCENT_DARK else BLUE_ACCENT_LIGHT
            for (accent in accents) {
                val d = deltaE2000(rise, accent)
                assertTrue("$hc/$dark/%06X: ΔE %.1f".format(accent and 0xFFFFFF, d), d >= 15.0)
            }
            // Getauscht: Petrol dann für fallend
            assertEquals(rise, PriceColorScheme.CALM_COLOR_VISION.down(dark, hc, inverted = true, accent = AccentColor.BLUE))
        }
        // Belegt: das bisherige Blau lag zu nah am Akzent (hell ΔE ≈ 5)
        assertTrue(deltaE2000(PriceColorScheme.CALM_COLOR_VISION.up(dark = false), BLUE_ACCENT_LIGHT[0]) < 15.0)
    }

    /**
     * «Ruhig» und «Ruhig Farbsehschwäche» × jede Akzentfarbe: steigend und fallend je ΔE2000 ≥ 24 zum Akzent (hell und dunkel,
     * normaler Kontrast). Einzige Ausnahme: fallend neben Orange (Karmin) mit eigener Schwelle
     * 19 hell / 20 dunkel — ein Rot neben Orange erreicht 24 nicht. Unverändert bleibt eine
     * Kursfarbe genau dann, wenn schon der Standard 24 weit weg liegt. Steigend und fallend bleiben
     * in jedem Schema klar verschieden.
     */
    @Test
    fun `Kursfarben klar verschieden von der Akzentfarbe`() {
        val min = PriceColorScheme.MIN_ACCENT_DELTA_E
        for (scheme in PriceColorScheme.entries) for (accent in AccentColor.entries) {
            if (scheme in ADAPTIVE) for (dark in listOf(false, true)) for (up in listOf(true, false)) {
                val color = if (up) scheme.up(dark, accent = accent) else scheme.down(dark, accent = accent)
                val plain = if (up) scheme.up(dark) else scheme.down(dark)
                val d = deltaE2000(color, accent.primary(dark))
                val key = "${scheme.name}/${accent.name}/dark=$dark/${if (up) "up" else "down"}"
                val floor = scheme.accentFloor(up, dark, accent)
                val relaxed = scheme == PriceColorScheme.CALM && accent == AccentColor.ORANGE && !up
                assertEquals(key, if (relaxed) (if (dark) 20.0 else 19.0) else min, floor, 0.0)
                assertTrue("$key: ΔE %.1f".format(d), d >= floor)
                // Standard weit genug weg (hell und dunkel) → bleibt
                val plainFar = listOf(false, true).all { dk ->
                    val p = if (up) scheme.up(dk) else scheme.down(dk)
                    deltaE2000(p, accent.primary(dk)) >= min
                }
                if (plainFar) assertEquals(key, plain, color)
            }
            // Steigend und fallend bleiben auch mit Ersatz klar verschieden
            for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
                val d = deltaE2000(scheme.up(dark, hc, accent = accent), scheme.down(dark, hc, accent = accent))
                assertTrue("${scheme.name}/${accent.name}/$dark/$hc: ΔE %.1f".format(d), d >= 20.0)
            }
        }
        // Belegt: Standard «Ruhig» neben Orange (Standard-Akzent) lag bei ΔE ≈ 9.5
        assertTrue(deltaE2000(0xFFA74340.toInt(), AccentColor.ORANGE.primary(dark = false)) < 10.0)
        assertEquals(0xFF77212F.toInt(), PriceColorScheme.CALM.down(dark = false, accent = AccentColor.ORANGE))
        assertEquals(0xFFFFB1BE.toInt(), PriceColorScheme.CALM.down(dark = true, accent = AccentColor.ORANGE))
        assertEquals(0xFF672B61.toInt(), PriceColorScheme.CALM.down(dark = false, accent = AccentColor.RED))
        assertEquals(0xFF506299.toInt(), PriceColorScheme.CALM.up(dark = false, accent = AccentColor.GREEN))
        assertEquals(0xFFAE4200.toInt(), PriceColorScheme.BLUE_ORANGE.down(dark = false, accent = AccentColor.ORANGE))
        assertEquals(0xFF786200.toInt(), PriceColorScheme.CALM_COLOR_VISION.down(dark = false, accent = AccentColor.ORANGE))
        assertTrue(PriceColorScheme.CALM_COLOR_VISION.clashesWith(AccentColor.BLUE))
        assertTrue(!PriceColorScheme.BLUE_ORANGE.clashesWith(AccentColor.BLUE))
        assertTrue(PriceColorScheme.CALM.clashesWith(AccentColor.ORANGE))
        assertTrue(!PriceColorScheme.GREEN_RED.clashesWith(AccentColor.BLUE))
        assertTrue(!PriceColorScheme.BLUE_ORANGE.clashesWith(AccentColor.GREEN))
        assertTrue(!PriceColorScheme.BLUE_ORANGE.clashesWith(null))
    }

    /**
     * «Ruhig» neben Orange (Standard): fallend liest sich als Rot — HSL-Farbton 345°…10°, bunt
     * (C* ≥ 40 hell, ≥ 30 dunkel; früher Weinrot/Rosé HSL 328°/334°). Hoher Kontrast im selben Farbton.
     */
    @Test
    fun `Ruhig neben Orange fallend rot`() {
        for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
            val c = PriceColorScheme.CALM.down(dark, hc, accent = AccentColor.ORANGE)
            val key = "dark=$dark/hc=$hc/%06X".format(c and 0xFFFFFF)
            val hue = hslHue(c)
            assertTrue("$key: Farbton %.0f".format(hue), hue >= 345.0 || hue <= 10.0)
            val lab = ColorDifference.lab(c)
            val chroma = kotlin.math.hypot(lab[1], lab[2])
            val minChroma = when {
                hc && dark -> 10.0 // sehr hell (≥ 7:1 und 1.25:1 heller als steigend)
                hc -> 35.0
                dark -> 30.0
                else -> 40.0
            }
            assertTrue("$key: C* %.1f".format(chroma), chroma >= minChroma)
        }
        // Belegt: das frühere Weinrot war kein Rot
        assertTrue(hslHue(0xFF701F4A.toInt()) in 300.0..340.0)
    }

    private fun hslHue(argb: Int): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        if (d == 0.0) return 0.0
        val h = when (max) {
            r -> ((g - b) / d).mod(6.0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return h * 60
    }

    /** Hoher Kontrast nimmt dieselbe Fassung; Abstand zum Akzent dort mindestens ΔE2000 15. */
    @Test
    fun `Hoher Kontrast Ersatz klar verschieden`() {
        for (scheme in PriceColorScheme.entries) for (accent in AccentColor.entries) for (dark in listOf(false, true)) {
            val hcAccent = com.cryptochecker.app.widget.WidgetContrast.highContrastAccent(accent.primary(dark), dark)
            for (up in listOf(true, false)) {
                if (!scheme.clashesWith(accent)) continue
                val color = if (up) scheme.up(dark, true, accent = accent) else scheme.down(dark, true, accent = accent)
                val normal = if (up) scheme.up(dark, false, accent = accent) else scheme.down(dark, false, accent = accent)
                val plainNormal = if (up) scheme.up(dark) else scheme.down(dark)
                if (normal == plainNormal) continue // diese Seite unverändert
                val d = deltaE2000(color, hcAccent)
                assertTrue("${scheme.name}/${accent.name}/$dark/$up: ΔE %.1f".format(d), d >= 15.0)
            }
        }
    }

    /*
     * Feste Börsenfarben «Frisch», «Traditionell», «Farbsehschwäche» (Entscheid 18. Oktober 2026).
     * Die Regeln gelten für jeden künftigen Wert; nachrechnen mit android/tools/check_price_colors.py.
     */

    /**
     * Regel 3: hell ist fallend die hellere, kräftigere Farbe (echtes Rot/Orange auf Papier), dunkel
     * die dunklere (wie an den Börsen); Leuchtdichte-Verhältnis ≥ 1.4 («Farbsehschwäche» ≥ 1.5), hoher Kontrast ≥ 1.25 wie «Ruhig». Regel 5: auch
     * nach Deuteranopie-Simulation, bei «Farbsehschwäche» nach allen drei Simulationen.
     */
    @Test
    fun `Boersenfarben in der Helligkeit getrennt`() {
        for (scheme in FIXED) for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
            val up = scheme.up(dark, hc)
            val down = scheme.down(dark, hc)
            val min = minRatio(scheme, hc)
            val key = "${scheme.name}/dark=$dark/hc=$hc"
            // Hell fallend heller, dunkel fallend dunkler
            assertTrue("$key: Richtung", if (dark) luminance(down) < luminance(up) else luminance(down) > luminance(up))
            assertTrue("$key: %.3f".format(contrast(up, down)), contrast(up, down) >= min)
            val sims = if (scheme == PriceColorScheme.BLUE_ORANGE) CVD.keys else setOf("deut")
            for (sim in sims) {
                val r = simulatedContrast(up, down, CVD.getValue(sim))
                assertTrue("$key $sim: %.3f".format(r), r >= min)
            }
        }
    }

    /**
     * Regeln 4/5: ΔE2000 ≥ 10 («Farbsehschwäche» ≥ 15) zu jedem Akzent (primary desselben Modus,
     * auch für hohen Kontrast) — ausser dem derselben Farbfamilie (Rot neben Rot, Grün neben
     * Grün/Marrs Green, Blau neben Blau, Orange neben Orange).
     */
    @Test
    fun `Boersenfarben klar verschieden von fremden Akzenten`() {
        for (scheme in FIXED) for (dark in listOf(false, true)) for (hc in listOf(false, true)) for (up in listOf(true, false)) {
            val color = if (up) scheme.up(dark, hc) else scheme.down(dark, hc)
            val family = SAME_FAMILY.getValue(scheme).let { if (up) it.first else it.second }
            val min = if (scheme == PriceColorScheme.BLUE_ORANGE) 15.0 else 10.0
            for (accent in AccentColor.entries - family) {
                val d = deltaE2000(color, accent.primary(dark))
                assertTrue("${scheme.name}/${accent.name}/dark=$dark/hc=$hc/up=$up: ΔE %.2f".format(d), d >= min)
            }
        }
    }

    /**
     * Regel 1: echte Farbtöne (HSL) — «Frisch» steigend 140–165°, fallend 350–10°; «Traditionell»
     * 115–135° / 0–8°, je ΔE2000 ≥ 10 zur selben Farbe von «Frisch», damit die Karten verschieden aussehen.
     */
    @Test
    fun `Frisch und Traditionell echte Farbtoene und verschieden`() {
        for (dark in listOf(false, true)) for (hc in listOf(false, true)) {
            val key = "dark=$dark/hc=$hc"
            val fresh = PriceColorScheme.GREEN_RED
            val trad = PriceColorScheme.TRADITIONAL
            assertTrue("$key Frisch up", hslHue(fresh.up(dark, hc)) in 140.0..165.0)
            hslHue(fresh.down(dark, hc)).let { assertTrue("$key Frisch down %.1f".format(it), it >= 350.0 || it <= 10.0) }
            assertTrue("$key Trad up", hslHue(trad.up(dark, hc)) in 115.0..135.0)
            assertTrue("$key Trad down", hslHue(trad.down(dark, hc)) in 0.0..8.0)
            assertTrue("$key up", deltaE2000(fresh.up(dark, hc), trad.up(dark, hc)) >= 10.0)
            assertTrue("$key down", deltaE2000(fresh.down(dark, hc), trad.down(dark, hc)) >= 10.0)
        }
    }

    /**
     * Regel 4: «Farbsehschwäche» steigend/fallend nach Protanopie-, Deuteranopie- und Tritanopie-
     * Simulation (Machado 2009, volle Ausprägung) ΔE2000 ≥ 40; hoher Kontrast ≥ 35 (dunkel lassen
     * ≥ 7:1 und 1.25:1 Abstand nur Pastelltöne zu, Blau/Orange erreicht dort höchstens ≈ 38.7).
     */
    @Test
    fun `Farbsehschwaeche simuliert klar getrennt`() {
        val scheme = PriceColorScheme.BLUE_ORANGE
        for (dark in listOf(false, true)) for (hc in listOf(false, true)) for ((sim, m) in CVD) {
            val a = simulate(scheme.up(dark, hc), m)
            val b = simulate(scheme.down(dark, hc), m)
            val d = ColorDifference.deltaE2000(labLinear(a), labLinear(b))
            assertTrue("dark=$dark/hc=$hc/$sim: ΔE %.2f".format(d), d >= if (hc) 35.0 else 40.0)
        }
        // Belegt: die früheren Werte lagen gleich hell (1.00:1) und neben dem blauen Akzent (ΔE 1.9)
        assertTrue(contrast(0xFF005ED6.toInt(), 0xFFB63900.toInt()) < 1.05)
        assertTrue(deltaE2000(0xFF005ED6.toInt(), AccentColor.BLUE.primary(dark = false)) < 2.0)
    }

    private fun minRatio(scheme: PriceColorScheme, hc: Boolean) = when {
        hc -> 1.25
        scheme == PriceColorScheme.BLUE_ORANGE -> 1.5
        else -> 1.4
    }

    /** Lineares sRGB nach Simulation [m] (3×3, zeilenweise), auf 0…1 begrenzt, ohne Rundung auf 8 Bit. */
    private fun simulate(argb: Int, m: DoubleArray): DoubleArray {
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return DoubleArray(3) { i -> (m[3 * i] * r + m[3 * i + 1] * g + m[3 * i + 2] * b).coerceIn(0.0, 1.0) }
    }

    private fun simulatedContrast(a: Int, b: Int, m: DoubleArray): Double {
        fun lum(c: DoubleArray) = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
        val la = lum(simulate(a, m))
        val lb = lum(simulate(b, m))
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** CIELAB (D65) aus linearem sRGB, wie [ColorDifference.lab]. */
    private fun labLinear(c: DoubleArray): DoubleArray {
        fun f(t: Double) = if (t > 216.0 / 24389.0) kotlin.math.cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val x = (0.4124 * c[0] + 0.3576 * c[1] + 0.1805 * c[2]) / 0.95047
        val y = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
        val z = (0.0193 * c[0] + 0.1192 * c[1] + 0.9505 * c[2]) / 1.08883
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun deltaE2000(c1: Int, c2: Int): Double = ColorDifference.deltaE2000(c1, c2)

    /** Leuchtdichte nach Deuteranopie-Simulation (Machado 2009, volle Ausprägung, lineares sRGB). */
    private fun deuteranopeLuminance(argb: Int): Double {
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        fun clamp(v: Double) = v.coerceIn(0.0, 1.0)
        val rs = clamp(0.367322 * r + 0.860646 * g - 0.227968 * b)
        val gs = clamp(0.280085 * r + 0.672501 * g + 0.047413 * b)
        val bs = clamp(-0.011820 * r + 0.042940 * g + 0.968881 * b)
        return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
    }

    private fun deuteranopeContrast(a: Int, b: Int): Double {
        val la = deuteranopeLuminance(a)
        val lb = deuteranopeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private companion object {
        /** Schemata, die neben einer zu ähnlichen Akzentfarbe ausweichen. */
        val ADAPTIVE = setOf(PriceColorScheme.CALM, PriceColorScheme.CALM_COLOR_VISION)

        /** Feste Börsenfarben (kein Ersatz je Akzent). */
        val FIXED = listOf(PriceColorScheme.GREEN_RED, PriceColorScheme.TRADITIONAL, PriceColorScheme.BLUE_ORANGE)

        /** Akzente derselben Farbfamilie (steigend, fallend): für sie gilt kein Mindestabstand. */
        val SAME_FAMILY = mapOf(
            PriceColorScheme.GREEN_RED to (setOf(AccentColor.GREEN, AccentColor.MARRS_GREEN) to setOf(AccentColor.RED)),
            PriceColorScheme.TRADITIONAL to (setOf(AccentColor.GREEN, AccentColor.MARRS_GREEN) to setOf(AccentColor.RED)),
            PriceColorScheme.BLUE_ORANGE to (setOf(AccentColor.BLUE) to setOf(AccentColor.ORANGE)),
        )

        /** Machado, Oliveira, Fernandes 2009, volle Ausprägung (lineares sRGB, zeilenweise). */
        val CVD = mapOf(
            "prot" to doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
            "deut" to doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881),
            "trit" to doubleArrayOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900),
        )

        /** Blauer Akzent (Knöpfe) hell: normal #2B5FC8, hoher Kontrast #1F4490 (AppColorSchemes / iOS). */
        val BLUE_ACCENT_LIGHT = listOf(0xFF2B5FC8.toInt(), 0xFF1F4490.toInt())
        /** Dunkel: normal #73A3FC, hoher Kontrast #A8C6FD. */
        val BLUE_ACCENT_DARK = listOf(0xFF73A3FC.toInt(), 0xFFA8C6FD.toInt())

        /** Weiss, surface, surfaceContainerLow … surfaceContainerHighest, tiefe Widget-Fläche #E4E4E4. */
        val LIGHT_SURFACES = listOf(
            0xFFFFFFFF.toInt(), 0xFFF9F9F9.toInt(), 0xFFF3F3F3.toInt(), 0xFFEEEEEE.toInt(),
            0xFFE8E8E8.toInt(), 0xFFE4E4E4.toInt(), 0xFFE2E2E2.toInt(),
            // K-9 warm (Lowest #FFFDFA, Widget-Grundfläche #F1EEE9, tiefe Fläche #E7E4DD)
            0xFFFFFDFA.toInt(), 0xFFFBF9F6.toInt(), 0xFFF6F3EE.toInt(), 0xFFF1EEE9.toInt(), 0xFFEBE8E2.toInt(),
            0xFFE7E4DD.toInt(), 0xFFE5E2DB.toInt(),
        )
        /** surfaceContainerLowest … surfaceContainerHighest, Auswahlfläche #333333. */
        val DARK_SURFACES = listOf(
            0xFF0E0E0E.toInt(), 0xFF121212.toInt(), 0xFF131313.toInt(), 0xFF1B1B1B.toInt(),
            0xFF1F1F1F.toInt(), 0xFF2A2A2A.toInt(), 0xFF333333.toInt(), 0xFF353535.toInt(),
        )
    }
}
