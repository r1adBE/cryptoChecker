package com.cryptochecker.app.widget

/**
 * Kopfzeile des Listen-Widgets: Logo · Titel («Merkliste») · «Uhrzeit · Dauer» · Knopf.
 * Reine Regeln ohne Android (getestet in ListWidgetHeaderTest); gemessen wird mit
 * [WidgetToolkit.textWidthDp] (echte Schrift). Nie abgeschnitten — passt es nicht, wird zuerst
 * der Titel kleiner (die Dauer der Aktualisierung bleibt so sichtbar), dann fällt die Dauer weg,
 * dann die Uhrzeit.
 * Der Titel bleibt immer.
 */
object ListWidgetHeader {

    /**
     * Feste Breiten (dp, widget_list.xml): Rand 2 × 8, Kopfzeile innen 4 + 0, Logo 18 + 4,
     * Abstand Titel → Uhrzeit 4, Uhrzeit-Rand rechts 4, Aktualisieren-Knopf 48 (Tippfläche; Summe wie früher 2 + 44).
     */
    const val FIXED_DP = 2 * 8f + 4f + 0f + 18f + 4f + 4f + 4f + 48f

    /** Schriftgrössen des Titels: normal, eng. */
    val TITLE_SIZES_SP = listOf(WidgetType.BODY, WidgetType.LABEL)

    /** Gewählte Darstellung: Schriftgrösse des Titels (sp) und Text rechts (leer = keiner). */
    data class Layout(val titleSp: Float, val status: String)

    /** «06:42:15 · 840 ms»; fehlende oder leere Teile entfallen. */
    fun statusText(time: String?, duration: String?): String =
        listOfNotNull(time, duration).filter { it.isNotEmpty() }.joinToString(" · ")

    /**
     * Erste Stufe, die ganz passt: (14 sp, Uhrzeit · Dauer), (12 sp, Uhrzeit · Dauer),
     * (14 sp, Uhrzeit), (12 sp, Uhrzeit), [(12 sp, Ersatz)], (12 sp, nur Titel). Passt keine, die letzte.
     * Unbekannte Breite (0): alles in normaler Grösse.
     * @param titleWidthDp Breite des Titels in der gegebenen Grösse (sp)
     * @param statusWidthDp Breite des Textes rechts
     * @param fallback kürzerer Text, bevor rechts gar nichts mehr steht (z. B. nur «veraltet»
     *   statt «veraltet · 06:42») — so geht der Hinweis nie vor der Uhrzeit verloren
     */
    fun layout(
        widthDp: Int,
        time: String?,
        duration: String?,
        titleWidthDp: (Float) -> Float,
        statusWidthDp: (String) -> Float,
        fallback: String? = null,
    ): Layout {
        val full = statusText(time, duration)
        val normal = TITLE_SIZES_SP.first()
        if (widthDp <= 0) return Layout(normal, full)
        val small = TITLE_SIZES_SP.last()
        val timeOnly = statusText(time, null)
        val available = widthDp - FIXED_DP - WidgetTextFit.SAFETY_DP
        val steps = listOfNotNull(
            Layout(normal, full),
            Layout(small, full),
            Layout(normal, timeOnly),
            Layout(small, timeOnly),
            fallback?.takeIf { it.isNotEmpty() }?.let { Layout(small, it) },
            Layout(small, ""),
        )
        return steps.firstOrNull { step ->
            val status = if (step.status.isEmpty()) 0f else statusWidthDp(step.status)
            titleWidthDp(step.titleSp) + status <= available
        } ?: steps.last()
    }
}
