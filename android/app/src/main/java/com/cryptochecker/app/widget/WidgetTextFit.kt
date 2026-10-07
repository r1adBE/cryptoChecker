package com.cryptochecker.app.widget

/**
 * Welcher Text passt in eine Widget-Zeile? Reine Regeln ohne Android (getestet in
 * WidgetTextFitTest); gemessen wird im [WidgetUpdater] mit der echten Schrift.
 * Lieber kürzer als abgeschnitten: «BT…» oder «heute ▲ +997.6…» sagen nichts.
 */
object WidgetTextFit {

    /** Rand links + rechts des Einzel-Widgets, Logo und Abstand dahinter (dp, widget_single.xml). */
    const val SINGLE_HEADER_FIXED_DP = 2 * 12f + 16f + 6f

    /** Sicherheitsabstand für Rundungen beim Messen (dp). */
    const val SAFETY_DP = 2f

    /**
     * Erster Text aus [candidates] (vom längsten zum kürzesten), der in [available] passt;
     * passt keiner, der letzte (kürzeste). Unbekannter Platz (null): der erste.
     * [available] und [measure] in derselben Einheit (dp oder px).
     */
    fun firstFitting(candidates: List<String>, available: Float?, measure: (String) -> Float): String {
        val list = candidates.filter { it.isNotEmpty() }.distinct()
        if (list.isEmpty()) return ""
        if (available == null) return list.first()
        return list.firstOrNull { measure(it) <= available } ?: list.last()
    }

    /**
     * Kopfzeile des Einzel-Widgets: volles Paar («BTC/USDT»), wenn es neben der
     * Veränderung Platz hat, sonst nur die Basis («BTC»). Die Veränderung selbst
     * wird nie gekürzt (sie steht mit ihrer vollen Breite daneben).
     * @param widgetWidthDp Breite des Widgets (0 = unbekannt → volles Paar)
     * @param changeWidthDp gemessene Breite der Veränderung
     */
    fun singlePairLabel(
        pair: String,
        base: String,
        widgetWidthDp: Int,
        changeWidthDp: Float,
        measureDp: (String) -> Float,
    ): String {
        val available = if (widgetWidthDp > 0) {
            widgetWidthDp - SINGLE_HEADER_FIXED_DP - changeWidthDp - SAFETY_DP
        } else null
        return firstFitting(listOf(pair, base), available, measureDp)
    }

    /**
     * «heute»-Pille des Portfolio-Widgets: voller Text (Betrag · Prozent), wenn er
     * passt, sonst nur der Prozentwert. Ohne Prozentwert bleibt der volle Text.
     */
    fun todayText(full: String, percentOnly: String?, availableDp: Float?, measureDp: (String) -> Float): String =
        firstFitting(listOfNotNull(full, percentOnly), availableDp?.minus(SAFETY_DP), measureDp)
}
