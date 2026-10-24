package com.cryptochecker.app.performance

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.marketdata.model.MarketPair
import java.util.Locale
import kotlin.math.pow

/** Deterministischer Zufall (SplitMix64): gleiche Folge auf jeder JVM und wie `PerformanceRandom` (iOS). */
internal class SplitMix64(private var state: Long) {

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Gleichverteilt in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / TWO_POW_53

    /** Gleichverteilt in 0 until [bound]. */
    fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()

    private companion object {
        // 0x9E3779B97F4A7C15, 0xBF58476D1CE4E5B9, 0x94D049BB133111EB als vorzeichenbehaftete Long
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        const val MIX_1 = -0x40a7b892e31b1a47L
        const val MIX_2 = -0x6b2fb644ecceee15L
        const val TWO_POW_53 = 9_007_199_254_740_992.0
    }
}

/**
 * Testdaten der Leistungstests: bis zu 2000 Paare (Obergrenze der Merkliste) aus 100 echten Coins × 20 Quote-Währungen,
 * je Börse eindeutig, mit festem Seed. Paar `i` hat immer dieselbe Basis/Quote ([base], [quote]); in der gemischten
 * Merkliste steht es an der Börse `i % 5` ([MARKETS]). Gleich aufgebaut wie `PerformanceData` (iOS).
 */
internal object PerformanceData {

    const val SEED = 20_261_024L

    /** Zeitpunkt des gespeicherten Kurses der Testpaare. */
    const val UPDATED_AT = 1_792_100_000_000L

    val BASES = listOf(
        "BTC", "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "TON", "AVAX",
        "SHIB", "DOT", "LINK", "BCH", "NEAR", "POL", "LTC", "ICP", "UNI", "APT",
        "ETC", "XLM", "SUI", "FIL", "HBAR", "ATOM", "ARB", "OP", "IMX", "VET",
        "INJ", "RENDER", "GRT", "STX", "MKR", "AAVE", "ALGO", "THETA", "FTM", "RUNE",
        "EGLD", "SAND", "MANA", "AXS", "FLOW", "XTZ", "CHZ", "EOS", "NEO", "KAVA",
        "IOTA", "ZEC", "DASH", "CRV", "SNX", "LDO", "CAKE", "GALA", "ENJ", "1INCH",
        "COMP", "ZIL", "BAT", "QNT", "KSM", "WAVES", "CELO", "ROSE", "MINA", "GMX",
        "DYDX", "PEPE", "FLOKI", "BONK", "WIF", "JUP", "PYTH", "TIA", "SEI", "WLD",
        "ORDI", "ENA", "ETHFI", "JTO", "STRK", "ZRO", "NOT", "BOME", "PENDLE", "ONDO",
        "FET", "AR", "KAS", "OM", "TAO", "JASMY", "HOT", "ANKR", "CFX", "IOTX",
    )

    val QUOTES = listOf(
        "USDT", "USDC", "FDUSD", "TUSD", "EUR", "TRY", "BRL", "JPY", "AUD", "GBP",
        "PLN", "RON", "ZAR", "UAH", "ARS", "MXN", "IDR", "COP", "CZK", "CHF",
    )

    /** Ungefährer Wert einer Einheit der Quote-Währung in CHF (für die Kurs-Spalte, wie die Umrechnung der App). */
    private val CHF_PER_QUOTE = mapOf(
        "USDT" to 0.80, "USDC" to 0.80, "FDUSD" to 0.80, "TUSD" to 0.80, "EUR" to 0.93, "TRY" to 0.024,
        "BRL" to 0.15, "JPY" to 0.0053, "AUD" to 0.52, "GBP" to 1.07, "PLN" to 0.22, "RON" to 0.19,
        "ZAR" to 0.045, "UAH" to 0.019, "ARS" to 0.0008, "MXN" to 0.043, "IDR" to 0.00005, "COP" to 0.0002,
        "CZK" to 0.037, "CHF" to 1.0,
    )

