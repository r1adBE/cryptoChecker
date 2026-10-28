package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.watch.DayChange
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha

/**
 * Paar für Kerzen von der EIGENEN Börse: Schlüssel und Name der Börse ([WatchEntity.exchangeKey],
 * [WatchEntity.exchangeName]), Kürzel, Kennung der Börse ([WatchEntity.pairId], z. B. Kraken «XXBTZUSD»,
 * OKX «BTC-USDT-SWAP», Binance COIN-M «2:BTCUSD_PERP») und Vertragsart.
 * [tradFi] = Aktie, Rohstoff, Devise: nie Kerzen eines gleichnamigen Krypto-Tokens. Wie `CandlePair` (iOS).
 */
data class CandlePair(
    val exchangeKey: String,
    val exchangeName: String,
    val base: String,
    val quote: String,
    val pairId: String? = null,
    val contractType: FuturesContractType = FuturesContractType.NONE,
    val tradFi: Boolean = false,
) {
    val isFutures: Boolean get() = contractType != FuturesContractType.NONE

    /** Laufzeit-Kontrakt (Woche … Quartal) mit Verfallstag: nie Kerzen des Perpetuals oder des Spots. */
    val isDated: Boolean get() = FuturesContractType.getDeliveryDate(contractType) != null

    /** Alpha-Paar von Binance (Kennung «ALPHA_<id>USDT», [BinanceAlpha]). */
    val isBinanceAlpha: Boolean get() = BinanceAlpha.isAlphaPair(exchangeKey, pairId)

    /** DexScreener-Pool: keine Börsen-Kerzen (fremde Kerzen gehörten nicht zum gemerkten Pool). */
    val isDex: Boolean get() = exchangeKey.equals(DEX_EXCHANGE_KEY, ignoreCase = true)

    /** Schlüssel für Zwischenspeicher: Börse, Kennung bzw. Kürzel, Vertragsart. */
    val cacheKey: String
        get() = "$exchangeKey|${pairId ?: (base.trim().uppercase() + "/" + quote.trim().uppercase())}|${contractType.name}"

    companion object {
        const val DEX_EXCHANGE_KEY = "DexScreener"

        fun of(watch: WatchEntity, tradFi: Boolean): CandlePair = CandlePair(
            exchangeKey = watch.exchangeKey,
            exchangeName = watch.exchangeName,
            base = watch.baseAsset,
            quote = watch.quoteAsset,
            pairId = watch.pairId,
            contractType = watch.contractType,
            tradFi = tradFi,
        )
    }
}

/**
 * Anfragen für eine Kerzenquelle: GET, POST (JSON, z. B. Hyperliquid) — je null bei Fehler, Sperre oder
 * Pause — und die Uhrzeit [now] für Zeitfenster in der Adresse (Tests setzen sie fest).
 */
fun interface CandleHttp {
    suspend fun get(url: String): String?

    suspend fun post(url: String, json: String): String? = null

    val now: Long get() = System.currentTimeMillis()
}

/**
 * Kerzen einer Börse über ihren öffentlichen Endpunkt ohne Schlüssel. Erweiterung: neue Quelle als
 * `object` in ExchangeCandleSourcesMajor.kt schreiben und in [ExchangeCandleRegistry.sources] eintragen
 * (Gegenstück in ExchangeCandleSourcesMajor.swift), dazu ein Fall in testdata/parity/own_candles.json.
 */
interface ExchangeCandleSource {
    /** Börsen-Schlüssel ([com.cryptochecker.marketdata.model.Exchange.key]), für die die Quelle gilt. */
    val exchangeKeys: Set<String>

    /** Kann die Quelle dieses Paar liefern (Vertragsart, Kennung)? */
    fun supports(pair: CandlePair): Boolean

    /**
     * Bis zu [limit] Kerzen ([CandleInterval.H1], [CandleInterval.H4] oder [CandleInterval.D1]),
     * zeitlich aufsteigend, Startzeit in Epoch-ms (UTC); null = keine. Anfragen nur über [http].
     */
    suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>?

