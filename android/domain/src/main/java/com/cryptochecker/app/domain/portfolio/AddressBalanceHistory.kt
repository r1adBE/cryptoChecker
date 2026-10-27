package com.cryptochecker.app.domain.portfolio

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Eine Beobachtung des Guthabens einer Adresse: Zeitpunkt (ms) und Guthaben (exakt, ≥ 0). */
data class AddressObservation(val time: Long, val balance: BigDecimal)

/**
 * Adress-Guthaben zu einem Zeitpunkt ([AddressBalanceHistory.balancesAt]): [balances] je Coin (> 0) und [unobserved] =
 * Coins, deren Reihe erst danach beginnt (dort nicht beobachtet).
 */
data class AddressBalancesAt(val balances: Map<String, BigDecimal>, val unobserved: Set<String>)

/**
 * Beobachtete Guthaben einer Adresse (zeitlich aufsteigend) — Grundlage für Heute / 7 T / 1 J und den
 * Wertverlauf (reines Kotlin, Spiegel: `Shared/Portfolio/AddressBalanceHistory.swift`).
 *
 * Regeln (nie rückwirkend, nie ein Scheingewinn):
 *  - Vor der ersten Beobachtung zählt die Adresse nicht.
 *  - Erste Beobachtung = Zufluss des Guthabens zum Marktkurs dieses Zeitpunkts; jede Änderung zwischen zwei
 *    Beobachtungen = Zu- bzw. Abfluss der Differenz zum Marktkurs der späteren Beobachtung (wie eine Ein-/
 *    Auszahlung zum Marktwert). Ein neu eingegangener Betrag ist also nie Gewinn; gezählt wird nur die
 *    Kursbewegung des beobachteten Guthabens danach.
 *  - Speichern: [append] behält die erste Beobachtung überhaupt und je UTC-Tag die letzte.
 */
