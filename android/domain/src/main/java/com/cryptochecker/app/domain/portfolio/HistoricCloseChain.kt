package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.watch.TokenIdentity

/**
 * Ausweich-Kette für historische TAGESSCHLÜSSE (Stichtag, Steuer-Zeilen, Vorschlag beim Erfassen, Wertverlauf):
 * nur Börsen, mit denen die App ohnehin spricht — keine neuen Hosts. Reihenfolge: eigene Börse des Coins (falls
 * eingetragen) → Binance (Spot, Futures, Binance.US) → Coinbase → Kraken → OKX → Bybit. Jede Quelle zählt nur mit
 * bestandener Identitätsprüfung ([decide]: Name + [PricePlausibility.acceptSourceClose]); ein gleiches Kürzel eines
 * anderen Tokens wird nie übernommen. Stablecoin-Regeln bleiben bei [PortfolioStables]. Reines Kotlin, gespiegelt in
 * `HistoricCloseChain.swift`; gemeinsame Testfälle `testdata/parity/historic_close_chain.json`.
 */
object HistoricCloseChain {
    const val BINANCE = "Binance"
    const val COINBASE = "Coinbase"
    const val KRAKEN = "Kraken"
    const val OKX = "Okex"
    const val BYBIT = "Bybit"

    /** Kette ohne eigene Börse (Börsen-Schlüssel wie [com.cryptochecker.marketdata.model.Exchange.key]). */
    val CHAIN = listOf(BINANCE, COINBASE, KRAKEN, OKX, BYBIT)

    /**
     * Ausweich für den Wertverlauf, wenn Binance den Coin nicht führt oder nicht erreichbar ist: dieselbe Kette ohne
     * Binance (dessen Reihe hat die Quelle schon versucht).
     */
    val SERIES_CHAIN = listOf(COINBASE, KRAKEN, OKX, BYBIT)

    /**
     * Reihenfolge der Quellen: eigene Börse [ownExchangeKey] zuerst (auch eine Börse ausserhalb der Kette, z. B.
     * Bitstamp), danach die übrigen Glieder von [CHAIN] ohne Doppel. Gross/Klein egal; leer = nur [CHAIN].
     */
    fun order(ownExchangeKey: String?): List<String> {
        val own = ownExchangeKey?.trim()?.takeIf { it.isNotEmpty() } ?: return CHAIN
        val canonical = CHAIN.firstOrNull { it.equals(own, ignoreCase = true) } ?: own
        return listOf(canonical) + CHAIN.filter { it != canonical }
    }

    /** Anzeigename der Quelle («Kurs: Kraken»); OKX heisst in der App «OKX», der Schlüssel «Okex». */
    fun provider(exchangeKey: String): String = if (exchangeKey == OKX) "OKX" else exchangeKey

    /** Ein abzufragendes Paar einer Kettenquelle: Gegenwährung und Kennung der Börse. */
    data class ChainPair(val quote: String, val pairId: String)

    /**
     * Paare einer Kettenquelle für [base], in dieser Reihenfolge versucht; leer = Quelle nicht in der Kette oder
     * Kürzel unbrauchbar. Gegenwährung USDT bzw. USD/USDC (≈ USDT, wie bei Binance.US und Coinbase).
     * Kraken führt BTC als «XBT» und DOGE als «XDG».
     */
    fun pairs(exchangeKey: String, base: String): List<ChainPair> {
        val b = base.trim().uppercase().takeIf { s -> s.isNotEmpty() && s.length <= 15 && s.all { it.isLetterOrDigit() && it.code < 128 } }
            ?: return emptyList()
        return when (exchangeKey) {
            COINBASE -> listOf(ChainPair("USD", "$b-USD"), ChainPair("USDC", "$b-USDC"))
            KRAKEN -> {
                val k = KRAKEN_ASSETS[b] ?: b
                listOf(ChainPair("USD", "${k}USD"), ChainPair("USDT", "${k}USDT"))
            }
            OKX -> listOf(ChainPair("USDT", "$b-USDT"), ChainPair("USDC", "$b-USDC"))
            BYBIT -> listOf(ChainPair("USDT", "${b}USDT"), ChainPair("USDC", "${b}USDC"))
            else -> emptyList()
        }
    }

    /** Kürzel, die Kraken anders führt. */
    private val KRAKEN_ASSETS = mapOf("BTC" to "XBT", "DOGE" to "XDG")

    /**
     * Name passt? Eigene Börse ([own]): ihr Name ([providerName]) darf dem eigenen ([ownName], Merkliste) nicht klar
     * widersprechen. Kettenquelle: wie die Ausweich-Reihen ([TokenIdentity.fallbackSeriesAllowed]) — widerspricht der
     * eigene Name dem Binance-Namen, fällt die ganze Kette aus. Unbekannte Namen: ja (Kursband entscheidet).
     */
    fun nameAllowed(base: String, own: Boolean, exchangeKey: String, ownName: String?, binanceName: String?, providerName: String?): Boolean =
        if (own) {
            TokenIdentity.compatible(base, ownName, providerName)
        } else {
            TokenIdentity.fallbackSeriesAllowed(base, ownName, provider(exchangeKey), binanceName, providerName)
        }

    /** Urteil über den Schluss einer Quelle: übernommen, verworfen (anderer Token/ungeprüft) oder keiner da. */
    enum class Verdict { ACCEPT, REJECT, MISSING }