    /** Antwort mit HTTP 200, die trotzdem «zu viele Anfragen» meldet (Kraken, Bybit, OKX). */
    fun isRateLimited(body: String): Boolean = false
}

/** Alle Quellen für eigene Kerzen; Auswahl je Paar. Wie `ExchangeCandleRegistry` (iOS). */
object ExchangeCandleRegistry {

    val sources: List<ExchangeCandleSource> = listOf(
        BinanceAlphaCandles,
        BinanceCandles,
        BinanceUsCandles,
        BinanceFuturesCandles,
        CoinbaseCandles,
        KrakenCandles,
        BitstampCandles,
        BitvavoCandles,
        BybitCandles,
        OkxCandles,
        // Zweite Runde (ExchangeCandleSourcesGlobal.kt)
        BitfinexCandles,
        BitgetCandles,
        GateIoCandles,
        KucoinCandles,
        MexcCandles,
        MexcFuturesCandles,
        HtxCandles,
        GeminiCandles,
        CryptoComCandles,
        UpbitCandles,
        WooCandles,
        PoloniexCandles,
        DeribitCandles,
        HyperliquidCandles,
        OneTradingCandles,
    )

    /** Intervalle mit eigenen Kerzen (alle Zeiträume von Aktionsblatt, Mini-Chart und Widget). */
    val INTERVALS: Set<CandleInterval> = setOf(CandleInterval.H1, CandleInterval.H4, CandleInterval.D1)

    fun sourceFor(pair: CandlePair): ExchangeCandleSource? =
        sources.firstOrNull { pair.exchangeKey in it.exchangeKeys && it.supports(pair) }

    fun supports(pair: CandlePair, interval: CandleInterval): Boolean =
        interval in INTERVALS && sourceFor(pair) != null
}

/**
 * Herkunft der Kerzen eines Paars: zuerst die eigene Börse ([ExchangeCandleRegistry]); sonst die
 * Binance-Kette mit Hinweis («Chart: Binance») und Prüfung auf gleichen Token. TradFi-Paare und
 * DEX-Pools nie mit fremden Kerzen (gleiches Kürzel ≠ gleicher Wert), Laufzeit-Kontrakte nie mit
 * denen des Perpetuals. Wie `OwnCandlePolicy` (iOS).
 */
object OwnCandlePolicy {
    /**
     * Darf die Ausweich-Kette (Binance … Coinbase) einspringen? Nicht für Alpha-Paare von Binance: Ein
     * gleiches Kürzel an Binance Spot oder Coinbase wäre ein anderer Token (Alpha = dort nicht gelistet).
     */
    fun allowsFallback(pair: CandlePair): Boolean =
        !pair.tradFi && !pair.isDex && !pair.isDated && !pair.isBinanceAlpha

    /**
     * Gleicher Token? Reihe der Ausweich-Kette gilt nur, wenn ihr letzter Schluss höchstens
     * [DayChange.MAX_PRICE_GAP] (25 %) vom Kurs des Paars [price] abweicht — wie der Chart
     * ([com.cryptochecker.app.domain.watch.SheetChart.accept]). Ohne Kurs nicht prüfbar → false.
     */
    fun acceptsFallback(candles: List<HourCandle>?, price: Double?): Boolean {
        val p = price?.takeIf { it.isFinite() && it > 0.0 } ?: return false
        val last = candles?.lastOrNull { it.close.isFinite() && it.close > 0.0 }?.close ?: return false
        return kotlin.math.abs(p / last - 1.0) <= DayChange.MAX_PRICE_GAP
    }

    /**
     * Anbieter eigener Kerzen = Name der Börse des Paars — der Hinweis in der Zeile entfällt damit; bei
     * Alpha-Paaren von Binance «Binance Alpha» ([BinanceAlpha.PROVIDER]).
     */
    fun provider(pair: CandlePair): String = if (pair.isBinanceAlpha) BinanceAlpha.PROVIDER else pair.exchangeName
}
