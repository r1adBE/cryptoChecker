package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.market.CycleInputs
import com.cryptochecker.app.domain.market.OnChainValues
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Holt die Daten für das Zyklus-Modell:
 *  - Tages- und Wochenkerzen BTC/USDT (Kurs, Durchschnitte, Allzeithoch) über die
 *    Ausweich-Kette Binance → Binance.US → Coinbase (siehe [CandleDataSource]);
 *    Coinbase liefert nur rund 300 Tage, das reicht notfalls für das Modell
 *  - Coin Metrics Community API: MVRV, Mining-Einnahmen, Hashrate
 *    (frei, ohne Schlüssel, Lizenz CC BY-NC 4.0 — Quellenangabe in der App)
 *
 * Fehlen die On-Chain-Daten, rechnet das Modell mit den Kursdaten weiter.
 */
class CycleDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
    private val candleDataSource: CandleDataSource,
) {
    /**
     * @param knownOnChain noch frische On-Chain-Werte (Zwischenspeicher des Markt-Tabs,
     *   12 Stunden) — dann entfällt der Abruf bei Coin Metrics.
     */
    suspend fun fetch(knownOnChain: OnChainValues? = null): CycleInputs = coroutineScope {
        val dailyJob = async { candleDataSource.candles("BTC", "USDT", CandleInterval.D1, 1000) }
        val weeklyJob = async { candleDataSource.candles("BTC", "USDT", CandleInterval.W1, 1000) }
        val onChainJob = async {
            if (knownOnChain != null) return@async null
            runCatching { httpClient.callMarket(coinMetricsUrl(), null) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .onFailure { Timber.w(it, "Coin-Metrics-Daten nicht verfügbar") }
                .getOrNull()
        }

        val daily = toCandles(dailyJob.await() ?: error("Keine BTC-Tageskerzen verfügbar"))
        // Wochenkerzen nur für 200-Wochen-Schnitt und Allzeithoch; fehlen sie, entfällt das
        val weekly = weeklyJob.await()?.let { toCandles(it) } ?: emptyList()
        val onChain = knownOnChain ?: onChainJob.await()?.let { runCatching { parseCoinMetrics(it) }.getOrNull() }

        require(daily.size >= 200) { "Zu wenige Tageskerzen: ${daily.size}" }
        val closes = daily.map { it.close }

        // Allzeithoch: Wochenkerzen reichen bis 2017 zurück, Tageskerzen geben das genaue Datum.
        val athCandle = (weekly + daily).maxByOrNull { it.high }

        CycleInputs(
            price = closes.last(),
            sma200d = closes.smaOfLast(200),
            sma111d = closes.smaOfLast(111),
            sma350d = closes.smaOfLast(350),
            price30dAgo = closes.getOrNull(closes.size - 31),
            sma200w = weekly.map { it.close }.smaOfLast(200),
            ath = athCandle?.high,
            athDate = athCandle?.date,
            mvrv = onChain?.mvrv,
            puell = onChain?.puell,
            hash30d = onChain?.hash30d,
            hash60d = onChain?.hash60d,
        )
    }

    private class Candle(val date: LocalDate, val high: Double, val close: Double)

    private fun toCandles(candles: List<HourCandle>): List<Candle> = candles.map {
        Candle(
            date = Instant.ofEpochMilli(it.openTime).atZone(ZoneOffset.UTC).toLocalDate(),
            high = it.high,
            close = it.close,
        )
    }

    private fun parseCoinMetrics(json: String): OnChainValues {
        val rows = JSONObject(json).getJSONArray("data")
        val mvrv = mutableListOf<Double>()
        val issuance = mutableListOf<Double>()
        val hash = mutableListOf<Double>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            r.optString("CapMVRVCur").toDoubleOrNull()?.let { mvrv += it }
            r.optString("IssTotUSD").toDoubleOrNull()?.let { issuance += it }
            r.optString("HashRate").toDoubleOrNull()?.let { hash += it }
        }

        // Puell Multiple: Wert der täglich neu geschürften Coins / 365-Tage-Schnitt.
        val puell = if (issuance.size >= 365) {
            val avg = issuance.takeLast(365).average()
            if (avg > 0) issuance.last() / avg else null
        } else null

        return OnChainValues(
            mvrv = mvrv.lastOrNull(),
            puell = puell,
            hash30d = hash.smaOfLast(30),
            hash60d = hash.smaOfLast(60),
        )
    }

    private fun List<Double>.smaOfLast(n: Int): Double? =
        if (size >= n) takeLast(n).average() else null

    private companion object {
        /** Rund 14 Monate Tageswerte — genug für den 365-Tage-Schnitt des Puell Multiple. */
        fun coinMetricsUrl(): String {
            val start = LocalDate.now(ZoneOffset.UTC).minusDays(430)
            return "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics" +
                "?assets=btc&metrics=CapMVRVCur,IssTotUSD,HashRate&frequency=1d" +
                "&start_time=$start&page_size=1000"
        }
    }
}
