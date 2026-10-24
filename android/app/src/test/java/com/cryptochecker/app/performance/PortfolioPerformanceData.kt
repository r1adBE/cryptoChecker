package com.cryptochecker.app.performance

import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import java.math.BigDecimal

/**
 * Testdaten der Leistungstests fürs Portfolio: [trades] erzeugt bis zu 10 000 Transaktionen mit festem Seed über
 * sieben Jahre (2019–2025) — 12 Coins, 4 Wallets, etwa 40 % Käufe (Gebühr in USDT, im Coin oder in BNB), 20 %
 * Verkäufe, 8 % Tausch, 10 % Belohnungen (jede zehnte ohne Wert), 10 % Umbuchungen (Netzwerkgebühr bzw. Gebühr USDT),
 * 4 % Einzahlungen (teils mit früherem Kaufdatum, teils ohne Preis), 4 % Auszahlungen und je 2 % «Damit bezahlt» und
 * «Verschenkt». Abgänge nehmen 5–50 % des Bestands der Wallet (begrenzt auf einen Wert in USDT); ohne Bestand wird
 * daraus ein Kauf. Gerechnet wird ganzzahlig (Mengen in 10⁻⁸, Kurse in 10⁻⁶ USDT), darum dieselben Daten wie
 * `PortfolioPerformanceData` (iOS). Kurse, Tagesschlüsse und Devisenkurse kommen aus festen Tabellen — kein Netz.
 */
internal object PortfolioPerformanceData {

    val COINS = listOf("BTC", "ETH", "SOL", "ADA", "XRP", "DOT", "LINK", "AVAX", "DOGE", "LTC", "ATOM", "BNB")

    /** Grundkurs je Coin in 10⁻⁶ USDT. */
    private val BASE_MICRO = longArrayOf(
        30_000_000_000, 2_000_000_000, 60_000_000, 500_000, 550_000, 7_000_000,
        12_000_000, 25_000_000, 90_000, 80_000_000, 9_000_000, 300_000_000,
    )

    /** Kursniveau je Jahr 2019…2025 in Prozent des Grundkurses. */
    private val YEAR_PERCENT = longArrayOf(30, 60, 160, 90, 110, 180, 220)

    val WALLETS = listOf("Binance", "Kraken", "Coinbase", "Ledger")

    private const val BNB = 11

    /** 2019-01-01 und 2026-01-01, 00:00 UTC. */
    const val START_MS = 1_546_300_800_000L
    const val END_MS = 1_767_225_600_000L
    private const val YEAR_MS = 31_557_600_000L
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000
    private const val UNITS = 100_000_000L
    private const val USDT = 1_000_000L

    /** Kleinster Bestand (10⁻⁸) für einen Abgang. */
    private const val MIN_HELD = 10_000L

    /** Letzter Tag der Devisen- und USDT-Tabellen (2026-12-31, Tage seit 1970). */
    private const val LAST_FX_DAY = 20_818L

    private fun yearIndex(time: Long): Int = ((time - START_MS) / YEAR_MS).coerceIn(0L, YEAR_PERCENT.size - 1L).toInt()

    /** Tagesschluss des Coins [coin] (Index) in 10⁻⁶ USDT: Grundkurs × Niveau des Jahres. */
    private fun closeMicro(coin: Int, time: Long): Long = BASE_MICRO[coin] * YEAR_PERCENT[yearIndex(time)] / 100

    private fun units(valueMicro: Long, priceMicro: Long): Long = valueMicro * UNITS / priceMicro

    private fun valueMicro(units: Long, priceMicro: Long): Long = units / 100 * priceMicro / USDT

    private fun amount(units: Long): BigDecimal = BigDecimal.valueOf(units, 8).stripTrailingZeros()

    private fun money(micro: Long): BigDecimal = BigDecimal.valueOf(micro, 6).stripTrailingZeros()

    /** Tagesschluss wie `HistoricPriceSource` (Gebühr-Zeilen, Belohnungen ohne Wert, Netzwerkgebühr); fremd = null. */
    fun priceAt(coin: String, time: Long): BigDecimal? {
        val i = COINS.indexOf(coin)
        return if (i < 0) null else money(closeMicro(i, time))
    }

