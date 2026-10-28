package com.cryptochecker.app.domain.portfolio

/**
 * Kurs von der Börse, die der Nutzer an seinen Transaktionen einträgt (reines Kotlin, testbar; Spiegel:
 * `Shared/Portfolio/PortfolioExchangeRoute.swift`).
 *
 * Regeln:
 *  - Ein Coin wird mit der eingetragenen Börse bewertet, wenn ALLE seine Transaktionen mit Börse dieselbe
 *    nennen ([exchangeByCoin]). Mehrere Börsen bei einem Coin → Binance (eine Position, ein Kurs) und ein
 *    Hinweis ([PortfolioPrices.mixedExchange]).
 *  - Umbuchungen ([exchangeByLedger]): Nach der LETZTEN Umbuchung mit Ziel liegt der Bestand dort — es zählen
 *    nur noch das Ziel und die Börsen der späteren Transaktionen; alles davor (auch frühere Umbuchungen)
 *    nicht mehr. Ziel = Wallet oder unbekannter Name → kein eigener Weg (Binance, wie freier Text).
 *  - Nur Spot-Börsen der App ([isSpot]); Binance ist ohnehin die Standardquelle (kein eigener Weg).
 *  - Paar: zuerst USDT, dann USD, dann Fiat-Währungen, die die App umrechnen kann ([QUOTES]); USD über den
 *    Marktkurs USDT/USD in USDT (ohne Kurs 1 — dann als Näherung markiert, [PortfolioStables.usdtUsd]), Fiat über
 *    den Devisenkurs und denselben USDT/USD-Kurs ([toUsdt]). Andere Stablecoins (USDC …) nicht — sie gelten im
 *    Portfolio nicht automatisch als 1.
 *  - Klappt der Weg nicht (Börse nicht erreichbar, kein Paar, kein Devisenkurs), bleibt der Binance-Kurs und
 *    der Coin steht in [PortfolioPrices.exchangeFailed].
 *  - Wertverlauf, «7 T» und «1 J» (auch Widget und Alarme): Tageskerzen der eigenen Börse, in USDT
 *    umgerechnet ([ownDailyUsdt]; reicht die Börse nicht weit genug zurück, davor Binance am ersten eigenen
 *    Tag angeglichen, [stitch]). Nur ohne eigene Tageskerzen Binance auf den Kurs der Börse skaliert
 *    ([scaleCloses]) — und nur mit Binance-Bezugskurs JETZT; sonst fehlt der Coin («ohne …»), [historyCloses].
 */
object PortfolioExchangeRoute {

    /** Märkte ohne Spot-Kurse für Coins (Futures, Optionen, DEX-Pools). */
    private val NOT_SPOT = setOf("Hyperliquid", "Deribit", "DexScreener", "EcbReference")

    /** Standardquelle des Portfolios; dafür braucht es keinen eigenen Weg. */
    const val DEFAULT_EXCHANGE = "Binance"

    /** Gegenwährungen in dieser Reihenfolge; USD über USDT/USD, Fiat über den Devisenkurs ([toUsdt]). */
    val QUOTES = listOf(
        "USDT", "USD", "EUR", "CHF", "GBP", "JPY", "KRW", "TRY", "BRL", "MXN", "INR", "IDR", "AUD", "CAD",
        "SGD", "HKD", "PLN", "SEK", "NOK", "DKK", "CZK", "HUF", "RON", "NZD", "ZAR", "THB",
    )

    /** Spot-Börse der App (keine Futures, keine Optionen, kein DEX)? */
    fun isSpot(exchangeKey: String): Boolean = !exchangeKey.endsWith("Futures") && exchangeKey !in NOT_SPOT

    /**
     * Börse je Coin aus den Transaktionen ([coinAndExchange]: Coin, eingetragene Börse): nur, wenn alle
     * Einträge mit Börse dieselbe nennen (Gross/Klein egal). Leere Einträge zählen nicht.
     * @return Coin (normalisiert) → Börsenname, dazu die Coins mit mehreren Börsen
     */
    fun exchangeByCoin(coinAndExchange: List<Pair<String, String?>>): Pair<Map<String, String>, Set<String>> {
        val names = HashMap<String, MutableSet<String>>()
        val display = HashMap<String, String>()
        for ((rawCoin, exchange) in coinAndExchange) {
            val name = exchange?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val coin = PortfolioCalculator.normalizeCoin(rawCoin)
            names.getOrPut(coin) { HashSet() } += name.lowercase()
            display.putIfAbsent(coin, name)
        }
        val single = HashMap<String, String>()
        val mixed = HashSet<String>()
        for ((coin, set) in names) {
            if (set.size == 1) single[coin] = display.getValue(coin) else mixed += coin
        }
        return single to mixed
    }