data class AddressBalanceHistory(
    val chain: AddressChain,
    val address: String,
    val observations: List<AddressObservation>,
    /** Coin eines weiteren Guthabens (Token, Netz-Guthaben eines anderen EVM-Netzes); null = Guthaben des Netzes. */
    val token: String? = null,
    /** Schlüssel des weiteren Guthabens ([WatchedAddress.tokenBalances]); unterscheidet z. B. USDC und USDC.e. */
    val key: String? = null,
) {
    val coin: String get() = token ?: chain.coin

    /** Gleiche Reihe (eine Adresse zählt je Schlüssel nur einmal). */
    private val series: String get() = key ?: token ?: chain.coin

    companion object {
        private const val DAY_MILLIS = 86_400_000L

        /**
         * Reihe nach einer neuen Beobachtung ([time], [balance]): zeitlich eingeordnet; behalten werden die
         * erste Beobachtung überhaupt und je UTC-Tag die letzte. Negatives Guthaben ändert nichts.
         */
        fun append(observations: List<AddressObservation>, time: Long, balance: BigDecimal): List<AddressObservation> {
            if (balance.signum() < 0) return observations
            val sorted = (observations + AddressObservation(time, balance)).sortedBy { it.time }
            val out = ArrayList<AddressObservation>(sorted.size)
            for (o in sorted) {
                // Gleicher Tag wie die vorige (nicht die erste überhaupt): die spätere ersetzt sie
                if (out.size > 1 && Math.floorDiv(out.last().time, DAY_MILLIS) == Math.floorDiv(o.time, DAY_MILLIS)) {
                    out[out.size - 1] = o
                } else {
                    out += o
                }
            }
            return out
        }

        /**
         * Beobachtung nach einem erfolgreichen Abruf speichern: gleiches Guthaben wie die letzte Beobachtung → Reihe
         * unverändert (keine Information, kein Schreiben); sonst [append].
         */
        fun record(observations: List<AddressObservation>, time: Long, balance: BigDecimal): List<AddressObservation> {
            val last = observations.maxByOrNull { it.time }
            if (last != null && last.balance.compareTo(balance) == 0 && time >= last.time) return observations
            return append(observations, time, balance)
        }

        /**
         * Guthaben-Änderungen als Einträge für Zeiträume und Verlauf: je Beobachtung mit geänderter Summe eine
         * Einzahlung (+) bzw. Auszahlung (−) der Differenz OHNE Preis mit negativer Id
         * ([AddressBalance.isAddressTrade]) — [PortfolioPeriodChange] und [PortfolioHistory] bewerten sie zum
         * Marktkurs ihres Zeitpunkts. Dieselbe Adresse (gleiches Netz, gleicher Coin) zählt nur einmal (die erste Reihe).
         */
        fun trades(histories: List<AddressBalanceHistory>): List<PortfolioTrade> {
            val out = ArrayList<PortfolioTrade>()
            val seen = ArrayList<AddressBalanceHistory>()
            var nextId = -1_000_001L
            for (h in histories) {
                if (seen.any { it.chain == h.chain && it.series == h.series && WatchedAddressRules.sameAddress(h.chain, it.address, h.address) }) continue
                seen += h
                var previous = BigDecimal.ZERO
                for (o in h.observations.sortedBy { it.time }) {
                    if (o.balance.signum() < 0) continue
                    val delta = o.balance - previous
                    previous = o.balance
                    if (delta.signum() == 0) continue
                    out += PortfolioTrade(
                        id = nextId--,
                        coin = h.coin,
                        type = if (delta.signum() > 0) PortfolioTxType.DEPOSIT else PortfolioTxType.WITHDRAWAL,
                        amount = delta.abs(),
                        priceUsdt = null,
                        time = o.time,
                    )
                }
            }
            return out
        }

        /**
         * Reihen aller beobachteten Adressen ([WatchedAddress.observations]). Ohne gespeicherte Beobachtung, aber
         * mit geladenem Guthaben: dieses ab seinem Abruf ([WatchedAddress.lastUpdated]) — nie früher.
         * Dazu je weiterem Guthaben, das zählt ([WatchedAddressRules.counts]), eine Reihe ([WatchedAddress.tokenObservations]);
         * Adressen «Nur anzeigen» fehlen ganz. Einzelne Bitcoin-Adressen, die in einem beobachteten xpub stecken, fehlen (zählen über den
         * xpub, [WatchedAddressRules.coveredByXpub]).
         */
        fun of(addresses: List<WatchedAddress>): List<AddressBalanceHistory> {
            val counted = addresses.filter { it.countInPortfolio }
            val covered = WatchedAddressRules.coveredByXpub(counted)
            val out = ArrayList<AddressBalanceHistory>()
            for (a in counted) {
                if (a.chain == AddressChain.BTC && !a.isXpub && a.address in covered) continue
                val obs = a.observations.ifEmpty {
                    val balance = PortfolioDecimal.parse(a.lastBalance)?.takeIf { it.signum() >= 0 }
                    if (balance != null && a.lastUpdated > 0) listOf(AddressObservation(a.lastUpdated, balance)) else emptyList()
                }
                if (obs.isNotEmpty()) out += AddressBalanceHistory(a.chain, a.address, obs)
                val series = a.tokenObservations.entries.mapNotNull { (key, list) ->
                    val asset = ChainAssets.resolve(a.chain, key) ?: return@mapNotNull null
                    if (list.isEmpty() || !WatchedAddressRules.counts(a, key)) null else asset to list
                }
                for ((asset, list) in series.sortedBy { it.first.order }) {
                    out += AddressBalanceHistory(a.chain, a.address, list, token = asset.coin, key = asset.key)
                }
            }
            return out
        }

        /** Gültige Beobachtungen (Guthaben ≥ 0, Zeit > 0), zeitlich aufsteigend. */
        fun clean(observations: List<AddressObservation>): List<AddressObservation> =
            observations.filter { it.balance.signum() >= 0 && it.time > 0 }.sortedBy { it.time }

        /** Speichern/Sichern: `[{"t":1727136000000,"b":"0.3"}]` (Guthaben exakt als Text). */
        fun toJson(observations: List<AddressObservation>): JSONArray {
            val array = JSONArray()
            for (o in observations) array.put(JSONObject().put("t", o.time).put("b", PortfolioDecimal.text(o.balance)))
            return array
        }

        /** Lesen; unlesbare Einträge fallen weg ([clean]). */
        fun fromJson(array: JSONArray?): List<AddressObservation> {
            if (array == null) return emptyList()
            val out = ArrayList<AddressObservation>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val time = (o.opt("t") as? Number)?.toLong() ?: continue
                val balance = PortfolioDecimal.parse(o.opt("b") as? String) ?: continue
                out += AddressObservation(time, balance)
            }
            return clean(out)
        }

        /** Spalte der Datenbank: Text von [toJson]; leer = null. */
        // Von Hand geschrieben (feste Reihenfolge «t», «b»): org.json ordnet Schlüssel je nach Laufzeit anders – wie iOS
        fun encode(observations: List<AddressObservation>): String? =
            if (observations.isEmpty()) null
            else observations.joinToString(",", "[", "]") { "{\"t\":${it.time},\"b\":\"${PortfolioDecimal.text(it.balance)}\"}" }

        /** Spalte der Datenbank lesen; unlesbar = leer. */
        fun decode(text: String?): List<AddressObservation> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching { fromJson(JSONArray(text)) }.getOrDefault(emptyList())
        }

        /**
         * Beobachtetes Guthaben je Coin als Stufen (Summe der Adressen, dieselbe nur einmal; Zeit, Guthaben ab dann),
         * Grossschreibung — für den 24-h-Verlauf des Widgets ([PortfolioWidgetSeries.hourly]).
         */
        fun stepsByCoin(histories: List<AddressBalanceHistory>): Map<String, List<AddressObservation>> {
            val out = LinkedHashMap<String, MutableList<AddressObservation>>()
            val sums = HashMap<String, BigDecimal>()
            for (t in trades(histories).sortedBy { it.time }) {
                val sum = (sums[t.coin] ?: BigDecimal.ZERO) + if (t.type == PortfolioTxType.DEPOSIT) t.amount else t.amount.negate()
                sums[t.coin] = sum
                val list = out.getOrPut(t.coin) { ArrayList() }
                // Gleiche Zeit (zwei Adressen): eine Stufe
                if (list.isNotEmpty() && list.last().time == t.time) list[list.size - 1] = AddressObservation(t.time, sum)
                else list += AddressObservation(t.time, sum)
            }
            return out
        }

        /** Guthaben der Stufen [steps] zum Zeitpunkt [time]: letzte Stufe ≤ [time], davor 0. */
        fun balanceAt(steps: List<AddressObservation>, time: Long): BigDecimal =
            steps.lastOrNull { it.time <= time }?.balance ?: BigDecimal.ZERO

        /**
         * Guthaben je Coin (Grossschreibung) zum Zeitpunkt [time] für den Stichtag-Export ([CutoffExport.addressRows]):
         * je Reihe die letzte Beobachtung ≤ [time] (Summe der Adressen, dieselbe nur einmal, wie [trades]); nur Guthaben
         * > 0. [AddressBalancesAt.unobserved] = Coins mit einer Reihe, die erst NACH [time] beginnt — an dem Tag nicht
         * beobachtet, ihr Guthaben fehlt (nie rückwirkend).
         */
        fun balancesAt(histories: List<AddressBalanceHistory>, time: Long): AddressBalancesAt {
            val sums = LinkedHashMap<String, BigDecimal>()
            val unobserved = LinkedHashSet<String>()
            val seen = ArrayList<AddressBalanceHistory>()
            for (h in histories) {
                if (seen.any { it.chain == h.chain && it.series == h.series && WatchedAddressRules.sameAddress(h.chain, it.address, h.address) }) continue
                seen += h
                val coin = PortfolioCalculator.normalizeCoin(h.coin)
                val obs = clean(h.observations)
                if (obs.isEmpty()) continue
                val at = obs.lastOrNull { it.time <= time }
                if (at == null) {
                    unobserved += coin
                    continue
                }
                sums[coin] = (sums[coin] ?: BigDecimal.ZERO) + at.balance
            }
            return AddressBalancesAt(sums.filterValues { it.signum() > 0 }, unobserved)
        }

        /** Zuletzt beobachtetes Guthaben je Coin (Summe der Adressen, dieselbe nur einmal), Grossschreibung. */
        fun latestByCoin(histories: List<AddressBalanceHistory>): Map<String, BigDecimal> {
            val out = LinkedHashMap<String, BigDecimal>()
            for (t in trades(histories)) {
                val sign = if (t.type == PortfolioTxType.DEPOSIT) t.amount else t.amount.negate()
                out[t.coin] = (out[t.coin] ?: BigDecimal.ZERO) + sign
            }
            return out.filterValues { it.signum() > 0 }
        }
    }
}