    /** Wie [priceAt] als Zahl (Kurs zum Zeitpunkt einer Auszahlung in der Übersicht). */
    fun priceAtDouble(coin: String, time: Long): Double? = priceAt(coin, time)?.toDouble()

    /** Aktueller Kurs je Coin (Niveau 2025). */
    fun prices(): Map<String, Double> =
        COINS.withIndex().associate { (i, c) -> c to closeMicro(i, END_MS - 1).toDouble() / USDT }

    /** USD → [currency] je Tag (Tage seit 1970) vom Herbst 2018 bis Ende 2026; USD leer (wie die App). */
    fun rates(currency: String): Map<Long, BigDecimal> {
        val base = when (currency) {
            "USD" -> return emptyMap()
            "CHF" -> 900_000L
            "GBP" -> 790_000L
            else -> 920_000L
        }
        val out = LinkedHashMap<Long, BigDecimal>()
        for (day in START_MS / DAY_MS - 40..LAST_FX_DAY) out[day] = money(base + (day * 7_919) % 40_001 - 20_000)
        return out
    }

    /** USDT/USD-Tagesschluss je Tag (0.999…1.001). */
    fun usdtUsd(): Map<Long, BigDecimal> {
        val out = LinkedHashMap<Long, BigDecimal>()
        for (day in START_MS / DAY_MS - 40..LAST_FX_DAY) out[day] = money(USDT + (day * 31) % 2_001 - 1_000)
        return out
    }

    /** [count] Transaktionen (Id 1…count) zeitlich aufsteigend über 2019–2025; gleicher Seed, gleiche Folge. */
    fun trades(count: Int): List<PortfolioTrade> {
        val rng = SplitMix64(PerformanceData.SEED + 10)
        val held = Array(COINS.size) { LongArray(WALLETS.size) }
        val out = ArrayList<PortfolioTrade>(count)
        for (i in 0 until count) {
            val roll = rng.nextInt(100)
            val c = rng.nextInt(COINS.size)
            val w = rng.nextInt(WALLETS.size)
            val value = (20L + rng.nextInt(1_981)) * USDT
            val jitter = 85L + rng.nextInt(31)
            val pct = 5L + rng.nextInt(46)
            val variant = rng.nextInt(10)
            val time = START_MS + (END_MS - START_MS) * i / count + rng.nextInt(HOUR_MS)
            val draw = Draw(i + 1L, c, w, value, closeMicro(c, time) * jitter / 100, pct, variant, time)
            out += draw.trade(roll, held)
        }
        return out
    }