    /** Eintrag für [exchangeByLedger]: Börse der Transaktion, bei einer Umbuchung ihr Ziel ([transferTo]). */
    data class RouteEntry(
        val coin: String,
        val exchange: String?,
        val type: PortfolioTxType = PortfolioTxType.BUY,
        val transferTo: String? = null,
        val time: Long = 0L,
        val id: Long = 0L,
    )

    /**
     * Wie [exchangeByCoin], aber mit Umbuchungen: je Coin zählen ab der letzten Umbuchung mit Ziel (Zeit, dann
     * Id) nur deren Ziel und die Börsen der späteren Transaktionen. Ohne solche Umbuchung alle Einträge.
     */
    fun exchangeByLedger(entries: List<RouteEntry>): Pair<Map<String, String>, Set<String>> {
        val pairs = ArrayList<Pair<String, String?>>()
        entries.groupBy { PortfolioCalculator.normalizeCoin(it.coin) }.forEach { (coin, list) ->
            val sorted = list.sortedWith(compareBy<RouteEntry> { it.time }.thenBy { it.id })
            val last = sorted.indexOfLast { it.type == PortfolioTxType.TRANSFER && !it.transferTo.isNullOrBlank() }
            if (last < 0) {
                sorted.forEach { pairs += coin to it.exchange.takeIf { _ -> it.type != PortfolioTxType.TRANSFER } }
            } else {
                pairs += coin to sorted[last].transferTo
                for (i in last + 1 until sorted.size) {
                    val e = sorted[i]
                    if (e.type != PortfolioTxType.TRANSFER) pairs += coin to e.exchange
                }
            }
        }
        return exchangeByCoin(pairs)
    }

    /**
     * Spot-Markt zu einem eingetragenen Namen ([exchanges]: Schlüssel → Anzeigename); Name oder Schlüssel,
     * Gross/Klein egal. Binance und unbekannte Namen (freier Text) → null.
     */
    fun exchange(name: String, exchanges: Map<String, String>): Pair<String, String>? {
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return null
        val hit = exchanges.entries.firstOrNull { (key, label) ->
            isSpot(key) && (label.lowercase() == wanted || key.lowercase() == wanted)
        } ?: return null
        if (hit.key == DEFAULT_EXCHANGE) return null
        return hit.key to hit.value
    }

    /** Ein Paar der Börse: Basis, Gegenwährung, Paar-Kennung der Börse (null = aus Basis/Gegenwährung gebildet). */
    data class Pair3(val base: String, val quote: String, val pairId: String?)

    /** Bestes Paar für [coin] aus [pairs] (nur Spot) nach [QUOTES]; null = keines. */
    fun pickPair(coin: String, pairs: List<Pair3>): Pair3? {
        val base = PortfolioCalculator.normalizeCoin(coin)
        val own = pairs.filter { PortfolioCalculator.normalizeCoin(it.base) == base }
        for (quote in QUOTES) {
            own.firstOrNull { it.quote.uppercase() == quote }?.let { return it }
        }
        return null
    }

    /**
     * Kurs in USDT: USDT unverändert; USD geteilt durch den Marktkurs USDT/USD [usdtUsd] (ungültig oder Standard = 1,
     * USDT = USD); Fiat geteilt durch den Kurs USD → Fiat ([usdTo]) und durch [usdtUsd]; sonst null.
     */
    fun toUsdt(price: Double, quote: String, usdtUsd: Double = 1.0, usdTo: (String) -> Double?): Double? {
        if (!(price > 0.0) || !price.isFinite()) return null
        val usdt = usdtUsd.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        return when (val q = quote.uppercase()) {
            "USDT" -> price
            "USD" -> price / usdt
            else -> {
                if (q !in QUOTES) return null
                val rate = usdTo(q)?.takeIf { it > 0.0 && it.isFinite() } ?: return null
                price / rate / usdt
            }
        }
    }