    /** Börsen der gemischten Merkliste: Schlüssel und Anzeigename. */
    val MARKETS = listOf(
        "Binance" to "Binance", "Kraken" to "Kraken", "Bybit" to "Bybit", "Okex" to "OKX", "Coinbase" to "Coinbase",
    )

    val BINANCE = MARKETS[0]

    /** Gruppen (null = keine; doppelt, damit etwa ein Viertel ohne Gruppe bleibt). */
    val GROUPS = listOf(null, null, "Layer 1", "DeFi", "Memes", "Gaming", AutoGroup.TRADFI, AutoGroup.DATED)

    /** Gespeicherte Coin-Namen für die Suche («bitcoin» findet BTC). */
    val COIN_NAMES = mapOf(
        "BTC" to "Bitcoin", "ETH" to "Ethereum", "SOL" to "Solana", "XRP" to "XRP", "ADA" to "Cardano",
        "DOGE" to "Dogecoin", "DOT" to "Polkadot", "LINK" to "Chainlink", "PEPE" to "Pepe", "AVAX" to "Avalanche",
    )

    fun base(index: Int): String = BASES[index % BASES.size]

    fun quote(index: Int): String = QUOTES[(index / BASES.size) % QUOTES.size]

    /** Kennung wie bei Binance, z. B. «BTCUSDT». */
    fun symbol(index: Int): String = base(index) + quote(index)

    fun chfPerQuote(quote: String): Double = CHF_PER_QUOTE[quote] ?: 1.0

    /** Die ersten [count] Paare als Binance-Paarliste (Auswahl der Sammelaufnahme). */
    fun binancePairs(count: Int): List<MarketPair> = List(count) { MarketPair(base(it), quote(it), symbol(it)) }

    /**
     * Merkliste mit [count] Paaren (höchstens 2000). [market] null = gemischt über [MARKETS]; sonst alle an dieser
     * Börse. Etwa 5 % Favoriten, 5 % ohne 24-h-Wert, 2 % deaktiviert, 10 % mit Notiz.
     */
    fun watches(count: Int, market: Pair<String, String>? = null): List<WatchEntity> {
        require(count <= BASES.size * QUOTES.size) { "höchstens ${BASES.size * QUOTES.size} Paare" }
        val rng = SplitMix64(SEED)
        return List(count) { i ->
            val (key, name) = market ?: MARKETS[i % MARKETS.size]
            val price = 10.0.pow(rng.nextDouble() * 8.0 - 4.0)
            val previous = price * (0.99 + rng.nextDouble() * 0.02)
            val change = (rng.nextDouble() - 0.5) * 16.0
            val noChange = rng.nextInt(20) == 0
            val favorite = rng.nextInt(20) == 0
            val group = GROUPS[rng.nextInt(GROUPS.size)]
            val note = if (rng.nextInt(10) == 0) String.format(Locale.ROOT, "Einstieg bei %.4f", previous) else null
            val disabled = rng.nextInt(50) == 0
            WatchEntity(
                id = i + 1L, marketKey = key, marketName = name, baseAsset = base(i), quoteAsset = quote(i),
                pairId = symbol(i), sortOrder = i, notificationEnabled = false,
                lastPrice = price, previousPrice = previous, lastUpdate = UPDATED_AT,
                favorite = favorite, groupName = group, note = note,
                change24h = if (noChange) null else change, disabled = disabled,
            )
        }
    }

    /** Neuer Kurs je Kennung für eine Aktualisierung: gespeicherter Kurs ±3 %. */
    fun newPrices(watches: List<WatchEntity>): Map<String, Double> {
        val rng = SplitMix64(SEED + 2)
        val out = LinkedHashMap<String, Double>(watches.size * 2)
        for (w in watches) {
            val price = w.lastPrice ?: continue
            val pairId = w.pairId ?: continue
            out[pairId] = price * (0.97 + rng.nextDouble() * 0.06)
        }
        return out
    }

