package com.cryptochecker.app.domain.activity

import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.macro.MiniJson
import com.cryptochecker.app.domain.spot.SpotMarket

/**
 * Breiter Markt für «Warum?»: Median der 24-h-Veränderung der Top-Coins.
 *
 * @param change24h Median in %
 * @param coins so viele Coins flossen ein
 * @param time Stand der Daten (Median von «last_updated», Epoch-ms)
 */
data class BroadMarketSnapshot(val change24h: Double, val coins: Int, val time: Long)

/** Ein Coin der Rangliste mit 24-h-Veränderung (CoinGecko `/coins/markets`). */
data class BroadCoin(val symbol: String, val change24h: Double?, val lastUpdated: Long?)

/**
 * Zweite Markt-Referenz neben BTC/ETH (reines Kotlin, Swift-Spiegel `BroadMarket`): die Rangliste
 * der Top-40-Coins von CoinGecko (dieselbe Abfrage wie der Spot-Tab, [SpotMarket.TOP_URL], für
 * alle Nutzer gleich) — keine Anfrage, die einen Coin verrät.
 *
 * Gezählt werden nur «Altcoins»: ohne Stablecoins, ohne BTC und ETH (die eigene Referenzen sind),
 * ohne deren verpackte/gestakte Abbilder und ohne Gold-Token — und ohne den betrachteten Coin
 * selbst. Der Median ist robust gegen einzelne Ausreisser. Zu wenige Coins oder ein zu alter
 * Stand: kein breiter Markt.
 */
object BroadMarket {

    /** Mindestens so viele Coins, sonst kein Median. */
    const val MIN_COINS = 10

    /** Höchstens so alt (Median von «last_updated») — CoinGecko aktualisiert laufend. */
    const val MAX_AGE_MILLIS = 3 * ActivityAnalyzer.HOUR_MILLIS

    /** Anbieter der Rangliste (für die Quellenangabe). */
    const val PROVIDER = "CoinGecko"

    /** Kein eigener Markt: Abbilder von BTC/ETH (verpackt, gestakt) und Gold-Token. */
    val EXCLUDED: Set<String> = setOf(
        "BTC", "ETH", "WBTC", "CBBTC", "LBTC", "BTCB", "TBTC", "SOLVBTC",
        "WETH", "STETH", "WSTETH", "WEETH", "RETH", "CBETH", "METH", "EZETH", "RSETH", "BETH", "WBETH",
        "PAXG", "XAUT",
    )

    /** Antwort von `/coins/markets` lesen; Einträge ohne Kürzel fallen weg. */
    fun parse(text: String?): List<BroadCoin> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try {
            MiniJson.parse(text) as? List<*>
        } catch (e: IllegalArgumentException) {
            null
        } ?: return emptyList()
        return root.mapNotNull { item ->
            val o = item as? Map<*, *> ?: return@mapNotNull null
            val symbol = (o["symbol"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            BroadCoin(
                symbol = symbol,
                change24h = (o["price_change_percentage_24h"] as? Number)?.toDouble()?.takeIf { it.isFinite() },
                lastUpdated = SpotMarket.isoMillis(o["last_updated"] as? String),
            )
        }
    }

    /** Zählt der Coin zum breiten Markt (ohne [excludeBase])? */
    fun counts(symbol: String, excludeBase: String?): Boolean {
        val s = symbol.trim().uppercase()
        if (s.isEmpty() || s in EXCLUDED || s in CurrencyConversion.USD_STABLES || s in EXTRA_STABLES) return false
        return excludeBase == null || s != excludeBase.trim().uppercase()
    }

    /**
     * Median der 24-h-Veränderung der zählenden Coins; null bei weniger als [MIN_COINS] Werten,
     * ohne Zeitstempel oder wenn der Stand älter als [MAX_AGE_MILLIS] ist (bzw. über 5 Min. in der Zukunft).
     */
    fun snapshot(coins: List<BroadCoin>, excludeBase: String?, now: Long): BroadMarketSnapshot? {
        val used = coins.filter { counts(it.symbol, excludeBase) && it.change24h != null }
            .distinctBy { it.symbol }
        if (used.size < MIN_COINS) return null
        val times = used.mapNotNull { it.lastUpdated }
        if (times.size < MIN_COINS) return null
        val time = median(times.map { it.toDouble() }).toLong()
        if (now - time > MAX_AGE_MILLIS || time - now > FUTURE_TOLERANCE_MILLIS) return null
        val change = median(used.mapNotNull { it.change24h })
        return BroadMarketSnapshot(change, used.size, time)
    }

    /** Median (bei gerader Anzahl Mittel der beiden mittleren). */
    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        if (n == 0) return Double.NaN
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Weitere Stablecoins der Rangliste, die nicht in der Umrechnungsliste stehen. */
    private val EXTRA_STABLES = setOf("USDS", "USDTB", "BUIDL", "USDX", "SUSDE", "SUSDS", "EURC", "USDG", "USDF")

    private const val FUTURE_TOLERANCE_MILLIS = 5 * 60_000L
}