    /**
     * Binance-Tageskurse eines Coins auf den Kurs der eigenen Börse skaliert: Faktor = Kurs der Börse ÷
     * Binance-Kurs jetzt. Coins ohne eigenen Weg bleiben unverändert. So misst «7 T» die Bewegung des
     * Markts am Kurs, mit dem das Portfolio bewertet ist, statt zwei Börsen zu vermischen.
     */
    fun scaleCloses(
        closes: Map<String, Map<Long, Double>>,
        prices: Map<String, Double>,
        binance: Map<String, Double>,
    ): Map<String, Map<Long, Double>> = scale(closes, prices, binance) { value, factor -> value * factor }

    /**
     * Eröffnung/Hoch/Tief der Tageskerzen mit demselben Faktor wie [scaleCloses] — sonst stimmt im Chart
     * die Linie, aber Dochte und erster Tag zeigen den Abstand zur Binance als Bewegung.
     */
    fun scaleRanges(
        ranges: Map<String, Map<Long, PortfolioDayRange>>,
        prices: Map<String, Double>,
        binance: Map<String, Double>,
    ): Map<String, Map<Long, PortfolioDayRange>> = scale(ranges, prices, binance) { r, f ->
        PortfolioDayRange(open = r.open * f, high = r.high * f, low = r.low * f)
    }

    /**
     * Faktor = eigener Kurs ÷ Binance-Kurs. ZUERST die Identität: Liegt das Verhältnis ausserhalb
     * 1/3 … 3 ([PricePlausibility.sameToken]), führt Binance unter dem Kürzel wohl einen anderen Token.
     * Dann wird die Reihe dieses Coins verworfen (Coin fehlt in 7 T / 1 J / Verlauf, steht unter «ohne …»)
     * statt sie auf den eigenen Kurs zu skalieren — nach dem Skalieren läge der letzte Schluss immer beim
     * eigenen Kurs, und die spätere Prüfung [PricePlausibility.closesMatchLive] liesse den falschen Token durch.
     */
    private fun <T> scale(
        series: Map<String, Map<Long, T>>,
        prices: Map<String, Double>,
        binance: Map<String, Double>,
        apply: (T, Double) -> T,
    ): Map<String, Map<Long, T>> {
        if (binance.isEmpty()) return series
        val out = LinkedHashMap<String, Map<Long, T>>()
        for ((coin, values) in series) {
            val own = prices[coin]
            val ref = binance[coin]
            if (own == null || ref == null || !(own > 0.0) || !(ref > 0.0)) {
                // Kein Bezug zum Skalieren: Reihe unverändert — die strenge Prüfung danach
                // ([PricePlausibility.closesMatchLive]) wirft sie ohne passenden aktuellen Kurs hinaus
                out[coin] = values
                continue
            }
            if (!PricePlausibility.sameToken(own, ref)) continue
            val factor = own / ref
            out[coin] = values.mapValues { apply(it.value, factor) }
        }
        return out
    }

