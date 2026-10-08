package com.cryptochecker.app.settings

/**
 * Runde 23f: die vier Wahlmöglichkeiten der Seite «Kursfarben» — eine Liste statt
 * Segmentleiste plus Schalter. Gespeichert bleiben die bisherigen zwei Schlüssel
 * (`price_color_scheme`, `price_colors_inverted`); das hier ist nur die Abbildung
 * dazwischen. Wie iOS `PriceColorChoice`.
 *
 * Pfeile und Vorzeichen bleiben immer richtungsgebunden (▲ = steigend); getauscht
 * werden nur die Farben.
 */
enum class PriceColorChoice(val scheme: PriceColorScheme, val inverted: Boolean) {
    /** Standard: Grün steigt, Rot fällt. */
    GREEN_UP(PriceColorScheme.GREEN_RED, false),

    /** Rot steigt, Grün fällt (China, Japan, Korea, Taiwan). */
    RED_UP(PriceColorScheme.GREEN_RED, true),

    /** Farbsehschwäche: Blau steigt, Orange fällt. */
    BLUE_UP(PriceColorScheme.BLUE_ORANGE, false),

    /** Farbsehschwäche, getauscht: Orange steigt, Blau fällt. */
    ORANGE_UP(PriceColorScheme.BLUE_ORANGE, true),
    ;

    companion object {
        fun of(scheme: PriceColorScheme, inverted: Boolean): PriceColorChoice =
            entries.first { it.scheme == scheme && it.inverted == inverted }
    }
}
