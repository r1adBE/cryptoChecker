package com.cryptochecker.app.widget

/**
 * Welcher Text passt in eine Widget-Zeile? Reine Regeln ohne Android (getestet in
 * WidgetTextFitTest); gemessen wird mit [WidgetToolkit.textWidthDp] (echte Schrift).
 * Lieber kürzer als abgeschnitten: «BT…» oder «heute ↗ +997.6…» sagen nichts.
 */
object WidgetTextFit {

    /** Rand links + rechts des Einzel-Widgets, Logo und Abstand dahinter (dp, widget_single.xml). */
    const val SINGLE_HEADER_FIXED_DP = 2 * 12f + 16f + 4f

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

    /** Trenner vor dem Zeitraum («… · 1J»); Zeitraum samt Trenner grau ([periodStart]). */
    const val PERIOD_SEPARATOR = " · "

    /** Abstand zwischen Betrag und Prozent in der Betragszeile («… CHF  −27.85%»). */
    const val PERCENT_GAP = "  "

    /**
     * Betragszeile der Portfolio-Widgets als Text (keine Pille): «↘ −31’997.28 CHF  −27.85% · 1J» —
     * Kandidaten vom längsten zum kürzesten für [firstFitting]: zuerst fällt der Zeitraum weg, dann der
     * Betrag (das Prozent bleibt, es stand früher in der Pille der Kopfzeile), zuletzt der Zeitraum
     * hinter dem Prozent. Ohne Prozent: Betrag mit und ohne Zeitraum.
     * @param arrow «↗» / «↘», leer bei 0 (dann kein Pfeil)
     * @param amount Betrag mit Vorzeichen und Währung, verborgen «•••»
     * @param percent «−27.85%» oder null
     * @param period «1J», «24h», «heute» … oder null
     */
    fun changeLineCandidates(arrow: String, amount: String, percent: String?, period: String?): List<String> {
        fun lead(text: String) = if (arrow.isEmpty()) text else "$arrow $text"
        fun withPeriod(text: String) = if (period.isNullOrEmpty()) text else text + PERIOD_SEPARATOR + period
        val value = lead(amount)
        return if (percent == null) {
            listOf(withPeriod(value), value)
        } else {
            val both = value + PERCENT_GAP + percent
            listOf(withPeriod(both), both, withPeriod(lead(percent)), lead(percent))
        }.distinct()
    }

    /**
     * Schriftgrössen der Betragszeile (sp): normal, dann kleiner, bevor ein Teil wegfällt.
     * J-18: nicht unter 11 sp — lieber fällt der Zeitraum bzw. Betrag weg.
     */
    val CHANGE_LINE_SIZES_SP = listOf(12f, 11f)

    /**
     * Wie [firstFitting], aber jeder Kandidat zuerst in allen [sizes] (gross → klein), bevor der
     * nächste, kürzere drankommt — lieber etwas kleiner als ohne Betrag oder Prozent. Passt nichts,
     * der letzte Kandidat in der kleinsten Grösse; unbekannter Platz (null): der erste in der ersten.
     * @param measure Breite eines Textes in einer Schriftgrösse (dieselbe Einheit wie [available])
     * @return Text und Schriftgrösse
     */
    fun firstFittingSized(
        candidates: List<String>,
        sizes: List<Float>,
        available: Float?,
        measure: (String, Float) -> Float,
    ): Pair<String, Float> {
        val list = candidates.filter { it.isNotEmpty() }.distinct()
        val first = sizes.firstOrNull() ?: return (list.firstOrNull() ?: "") to 0f
        if (list.isEmpty()) return "" to first
        if (available == null) return list.first() to first
        for (text in list) {
            for (size in sizes) {
                if (measure(text, size) <= available) return text to size
            }
        }
        return list.last() to sizes.last()
    }

