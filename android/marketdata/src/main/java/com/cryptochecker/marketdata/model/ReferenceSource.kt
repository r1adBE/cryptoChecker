package com.cryptochecker.marketdata.model

/**
 * Herkunft eines Devisen-Referenzkurses (Abschnitt «Referenzkurs» unter «Devisen»): welche Zentralbank, wie oft sie
 * veröffentlicht, und ob der gezeigte Wert umgerechnet ist. Jede Währung kommt aus genau einer Quelle, EZB vor NBU vor
 * NBP ([com.cryptochecker.marketdata.util.ReferenceRates.assign]). iOS: `ReferenceSource.swift`, gleiche Fälle in
 * `testdata/parity/reference_rates.json`.
 *
 * @param exchangeKey Schlüssel der Quelle in [com.cryptochecker.marketdata.config.ExchangesConfig].
 * @param weekly true: neue Kurse einmal pro Woche (NBP Tabelle B, mittwochs), sonst täglich.
 * @param home Währung, in der die Quelle ihre Kurse festlegt (EZB: Einheiten je 1 EUR; NBU: UAH je Einheit; NBP: PLN
 *   je Einheit).
 */
enum class ReferenceSource(val exchangeKey: String, val weekly: Boolean, val home: String) {
    ECB("EcbReference", weekly = false, home = "EUR"),
    NBU("NbuReference", weekly = false, home = "UAH"),
    NBP("NbpReference", weekly = true, home = "PLN"),
    ;

    /**
     * Ist der gezeigte Wert von [base] in eine andere Währung umgerechnet oder eine selbst berechnete Veränderung
     * ([hasChange])? Dann sagt die Quellzeile «· umgerechnet» (die EZB verlangt einen klaren Hinweis, wenn ihre Daten
     * verändert gezeigt werden). Unverändert ist nur der Kurs der EZB zur Basis EUR ohne Veränderung; die Paare von
     * NBU und NBP (Währung gegen USD bzw. EUR) sind immer Kreuzkurse.
     */
    fun converted(base: String, hasChange: Boolean): Boolean = hasChange || base != home || this != ECB

    companion object {
        /** Quelle zum Börsen-Schlüssel [exchangeKey]; null = kein Referenzkurs. */
        fun of(exchangeKey: String?): ReferenceSource? = entries.firstOrNull { it.exchangeKey == exchangeKey }
    }
}