    /** Zufallswerte einer Transaktion; [price] in 10⁻⁶ USDT. */
    private class Draw(
        val id: Long,
        val c: Int,
        val w: Int,
        val value: Long,
        val price: Long,
        val pct: Long,
        val variant: Int,
        val time: Long,
    ) {
        val coin: String get() = COINS[c]
        val wallet: String get() = WALLETS[w]

        /** Abgang: 5–50 % des Bestands, höchstens [maxValue] USDT wert; 0 = kein Abgang möglich. */
        fun outgoing(held: Long, maxValue: Long): Long =
            if (held < MIN_HELD) 0L else minOf(held * pct / 100, units(maxValue, price))

        fun trade(roll: Int, held: Array<LongArray>): PortfolioTrade {
            val h = held[c][w]
            return when {
                roll < 40 -> buy(held)
                roll < 60 -> outgoing(h, value * 2).takeIf { it > 0 }?.let { sell(it, held) } ?: buy(held)
                roll < 68 -> swap(held) ?: buy(held)
                roll < 78 -> reward(held)
                roll < 88 -> outgoing(h, value * 5).takeIf { it > 0 }?.let { transfer(it, held) } ?: buy(held)
                roll < 92 -> deposit(held)
                roll < 96 -> outgoing(h, value).takeIf { it > 0 }?.let { withdraw(it, held) } ?: buy(held)
                else -> outgoing(h, value / 4).takeIf { it > 0 }?.let { spend(it, roll < 98, held) } ?: buy(held)
            }
        }

        private fun base(type: PortfolioTxType, units: Long, priced: Boolean) = PortfolioTrade(
            id = id, coin = coin, type = type, amount = amount(units), priceUsdt = if (priced) money(price) else null,
            time = time, exchange = wallet,
        )

        private fun buy(held: Array<LongArray>): PortfolioTrade {
            val units = units(value, price)
            var t = base(PortfolioTxType.BUY, units, priced = true)
            var coinFee = 0L
            when (variant) {
                in 0..3 -> t = t.copy(fee = money(value / 1_000), feeUnit = PortfolioFeeUnit.QUOTE)
                4 -> {
                    coinFee = units / 1_000
                    t = t.copy(fee = amount(coinFee), feeUnit = PortfolioFeeUnit.COIN)
                }
                5 -> {
                    val bnbPrice = closeMicro(BNB, time)
                    val fee = units(value / 1_000, bnbPrice)
                    if (c != BNB && fee > 0 && held[BNB][w] >= fee) {
                        held[BNB][w] -= fee
                        t = t.copy(feeCoin = COINS[BNB], feeCoinAmount = amount(fee), feeCoinPrice = money(bnbPrice))
                    }
                }
            }
            held[c][w] += units - coinFee
            return t
        }

        private fun sell(units: Long, held: Array<LongArray>): PortfolioTrade {
            held[c][w] -= units
            val t = base(PortfolioTxType.SELL, units, priced = true)
            if (variant >= 5) return t
            return t.copy(fee = money(valueMicro(units, price) / 1_000), feeUnit = PortfolioFeeUnit.QUOTE)
        }

        private fun swap(held: Array<LongArray>): PortfolioTrade? {
            val units = outgoing(held[c][w], value * 2)
            if (units <= 0) return null
            val to = (c + 1 + variant) % COINS.size
            val swapValue = valueMicro(units, price)
            val toUnits = units(swapValue, closeMicro(to, time))
            if (swapValue <= 0 || toUnits <= 0) return null
            held[c][w] -= units
            held[to][w] += toUnits
            return base(PortfolioTxType.SWAP, units, priced = false)
                .copy(toCoin = COINS[to], toAmount = amount(toUnits), swapValueUsdt = money(swapValue))
        }

        private fun reward(held: Array<LongArray>): PortfolioTrade {
            val units = units(value / 20, price)
            held[c][w] += units
            return base(PortfolioTxType.REWARD, units, priced = variant != 9)
        }

        private fun transfer(units: Long, held: Array<LongArray>): PortfolioTrade {
            val to = (w + 1 + variant % 3) % WALLETS.size
            val coinFee = if (variant < 6) units / 2_000 else 0L
            held[c][w] -= units + coinFee
            held[c][to] += units
            val t = PortfolioTrade(
                id = id, coin = coin, type = PortfolioTxType.TRANSFER, amount = amount(units), priceUsdt = null,
                time = time, transferFrom = wallet, transferTo = WALLETS[to],
            )
            return when {
                coinFee > 0 -> t.copy(fee = amount(coinFee), feeUnit = PortfolioFeeUnit.COIN)
                variant < 8 -> t.copy(fee = money(USDT), feeUnit = PortfolioFeeUnit.QUOTE)
                else -> t
            }
        }

        private fun deposit(held: Array<LongArray>): PortfolioTrade {
            val units = units(value, price)
            held[c][w] += units
            val t = base(PortfolioTxType.DEPOSIT, units, priced = variant < 7)
            return if (variant % 2 == 0) t.copy(acquiredTime = time - (30 + pct * 7) * DAY_MS) else t
        }

        private fun withdraw(units: Long, held: Array<LongArray>): PortfolioTrade {
            held[c][w] -= units
            val t = base(PortfolioTxType.WITHDRAWAL, units, priced = true)
            return if (variant < 5) t.copy(fee = money(USDT), feeUnit = PortfolioFeeUnit.QUOTE) else t
        }

        private fun spend(units: Long, payment: Boolean, held: Array<LongArray>): PortfolioTrade {
            held[c][w] -= units
            return if (payment) base(PortfolioTxType.PAYMENT, units, priced = true)
            else base(PortfolioTxType.GIFT, units, priced = true).copy(giftToSpouse = variant == 0)
        }
    }
}