    /**
     * Beginn des grauen Zeitraums in [text] («· 1J» am Ende, Trenner mitgezählt); null, wenn der
     * Text nicht mit «· [period]» endet. Der Zeitraum ist nie rot/grün, nur der Wert davor.
     */
    fun periodStart(text: String, period: String?): Int? {
        if (period.isNullOrEmpty()) return null
        val suffix = PERIOD_SEPARATOR + period
        return if (text.endsWith(suffix) && text.length > suffix.length) text.length - suffix.length + 1 else null
    }

    /** Merklisten-Zeile: Rand des Widgets 2 × 8, der Zeile 2 × 4, Abstand vor dem Kurs 4 (dp, widget_list*.xml). */
    const val LIST_ROW_FIXED_DP = 2 * 8f + 2 * 4f + 4f

    /** Coin-Logo der Merklisten-Zeile samt Abstand dahinter (dp, widget_list_item.xml). */
    const val LIST_ROW_LOGO_DP = 24f + 8f

    /** Kleinste Kursgrösse der Merklisten-Zeile, solange der Paarname noch ganz Platz haben soll: 70 % der
     * Grundgrösse bei normaler Systemschrift. */
    const val LIST_PRICE_MIN_FACTOR = 0.7f

    /** Kurs der Merklisten-Zeile: Schriftgrösse (sp) und Breite, die dem Paarnamen bleibt (dp, ≥ 0). */
    data class ListPriceFit(val sp: Float, val nameDp: Float)

    /**
     * Merklisten-Zeile mit [availableDp] für Paarname und Kurs (Layout: Kurs-Spalte `wrap_content` bekommt zuerst
     * ihre Breite, der Paarname mit Gewicht 1 nur den Rest, einzeilig, am Ende gekürzt). Breiten gemessen in [baseSp]
     * mit der Systemschrift ([priceWidthDp], [nameWidthDp]). Der Kurs ist immer vollständig:
     *  1. passen beide: [baseSp] (wächst mit der Systemschrift, keine Obergrenze);
     *  2. sonst (nur bei vergrösserter Systemschrift, [fontScale] > 1) wird der Kurs kleiner, damit der Name ganz
     *     bleibt — höchstens bis [listPriceFloorSp]; bei normaler Schrift weicht gleich der Name, der Kurs bleibt
     *     [baseSp] (wie bisher);
     *  3. reicht das nicht, weicht der Name (gekürzt), der Kurs bleibt bei der Untergrenze;
     *  4. passt der Kurs selbst ohne Namen nicht, wird er so klein, dass er genau passt (Name leer).
     * Unbekannte Breite (null oder ≤ 0): [baseSp], Name unbegrenzt ([Float.MAX_VALUE]).
     */
    fun listPriceFit(
        baseSp: Float,
        fontScale: Float,
        priceWidthDp: Float,
        nameWidthDp: Float,
        availableDp: Float?,
    ): ListPriceFit {
        if (availableDp == null || availableDp <= 0f) return ListPriceFit(baseSp, Float.MAX_VALUE)
        val price = maxOf(priceWidthDp, 0f)
        val name = maxOf(nameWidthDp, 0f)
        if (price + name <= availableDp || price == 0f) return ListPriceFit(baseSp, availableDp - price)
        if (fontScale <= 1f) {
            return if (price <= availableDp) ListPriceFit(baseSp, availableDp - price)
            else ListPriceFit(baseSp * availableDp / price, 0f)
        }
        val floor = minOf(listPriceFloorSp(baseSp, fontScale), baseSp)
        val keepName = baseSp * (availableDp - name) / price
        if (keepName >= floor) return ListPriceFit(keepName, availableDp - price * keepName / baseSp)
        val atFloor = price * floor / baseSp
        if (atFloor <= availableDp) return ListPriceFit(floor, availableDp - atFloor)
        return ListPriceFit(baseSp * availableDp / price, 0f)
    }

    /** Untergrenze der Kursgrösse bei [fontScale] (sp): [LIST_PRICE_MIN_FACTOR] × [baseSp] in wirklicher Grösse. */
    fun listPriceFloorSp(baseSp: Float, fontScale: Float): Float =
        baseSp * LIST_PRICE_MIN_FACTOR / (if (fontScale > 0f) fontScale else 1f)
}
