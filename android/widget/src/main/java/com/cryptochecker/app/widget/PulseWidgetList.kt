package com.cryptochecker.app.widget

/**
 * Coins als Liste im grossen Widget «Was gerade auffällt» (reine Regeln, getestet in PulseWidgetListTest; wie iOS
 * `PulseWidgetHome`): bleibt unter «Kurz gesagt» genug Platz, stehen die Coins untereinander mit Namen und
 * 24-h-Veränderung statt als Zeile «BTC ↗ +2.8%». Die Sätze haben Vorrang; reicht der Platz nicht, bleibt die Zeile.
 */
object PulseWidgetList {

    /** Abstand über der Liste und Mindesthöhe einer Coin-Zeile (dp, widget_pulse.xml). */
    const val TOP_DP = 8f
    const val ROW_MIN_DP = 22f

    /** Die Zeile mit den Coins, die die Liste ersetzt (12 sp und 4 dp Abstand). */
    const val CHIP_ROW_DP = 20f

    /** Schriftgrössen (sp): Name und Veränderung, «24h». */
    const val ROW_SP = 14f
    const val HEADER_SP = 12f

    /**
     * Mehrbedarf der Liste gegenüber der Zeile (dp) für [coins] Coins; [rowTextDp] und [headerDp] gemessen (mit der
     * Schriftgrösse des Geräts), eine Zeile mindestens [ROW_MIN_DP].
     */
    fun extraDp(coins: Int, rowTextDp: Float, headerDp: Float): Float =
        TOP_DP + headerDp + coins * maxOf(ROW_MIN_DP, rowTextDp) - CHIP_ROW_DP

    /** Liste statt Zeile: nur gross ([large]), mit Coins und wenn [freeDp] (Platz unter dem Block) reicht. */
    fun fits(large: Boolean, freeDp: Float, coins: Int, rowTextDp: Float, headerDp: Float): Boolean =
        large && coins > 0 && freeDp >= extraDp(coins, rowTextDp, headerDp)
}