    /**
     * Schluss [close] einer Quelle übernehmen? Ohne gültigen Schluss [Verdict.MISSING] (nächste Quelle, kein Hinweis);
     * Name widerspricht ([nameOk] false) oder Identitätsprüfung nicht bestanden ([PricePlausibility.acceptSourceClose]:
     * jüngster Schluss derselben Quelle [latest] gegen den aktuellen Kurs [current]; [primary] = 1/3…3, sonst ±25 %)
     * → [Verdict.REJECT].
     */
    fun decide(close: Double?, latest: Double?, current: Double?, primary: Boolean, nameOk: Boolean): Verdict {
        if (close == null || !(close > 0.0) || !close.isFinite()) return Verdict.MISSING
        if (!nameOk) return Verdict.REJECT
        return if (PricePlausibility.acceptSourceClose(close, primary, latest, current)) Verdict.ACCEPT else Verdict.REJECT
    }

    /**
     * Ergebnis einer Quelle: übernommener Schluss oder keiner; [rejected] = es gab einen, der die Prüfung nicht bestand.
     * [provider] = genauerer Anbieter des Treffers (z. B. «Binance.US» in der Binance-Gruppe); null = [provider] der Quelle.
     */
    data class Outcome(val price: Double?, val rejected: Boolean = false, val provider: String? = null, val failed: Boolean = false)

    /**
     * Art eines gescheiterten Abrufs ([httpCode] = HTTP-Status, null = Netz, Zeitgrenze oder unlesbare Antwort):
     * [FetchFailure.NO_DATA] (4xx: Paar unbekannt) und [FetchFailure.BLOCKED] (403/451: Geo-Sperre) → nächste Quelle wie
     * «keine Kerze»; [FetchFailure.TRANSIENT] (Netz, 5xx, 408, 429/418) → einmal wiederholen, sonst bricht die Kette für
     * diesen Tag ab ([Outcome.failed]): eine andere Quelle hätte einen anderen Kurs, der Export wäre nicht wiederholbar.
     */
    enum class FetchFailure { NO_DATA, BLOCKED, TRANSIENT }

    fun fetchFailure(httpCode: Int?): FetchFailure = when {
        httpCode == null -> FetchFailure.TRANSIENT
        httpCode == 403 || httpCode == 451 -> FetchFailure.BLOCKED
        httpCode == 408 || httpCode == 418 || httpCode == 429 || httpCode >= 500 -> FetchFailure.TRANSIENT
        httpCode in 400..499 -> FetchFailure.NO_DATA
        else -> FetchFailure.TRANSIENT
    }

    /**
     * Ergebnis der Kette: Schluss und Quelle ([source] = Börsen-Schlüssel, [provider] = Anzeigename) oder keiner;
     * [unchecked] = nur verworfene Schlüsse.
     */
    data class Resolved(
        val price: Double?,
        val source: String?,
        val unchecked: Boolean,
        val provider: String? = null,
        /** Eine Quelle war nicht erreichbar ([Outcome.failed]): kein Kurs, die Kette brach dort ab (später erneut). */
        val failed: Boolean = false,
    )

    /**
     * Quellen in [order] der Reihe nach fragen ([outcome], z. B. Abfrage), bis eine einen Schluss liefert. Ohne
     * Treffer: [Resolved.unchecked], wenn irgendeine Quelle einen Schluss verworfen hat. Eine nicht erreichbare Quelle
     * ([Outcome.failed]) beendet die Kette ohne Kurs ([Resolved.failed]) — nie die nächste Quelle an ihrer Stelle.
     */
    inline fun resolve(order: List<String>, outcome: (String) -> Outcome): Resolved {
        var rejected = false
        for (source in order) {
            val r = outcome(source)
            val price = r.price
            if (price != null && price > 0.0 && price.isFinite()) {
                return Resolved(price, source, unchecked = false, provider = r.provider ?: provider(source))
            }
            rejected = rejected || r.rejected
            if (r.failed) return Resolved(null, null, unchecked = rejected, failed = true)
        }
        return Resolved(null, null, unchecked = rejected)
    }

    /**
     * Ausweich-Reihe für den Wertverlauf übernehmen? Jüngster Schluss [latest] der Reihe gegen den aktuellen Kurs
     * [current] mit ±25 % (Ausweich-Quelle, [PricePlausibility.acceptSourceClose]) und passender Name ([nameOk]).
     * Ohne aktuellen Kurs nicht (gleicher Token nicht bestätigt).
     */
    fun acceptSeries(latest: Double?, current: Double?, nameOk: Boolean): Boolean =
        nameOk && PricePlausibility.acceptSourceClose(latest, primary = false, sourceLatest = latest, current = current)

    /**
     * USDT/USD-Tagesschlüsse: [primary] (Kraken) gilt; Tage ohne Schluss dort füllt [secondary] (z. B. Coinbase).
     * Plausibilität nur für die Wahl der Quelle ([PortfolioStables.isUsdtNormal], 0.5…1.5): ein Wert von [secondary]
     * ausserhalb füllt nichts; liegt [primary] ausserhalb, aber [secondary] im Normalbereich, gilt [secondary] (zwei
     * Quellen uneins → der normale Wert). Ein Einbruch, den nur eine Quelle hat, bleibt stehen (Umrechnung dann mit
     * [PortfolioStables.usdtUsd], Einbruch-Bereich 0.05…3). Erst danach gilt «USDT = 1 angenommen».
     */
    fun fillUsdtUsd(primary: Map<Long, Double>, secondary: Map<Long, Double>): Map<Long, Double> {
        if (secondary.isEmpty()) return primary
        val out = HashMap<Long, Double>(primary.size + secondary.size)
        secondary.forEach { (day, v) -> if (PortfolioStables.isUsdtNormal(v)) out[day] = v }
        primary.forEach { (day, v) -> if (PortfolioStables.isUsdtNormal(v) || day !in out) out[day] = v }
        return out
    }
}