    /**
     * Tagesverlauf aller Coins für Wertverlauf, «7 T»/«1 J», Widget und Alarme ([closes]/[ranges]: Binance,
     * für Coins in [own] schon die Reihe der eigenen Börse):
     *  - ohne eigene Börse ([routed]) oder mit eigener Reihe: unverändert;
     *  - eingetragene Börse ging diesmal nicht ([failed], bewertet mit Binance): Binance unverändert;
     *  - sonst Ausweich: Binance mit [scaleCloses]/[scaleRanges] skaliert — NUR mit eigenem Kurs und
     *    Binance-Bezug jetzt ([prices], [binance]); fehlt einer davon (z. B. nach einem Neustart), fehlt die
     *    Reihe (Coin unter «ohne …»), nie die unskalierte Binance-Reihe.
     */
    fun historyCloses(
        closes: Map<String, Map<Long, Double>>,
        ranges: Map<String, Map<Long, PortfolioDayRange>>,
        own: Set<String>,
        routed: Set<String>,
        failed: Set<String>,
        prices: Map<String, Double>,
        binance: Map<String, Double>,
    ): RoutedHistory {
        val fallback = closes.keys.filterTo(HashSet()) { it in routed && it !in own && it !in failed }
        if (fallback.isEmpty()) return RoutedHistory(closes, ranges, emptySet())
        // Nur Coins mit beiden Kursen jetzt — sonst liesse [scale] die Reihe unskaliert durch
        val withRef = fallback.filterTo(HashSet()) { coin ->
            val p = prices[coin]
            val b = binance[coin]
            p != null && b != null && p > 0.0 && b > 0.0 && p.isFinite() && b.isFinite()
        }
        val refs = binance.filterKeys { it in withRef }
        val scaled = scaleCloses(closes.filterKeys { it in withRef }, prices, refs)
        val scaledRanges = scaleRanges(ranges.filterKeys { it in scaled }, prices, refs)
        val outCloses = LinkedHashMap<String, Map<Long, Double>>()
        closes.forEach { (coin, values) -> if (coin !in fallback) outCloses[coin] = values }
        outCloses.putAll(scaled)
        val outRanges = LinkedHashMap<String, Map<Long, PortfolioDayRange>>()
        ranges.forEach { (coin, values) -> if (coin !in fallback) outRanges[coin] = values }
        outRanges.putAll(scaledRanges)
        return RoutedHistory(outCloses, outRanges, scaled.keys.toSet())
    }

    /**
     * Tageskerzen der eigenen Börse (Gegenwährung [quote]) in USDT: USDT/USD unverändert, Fiat je Tag
     * geteilt durch den Devisenkurs USD → Fiat dieses Tags ([usdToByDay], fehlt er: der letzte davor,
     * [PortfolioHistoryFx.rateOn]); Tage ohne Kurs fallen weg. null = Gegenwährung nicht umrechenbar,
     * keine Devisen-Tageskurse oder nichts übrig. Mit [usdtUsdByDay] (USDT/USD-Tagesschluss): USD- und Fiat-Kerzen
     * zusätzlich geteilt durch USDT/USD DES Tags (ohne Schluss 1, [PortfolioStables.usdtUsd]); USDT-Kerzen unverändert.
     */
    fun ownDailyUsdt(
        daily: PortfolioCoinDaily,
        quote: String,
        usdToByDay: Map<Long, Double>?,
        usdtUsdByDay: Map<Long, Double>? = null,
    ): PortfolioCoinDaily? {
        val q = quote.uppercase()
        if (q !in QUOTES) return null
        val direct = q == "USDT" || q == "USD"
        val sorted = if (direct) emptyList() else {
            usdToByDay.orEmpty().filter { it.value > 0.0 && it.value.isFinite() }.entries
                .sortedBy { it.key }.map { it.key to it.value }
                .takeIf { it.isNotEmpty() } ?: return null
        }
        fun usdt(day: Long): Double = if (q == "USDT" || usdtUsdByDay == null) 1.0 else PortfolioStables.usdtUsd(usdtUsdByDay[day]).rate
        fun rate(day: Long): Double? = (if (direct) 1.0 else PortfolioHistoryFx.rateOn(sorted, day))?.let { it * usdt(day) }
        val closes = HashMap<Long, Double>()
        val ranges = HashMap<Long, PortfolioDayRange>()
        for ((day, close) in daily.closes) {
            if (!(close > 0.0) || !close.isFinite()) continue
            val r = rate(day) ?: continue
            closes[day] = close / r
            daily.ranges[day]?.let { PortfolioDayRange(it.open / r, it.high / r, it.low / r) }
                ?.takeIf { it.valid }?.let { ranges[day] = it }
        }
        return if (closes.isEmpty()) null else PortfolioCoinDaily(closes, ranges)
    }

