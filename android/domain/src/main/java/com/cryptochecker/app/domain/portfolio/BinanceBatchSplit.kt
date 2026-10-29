package com.cryptochecker.app.domain.portfolio

/**
 * Binance-Sammelabfrage der Portfolio-Kurse (`ticker/price?symbols=[…]`) robust gegen unbekannte Symbole: Binance lehnt
 * die ganze Anfrage mit HTTP 400 ab, sobald ein Symbol unbekannt ist. Dann wird der Teil halbiert und jede Hälfte neu
 * abgefragt, bis hinunter zu einzelnen Symbolen. Ein einzelnes Symbol mit 400 = Binance führt es nicht: kein Kurs von
 * Binance, aber KEIN Fehler ([Outcome.unlisted]). Andere Fehler (Netz, Zeitgrenze, 403/451, 429) = [Outcome.failed],
 * ohne Halbieren. Zusätzliche Abfragen durch das Halbieren höchstens [EXTRA_REQUESTS]; was danach offen bleibt, geht
 * ohne Fehler an die Kerzen-Kette. So bekommt ein Binance-Coin (z. B. IOTA) seinen Kurs auch bei der ersten
 * Aktualisierung ohne Coin-Liste, wenn andere gehaltene Coins bei Binance fehlen.
 *
 * Reine Regel mit eingesetzter Abfrage; wie `BinanceBatchSplit.swift`, Fixture `binance_batch_split.json`.
 */
object BinanceBatchSplit {
    /** Höchstens so viele Symbole je Abfrage. */
    const val MAX_SYMBOLS = 100

    /** Zusätzliche Abfragen je Sammelabfrage durch das Halbieren (reicht für einige unbekannte Coins). */
    const val EXTRA_REQUESTS = 40

    /** Antwort auf eine Abfrage eines Teils. */
    sealed interface Reply {
        /** Gelesene Kurse (Coin → Kurs); fehlende Coins haben keinen. */
        data class Prices(val prices: Map<String, Double>) : Reply

        /** HTTP 400: mindestens ein Symbol des Teils ist Binance unbekannt. */
        data object Rejected : Reply

        /** Netz, Zeitgrenze, Sperre oder Bremse: kein Beleg, dass es keinen Kurs gibt. */
        data object Failed : Reply
    }

    /**
     * [prices]: Coin → Kurs; [failed]: mindestens eine Abfrage scheiterte (nicht 400); [unlisted]: einzeln mit 400
     * abgelehnt (Binance führt sie nicht); [requests]: Anzahl Abfragen.
     */
    data class Outcome(val prices: Map<String, Double>, val failed: Boolean, val unlisted: Set<String>, val requests: Int)

    /**
     * Kurse für [coins] in Teilen zu höchstens [maxSymbols]; abgelehnte Teile werden halbiert (zuerst die erste Hälfte,
     * Tiefe zuerst — fester Ablauf wie iOS), höchstens [budget] zusätzliche Abfragen.
     */
    suspend fun fetch(
        coins: List<String>,
        maxSymbols: Int = MAX_SYMBOLS,
        budget: Int = EXTRA_REQUESTS,
        request: suspend (List<String>) -> Reply,
    ): Outcome {
        val prices = LinkedHashMap<String, Double>()
        val unlisted = LinkedHashSet<String>()
        var failed = false
        var requests = 0
        var extra = 0
        // Teil und ob er aus dem Halbieren stammt (zählt zum Budget)
        val pending = ArrayDeque(coins.distinct().chunked(maxSymbols.coerceAtLeast(1)).map { it to false })
        while (pending.isNotEmpty()) {
            val (part, fromSplit) = pending.removeFirst()
            if (fromSplit) {
                // Budget aufgebraucht: offen lassen (Kerzen-Kette), nicht als Fehler werten
                if (extra >= budget) continue
                extra++
            }
            requests++
            when (val reply = request(part)) {
                is Reply.Prices -> reply.prices.forEach { (coin, price) -> if (coin in part) prices[coin] = price }
                Reply.Failed -> failed = true
                Reply.Rejected -> if (part.size == 1) {
                    unlisted += part[0]
                } else {
                    val half = (part.size + 1) / 2
                    pending.addFirst(part.subList(half, part.size) to true)
                    pending.addFirst(part.subList(0, half) to true)
                }
            }
        }
        return Outcome(prices, failed, unlisted, requests)
    }
}
