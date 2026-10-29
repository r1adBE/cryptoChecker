package com.cryptochecker.app.util

/**
 * Grosse Schrift in dichten Zeilen (Merkliste, Positionen des Portfolios): umbrechen statt abschneiden.
 * [scale] = Schriftfaktor (Android `fontScale`, iOS Textgrösse / 17 pt, siehe `DenseRowText.swift`).
 *
 *  - ab [WRAP_FROM] (Android 1.3, iOS xxLarge) dürfen Paar bzw. Coin und Zeitzeile zwei Zeilen nehmen,
 *  - ab [STACK_FROM] (Android 1.5 … 2.0, iOS accessibility1 …) steht die rechte Spalte (Kurs bzw. Wert)
 *    unter dem Paar statt daneben — so bleibt links genug Breite, nichts wird abgeschnitten.
 *
 * Reines Kotlin, gleiche Werte wie iOS `DenseRowText`.
 */
object DenseRowText {
    const val WRAP_FROM = 1.2f
    const val STACK_FROM = 1.45f

    /** Höchstens so viele Zeilen für Paar/Coin und Zeitzeile. */
    fun maxLines(scale: Float): Int = if (scale >= WRAP_FROM) 2 else 1

    /** Rechte Spalte unter die linke stellen? */
    fun stacksTrailing(scale: Float): Boolean = scale >= STACK_FROM

    /** Zeilen schmaler als das (dp/pt) gelten als schmal: Mini-Chart dort schmaler. */
    const val NARROW_ROW = 360f
    const val SPARKLINE_WIDTH_REGULAR = 56f
    const val SPARKLINE_WIDTH_NARROW = 48f
    private const val CONVERTED_MAX_SHARE = 0.5f

    /**
     * Breite des Mini-Charts der Merkliste für eine Zeile von [rowWidth] dp/pt: 48 statt 56 in
     * schmalen Zeilen, damit das Paar dort mehr Platz behält (Kurs-Spalte bleibt unberührt).
     */
    fun sparklineWidth(rowWidth: Float): Float = if (rowWidth < NARROW_ROW) SPARKLINE_WIDTH_NARROW else SPARKLINE_WIDTH_REGULAR

    /**
     * Höchstbreite der ≈ Umrechnung neben dem Paar: die Hälfte der Zeile — sonst drückt die Kurs-Spalte
     * Paar, Name und Börse auf Breite 0. Längeres bricht um ([convertedMaxLines]).
     */
    fun convertedMaxWidth(rowWidth: Float): Float = rowWidth * CONVERTED_MAX_SHARE

    /** Zeilen der ≈ Umrechnung neben dem Paar; der Vermerk am Ende bleibt so sichtbar. */
    const val CONVERTED_MAX_LINES = 2

    /**
     * Höchstzahl Zeilen der ≈ Umrechnung: neben dem Paar (halbe Breite) [CONVERTED_MAX_LINES], erst danach
     * «…»; gestapelt (volle Breite) ohne Grenze.
     */
    fun convertedMaxLines(stacked: Boolean): Int = if (stacked) Int.MAX_VALUE else CONVERTED_MAX_LINES

    /**
     * Kurs der Merkliste eine Stufe grösser (Android `titleLarge`, iOS `.title3`)? Unter dem Paar
     * (gestapelt) immer, daneben nur bei normaler Schrift in Zeilen ab [NARROW_ROW] — sonst bliebe
     * dem Paar neben Logo, Mini-Chart und Kurs kaum Breite (Kurs wird nie abgeschnitten).
     */
    fun largePrice(scale: Float, rowWidth: Float): Boolean =
        stacksTrailing(scale) || (scale < WRAP_FROM && rowWidth >= NARROW_ROW)

    /**
     * Zeile «Börse ◇ 🔔 🔔 1» der Merkliste: Symbole in eine eigene Zeile unter die Börse? Ja, wenn Börsentext
     * ([lead], Eigenbreite) und Symbole ([icons]) mit [gap] dazwischen nicht in [available] passen (null = ohne
     * Grenze, nie). So bleiben Mini-Chart und Börsenname ganz (kein «Bi…»). Gleiche Regel wie iOS, `row_meta_line.json`.
     */
    fun metaWraps(available: Float?, lead: Float, icons: Float, gap: Float): Boolean =
        available != null && lead + gap + icons > available
}