    /**
     * Alarme: je Paar 1–3 ([perPair] null) bzw. genau [perPair] — Kursmarke über/unter (Alarmwährung [currency]) und
     * Prozent seit Bezug (jeder dritte ohne Bezug, bekommt also einen). Etwa jedes hundertste Paar hat eine schon
     * überschrittene, scharfe Kursmarke (löst aus).
     */
    fun alarms(watches: List<WatchEntity>, currency: String? = null, perPair: Int? = null): List<AlarmEntity> {
        val rng = SplitMix64(SEED + 1)
        val out = ArrayList<AlarmEntity>(watches.size * 3)
        var id = 0L
        for ((i, w) in watches.withIndex()) {
            val price = w.lastPrice ?: continue
            val count = perPair ?: (1 + rng.nextInt(3))
            for (k in 0 until count) {
                val u = rng.nextDouble()
                id++
                out += when ((i + k) % 4) {
                    0 -> AlarmEntity(
                        id = id, watchId = w.id, condition = AlarmCondition.PRICE_ABOVE,
                        threshold = if (i % 25 == 0) price * 0.5 else price * (1.1 + u), currency = currency,
                    )
                    1 -> AlarmEntity(
                        id = id, watchId = w.id, condition = AlarmCondition.PRICE_BELOW,
                        threshold = price * (0.9 - 0.5 * u), currency = currency,
                    )
                    2 -> AlarmEntity(
                        id = id, watchId = w.id, condition = AlarmCondition.CHANGE_PERCENT_UP,
                        threshold = 5.0 + 10.0 * u, referencePrice = if (i % 3 == 0) null else price,
                    )
                    else -> AlarmEntity(
                        id = id, watchId = w.id, condition = AlarmCondition.CHANGE_PERCENT_DOWN,
                        threshold = 5.0 + 10.0 * u, referencePrice = if (i % 3 == 0) null else price,
                    )
                }
            }
        }
        return out
    }

    /**
     * Ungefilterte Sammelantwort von Binance (`GET /api/v3/ticker/24hr`) mit einer Zeile je Eintrag von [prices]
     * (Kennung → Kurs) — Felder und Zahlenformat wie die Aufzeichnung
     * `testdata/exchanges/recorded/recorded_binance.json`.
     */
    fun binanceBulkBody(prices: Map<String, Double>): String = buildString(prices.size * 700) {
        append('[')
        var index = 0
        for ((symbol, last) in prices) {
            if (index > 0) append(',')
            appendBinanceRow(symbol, last, index)
            index++
        }
        append(']')
    }

    private fun StringBuilder.appendBinanceRow(symbol: String, last: Double, index: Int) {
        val open = last * 1.0073
        val openTime = 1_792_106_885_009L + index
        append("{\"symbol\":\"").append(symbol).append('"')
        append(",\"priceChange\":\"").append(decimal(last - open)).append('"')
        append(",\"priceChangePercent\":\"").append(String.format(Locale.ROOT, "%.3f", (last / open - 1.0) * 100.0))
        append("\",\"weightedAvgPrice\":\"").append(decimal(last * 1.0033)).append('"')
        append(",\"prevClosePrice\":\"").append(decimal(open)).append('"')
        append(",\"lastPrice\":\"").append(decimal(last)).append('"')
        append(",\"lastQty\":\"0.00009000\"")
        append(",\"bidPrice\":\"").append(decimal(last * 0.9999)).append('"')
        append(",\"bidQty\":\"3.21350000\"")
        append(",\"askPrice\":\"").append(decimal(last * 1.0001)).append('"')
        append(",\"askQty\":\"2.64886000\"")
        append(",\"openPrice\":\"").append(decimal(open)).append('"')
        append(",\"highPrice\":\"").append(decimal(last * 1.0159)).append('"')
        append(",\"lowPrice\":\"").append(decimal(last * 0.9898)).append('"')
        append(",\"volume\":\"14757.89016000\",\"quoteVolume\":\"1208844990.00894550\"")
        append(",\"openTime\":").append(openTime).append(",\"closeTime\":").append(openTime + 86_400_000L)
        append(",\"firstId\":6766948266,\"lastId\":6770117321,\"count\":3169056}")
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.8f", value)
}