    /**
     * Eigene Reihe [own], vorn mit [binance] ergänzt, wenn die Börse nicht so weit zurückreicht (Kraken z. B.
     * höchstens 720 Tage): Binance-Tage vor dem ersten eigenen Tag, mit dem Verhältnis eigener ÷ Binance-Schluss
     * AN diesem Tag angeglichen (kein Bezugskurs von jetzt nötig). Ohne Binance-Schluss an dem Tag oder bei
     * anderem Token ([PricePlausibility.sameToken]) bleibt die eigene Reihe allein.
     */
    fun stitch(own: PortfolioCoinDaily, binance: PortfolioCoinDaily?): StitchedDaily {
        val first = own.closes.keys.minOrNull() ?: return StitchedDaily(own, null)
        val anchor = binance?.closes?.get(first)?.takeIf { it > 0.0 && it.isFinite() } ?: return StitchedDaily(own, null)
        val ownFirst = own.closes.getValue(first)
        if (!PricePlausibility.sameToken(ownFirst, anchor)) return StitchedDaily(own, null)
        val earlier = binance.closes.filter { it.key < first && it.value > 0.0 && it.value.isFinite() }
        if (earlier.isEmpty()) return StitchedDaily(own, null)
        val factor = ownFirst / anchor
        val closes = HashMap<Long, Double>(earlier.size + own.closes.size)
        earlier.forEach { (day, v) -> closes[day] = v * factor }
        closes.putAll(own.closes)
        val ranges = HashMap<Long, PortfolioDayRange>()
        binance.ranges.forEach { (day, r) ->
            if (day < first) PortfolioDayRange(r.open * factor, r.high * factor, r.low * factor).takeIf { it.valid }?.let { ranges[day] = it }
        }
        ranges.putAll(own.ranges)
        return StitchedDaily(PortfolioCoinDaily(closes, ranges), first)
    }

    /**
     * Coins für den Hinweis «Binance-Verlauf auf den Börsenkurs skaliert» in einem Zeitraum ab [fromDay]:
     * ganz skaliert ([scaled]) oder mit Binance vor dem ersten eigenen Tag ([binanceBefore]), der nach
     * [fromDay] liegt. Nur [coins] (z. B. gezeichnete bzw. gehaltene Coins), alphabetisch.
     */
    fun scaledHint(scaled: Set<String>, binanceBefore: Map<String, Long>, fromDay: Long, coins: Collection<String>): List<String> =
        coins.map { PortfolioCalculator.normalizeCoin(it) }.distinct()
            .filter { it in scaled || (binanceBefore[it]?.let { first -> first > fromDay } ?: false) }
            .sorted()

    /** «Kraken (BTC, ETH); Bitstamp (SOL)» — Börsen alphabetisch, Coins je Börse alphabetisch. */
    fun sourcesText(sources: Map<String, String>): String =
        sources.entries.groupBy({ it.value }, { it.key })
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .entries.joinToString("; ") { (exchange, coins) -> "$exchange (${coins.sorted().joinToString(", ")})" }

    /**
     * Hängen die Werte der Gesamtkarte am Kurs USDT/USD? Ja, wenn in eine Anzeigewährung umgerechnet
     * wird ([converted]: Devisenkurs USD → Währung auf USDT-Beträge), wenn USD gewählt ist oder wenn
     * Kurse anderer Börsen in USD ([sources]) mit Binance-USDT gemischt werden. Mit USDT zum Marktkurs
     * zählt das nur noch ohne Kurs (Ersatz 1, [com.cryptochecker.app.domain.alarm.DepegAlarm.cardNote]);
     * bis dahin steht «USDT = 1» auf der Karte selbst, nicht nur unter «Hinweise».
     */
    fun usdtParityShown(displayCurrency: String, converted: Boolean, sources: Map<String, String>): Boolean {
        val currency = displayCurrency.trim().uppercase()
        return converted || currency == "USD" || sources.isNotEmpty()
    }
}

/** Tageskerzen eines Coins: Schluss und Eröffnung/Hoch/Tief je UTC-Tag (USDT oder Gegenwährung der Börse). */
data class PortfolioCoinDaily(
    val closes: Map<Long, Double>,
    val ranges: Map<Long, PortfolioDayRange> = emptyMap(),
)

/** Ergebnis von [PortfolioExchangeRoute.stitch]; [binanceBefore] = erster eigener Tag, davor Binance (null = nur eigene). */
data class StitchedDaily(val daily: PortfolioCoinDaily, val binanceBefore: Long?)

/** Ergebnis von [PortfolioExchangeRoute.historyCloses]; [scaled] = Coins mit skaliertem Binance-Verlauf. */
data class RoutedHistory(
    val closes: Map<String, Map<Long, Double>>,
    val ranges: Map<String, Map<Long, PortfolioDayRange>>,
    val scaled: Set<String>,
)
