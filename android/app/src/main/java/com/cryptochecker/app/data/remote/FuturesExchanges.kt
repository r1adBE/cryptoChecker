package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.activity.FuturesSourceNote
import org.json.JSONArray
import org.json.JSONObject

/**
 * Welche Börse ihre Perpetuals mit eigenen Funding-/Open-Interest-Daten versorgt und wie die
 * Herkunft der Kennzahlen zum beobachteten Paar steht. Reines Kotlin (Spiegel: `FuturesSources`
 * in Shared/Insights/FuturesDataSource.swift).
 */
object FuturesSources {
    const val BINANCE = "Binance Futures"
    const val BYBIT = "Bybit"
    const val OKX = "OKX"
    const val WOO = "WOO X Futures"
    const val BITGET = "Bitget Futures"
    const val MEXC = "MEXC Futures"
    const val HTX = "HTX Futures"
    const val HYPERLIQUID = "Hyperliquid"
    const val DERIBIT = "Deribit"
    /** Binance COIN-M (Paare «2:BTCUSD_PERP», `dapi.binance.com`) — eigener Kontrakt, nicht der USDT-Kontrakt. */
    const val BINANCE_COIN_M = "Binance COIN-M"

    /** Marktschlüssel → Name der eigenen Quelle (= [FuturesInfo.source], wenn die Börse selbst antwortet). */
    private val OWN = mapOf(
        "BinanceFutures" to BINANCE,
        "BybitFutures" to BYBIT,
        "OkexFutures" to OKX,
        "WooFutures" to WOO,
        "BitgetFutures" to BITGET,
        "MexcFutures" to MEXC,
        "HtxFutures" to HTX,
        "Hyperliquid" to HYPERLIQUID,
        "Deribit" to DERIBIT,
    )

    /** Börsen, die auch als Ausweichquelle für andere Paare dienen (eingespielte Kette Binance → Bybit → OKX). */
    private val CHAIN = setOf(BINANCE, BYBIT, OKX)

    /** Eigene Quelle der Börse oder null, wenn sie keine eigenen Kennzahlen liefert (dann nur Richtwert). */
    fun own(marketKey: String): String? = OWN[marketKey]

    /**
     * Eigene Quelle für dieses Paar: bei Binance COIN-M ([isCoinM]) der COIN-M-Kontrakt, sonst [own]. So gelten
     * USDⓈ-M-Daten (BTCUSDT) an einem COIN-M-Paar als Richtwert, nicht als eigene Daten.
     */
    fun own(marketKey: String, pairId: String?): String? =
        if (marketKey == "BinanceFutures" && isCoinM(pairId)) BINANCE_COIN_M else own(marketKey)

    /** Binance-COIN-M-Paar (Präfix «2:» wie im Adapter `BinanceFutures`). */
    fun isCoinM(pairId: String?): Boolean = pairId?.startsWith(COIN_M_PREFIX) == true

    const val COIN_M_PREFIX = "2:"

    /** Börsen mit eigenen Funding-/Open-Interest-Daten (für die Alarme FUNDING_* / OI_*). */
    val MARKETS: Set<String> get() = OWN.keys

    /** Quellen, deren Intervall je Kontrakt verschieden ist (1, 2, 4 oder 8 Std.) und in der Antwort steht. */
    private val PER_CONTRACT = setOf(BINANCE, BYBIT, OKX, BITGET, MEXC)

    /**
     * Abrechnungsintervall der Quelle in Stunden, wenn die Antwort keines nennt
     * ([FuturesInfo.fundingIntervalHours]): Hyperliquid stündlich, Deribit «funding_8h», WOO X, HTX und
     * Binance COIN-M 8. Für Binance, Bybit, OKX, Bitget und MEXC gibt es keinen Standard — das Intervall ist
     * je Kontrakt verschieden; fehlt es in der Antwort, ist es **unbekannt** (null), nie still 8.
     */
    fun fundingIntervalHours(source: String?): Int? = when {
        source == HYPERLIQUID -> 1
        source != null && source in PER_CONTRACT -> null
        else -> 8
    }

    /**
     * Wie [source] zum Paar steht: null = Daten der beobachteten Börse; [FuturesSourceNote.FALLBACK] =
     * eigene Börse (Binance/Bybit/OKX) nicht erreichbar, eine andere sprang ein;
     * [FuturesSourceNote.REFERENCE] = Spot-Paar, Börse ohne eigene Daten oder eigene Quelle ausgefallen —
     * Kennzahlen einer anderen Börse als Richtwert.
     */
    fun note(perpetual: Boolean, marketKey: String, source: String, pairId: String? = null): FuturesSourceNote? {
        if (!perpetual) return FuturesSourceNote.REFERENCE
        val own = own(marketKey, pairId) ?: return FuturesSourceNote.REFERENCE
        if (source == own) return null
        return if (own in CHAIN) FuturesSourceNote.FALLBACK else FuturesSourceNote.REFERENCE
    }
}

/**
 * Antworten der Börsen-APIs → [FuturesInfo]. Reine Funktionen über die Antworttexte, damit die
 * Vertragstests (testdata/exchanges/futures_derivatives.json, auch iOS) sie ohne Netz prüfen.
 * Funding in Prozent (Antwort × 100), Open Interest in USD bzw. Quote-Währung. Liefert eine
 * Antwort weder Funding noch Open Interest, wirft der Parser — die Kette nimmt dann die nächste Quelle.
 */
object FuturesParsers {

    /**
     * WOO X `GET /v3/public/futures?symbol=PERP_BTC_USDT`: «estFundingRate» (geschätzte Rate der
     * nächsten Abrechnung, sonst «lastFundingRate»), «nextFundingTime», «openInterest» in Coins × «markPrice».
     */
    fun woo(body: String): FuturesInfo {
        val row = JSONObject(body).getJSONObject("data").getJSONArray("rows").getJSONObject(0)
        val funding = num(row, "estFundingRate") ?: num(row, "lastFundingRate")
        val coins = num(row, "openInterest")
        val mark = num(row, "markPrice")
        return info(funding, time(row, "nextFundingTime"), product(coins, mark), FuturesSources.WOO)
    }

    /**
     * Bitget `GET /api/v2/mix/market/ticker?symbol=…&productType=USDT-FUTURES`: «fundingRate»,
     * «holdingAmount» (Open Interest in Coins) × «markPrice». [fundingTime] = optional
     * `GET /api/v2/mix/market/funding-time` («nextFundingTime»).
     */
    fun bitget(ticker: String, fundingTime: String?): FuturesInfo {
        val t = JSONObject(ticker).getJSONArray("data").getJSONObject(0)
        val mark = num(t, "markPrice") ?: num(t, "lastPr")
        val next = fundingTime?.let { text ->
            runCatching { time(JSONObject(text).getJSONArray("data").getJSONObject(0), "nextFundingTime") }.getOrNull()
        }
        val period = fundingTime?.let { text ->
            runCatching { num(JSONObject(text).getJSONArray("data").getJSONObject(0), "ratePeriod") }.getOrNull()
        }
        return info(num(t, "fundingRate"), next, product(num(t, "holdingAmount"), mark), FuturesSources.BITGET, hours(period))
    }

    /**
     * MEXC `GET /api/v1/contract/ticker?symbol=BTC_USDT`: «fundingRate», «holdVol» (Kontrakte) ×
     * «contractSize» aus `GET /api/v1/contract/detail?symbol=…` ([detail]) × «fairPrice». Ohne
     * Kontraktgrösse kein Open Interest. [fundingRate] = optional `GET /api/v1/contract/funding_rate/{symbol}`
     * («nextSettleTime»).
     */
    fun mexc(ticker: String, detail: String?, fundingRate: String?, symbol: String): FuturesInfo {
        val t = JSONObject(ticker).getJSONObject("data")
        val size = detail?.let { text -> runCatching { mexcContractSize(text, symbol) }.getOrNull() }
        val price = num(t, "fairPrice") ?: num(t, "lastPrice")
        val contracts = num(t, "holdVol")
        val oi = if (contracts != null && size != null) product(contracts * size, price) else null
        val f = fundingRate?.let { text -> runCatching { JSONObject(text).getJSONObject("data") }.getOrNull() }
        val funding = num(t, "fundingRate") ?: f?.let { num(it, "fundingRate") }
        return info(funding, f?.let { time(it, "nextSettleTime") }, oi, FuturesSources.MEXC, hours(f?.let { num(it, "collectCycle") }))
    }

    /** «contractSize» aus der Kontraktbeschreibung (Objekt bei Abfrage mit Symbol, sonst Liste). */
    private fun mexcContractSize(text: String, symbol: String): Double? {
        val root = JSONObject(text)
        val item = root.optJSONObject("data") ?: findBy(root.getJSONArray("data"), "symbol", symbol)
        return item?.let { num(it, "contractSize") }?.takeIf { it > 0.0 }
    }

    /**
     * HTX `GET /linear-swap-api/v1/swap_funding_rate?contract_code=BTC-USDT` («funding_rate») und
     * `GET /linear-swap-api/v1/swap_open_interest?contract_code=…&business_type=swap` («value» = Open
     * Interest in USDT). Zeitpunkt der nächsten Abrechnung bleibt leer.
     */
    fun htx(funding: String, openInterest: String?): FuturesInfo {
        val root = JSONObject(funding)
        require(root.optString("status") == "ok") { "HTX-Fehler: ${root.optString("err_msg")}" }
        val rate = num(root.getJSONObject("data"), "funding_rate")
        val value = openInterest?.let { text ->
            runCatching {
                val oiRoot = JSONObject(text)
                if (oiRoot.optString("status") == "ok") num(oiRoot.getJSONArray("data").getJSONObject(0), "value") else null
            }.getOrNull()
        }
        return info(rate, null, value, FuturesSources.HTX)
    }

    /**
     * Hyperliquid `POST /info {"type":"metaAndAssetCtxs"}`: Eintrag [coin] in «universe», gleicher Index
     * in der Kontextliste: «funding» (stündliche Rate, wie die Börse sie zeigt), «openInterest» in Coins × «markPx».
     */
    fun hyperliquid(body: String, coin: String): FuturesInfo {
        val root = JSONArray(body)
        val universe = root.getJSONObject(0).getJSONArray("universe")
        val contexts = root.getJSONArray(1)
        for (i in 0 until minOf(universe.length(), contexts.length())) {
            if (universe.getJSONObject(i).optString("name") != coin) continue
            val ctx = contexts.getJSONObject(i)
            return info(num(ctx, "funding"), null, product(num(ctx, "openInterest"), num(ctx, "markPx")), FuturesSources.HYPERLIQUID)
        }
        throw IllegalArgumentException("Hyperliquid ohne $coin")
    }

    /**
     * Deribit `GET /api/v2/public/ticker?instrument_name=…`: «funding_8h»; «open_interest» bei inversen
     * Perpetuals (BTC-PERPETUAL) in USD, bei linearen USDC-Perpetuals (SOL_USDC-PERPETUAL) in Coins × «mark_price».
     */
    fun deribit(body: String): FuturesInfo {
        val r = JSONObject(body).getJSONObject("result")
        val oi = num(r, "open_interest")
        val linear = r.optString("instrument_name").contains('_')
        val usd = if (linear) product(oi, num(r, "mark_price")) else oi
        return info(num(r, "funding_8h"), null, usd, FuturesSources.DERIBIT)
    }

    private fun info(rate: Double?, next: Long?, oiUsd: Double?, source: String, intervalHours: Int? = null): FuturesInfo {
        val funding = rate?.times(100.0)
        val oi = oiUsd?.takeIf { it > 0.0 }
        require(funding != null || oi != null) { "$source ohne Futures-Daten" }
        return FuturesInfo(
            fundingRatePercent = funding, nextFundingTime = next, openInterestUsd = oi, source = source,
            fundingIntervalHours = intervalHours,
        )
    }

    /** Abrechnungsintervall in ganzen Stunden (1 … 24); alles andere = unbekannt. */
    fun hours(value: Double?): Int? {
        val v = value?.takeIf { it.isFinite() } ?: return null
        val rounded = kotlin.math.round(v)
        if (kotlin.math.abs(v - rounded) > 0.01) return null
        return rounded.toInt().takeIf { it in 1..24 }
    }

    /**
     * Binance `GET /fapi/v1/fundingInfo`: Liste der Kontrakte mit angepasstem Intervall
     * («symbol», «fundingIntervalHours»). Symbol → Stunden.
     */
    fun binanceFundingInfo(body: String): Map<String, Int> {
        val array = JSONArray(body)
        val map = HashMap<String, Int>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol").takeIf { it.isNotEmpty() } ?: continue
            hours(num(item, "fundingIntervalHours"))?.let { map[symbol] = it }
        }
        return map
    }

    /** Intervall eines Binance-Kontrakts: aus [fundingInfo], nicht gelistet = 8 Stunden (Standard). */
    fun binanceIntervalHours(fundingInfo: Map<String, Int>, symbol: String): Int = fundingInfo[symbol] ?: 8

    /**
     * Binance COIN-M `GET /dapi/v1/premiumIndex?symbol=BTCUSD_PERP` (Liste, auch mit Symbol) und optional
     * `GET /dapi/v1/openInterest?symbol=…` («openInterest» in Kontrakten). Ein Kontrakt ist ein fester
     * USD-Betrag ([coinMContractUsd], aus [contractSizes] der `exchangeInfo`): Open Interest in USD =
     * Kontrakte × Kontraktwert. Intervall 8 Std.
     */
    fun binanceCoinM(
        premium: String,
        openInterest: String?,
        symbol: String,
        contractSizes: Map<String, Double>? = null,
    ): FuturesInfo {
        val array = JSONArray(premium)
        val row = findBy(array, "symbol", symbol) ?: array.optJSONObject(0)?.takeIf { array.length() == 1 }
            ?: throw IllegalArgumentException("COIN-M ohne $symbol")
        val contracts = openInterest?.let { runCatching { num(JSONObject(it), "openInterest") }.getOrNull() }
        return info(
            num(row, "lastFundingRate"), time(row, "nextFundingTime"),
            product(contracts, coinMContractUsd(symbol, contractSizes)), FuturesSources.BINANCE_COIN_M,
        )
    }

    /**
     * Binance COIN-M `GET /dapi/v1/exchangeInfo`: Kontraktwert in USD («contractSize») je Symbol
     * (Stand der Doku: BTCUSD 100, alle anderen 10). Einträge ohne gültige Grösse fehlen.
     */
    fun binanceCoinMContractSizes(body: String): Map<String, Double> {
        val symbols = JSONObject(body).optJSONArray("symbols") ?: return emptyMap()
        val map = HashMap<String, Double>()
        for (i in 0 until symbols.length()) {
            val item = symbols.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol").takeIf { it.isNotEmpty() } ?: continue
            num(item, "contractSize")?.takeIf { it > 0.0 }?.let { map[symbol] = it }
        }
        return map
    }

    /**
     * Kontraktwert eines COIN-M-Kontrakts in USD: «contractSize» aus [contractSizes] (exchangeInfo), sonst
     * nach der Binance-Kontraktspezifikation BTC 100, alle anderen 10.
     */
    fun coinMContractUsd(symbol: String, contractSizes: Map<String, Double>? = null): Double =
        contractSizes?.get(symbol)?.takeIf { it.isFinite() && it > 0.0 }
            ?: if (symbol.startsWith("BTCUSD")) 100.0 else 10.0

    /** OKX: Abstand zwischen nächster und übernächster Abrechnung in Stunden; fehlt eine Zeit = unbekannt. */
    fun okxIntervalHours(fundingTime: Long?, nextFundingTime: Long?): Int? {
        if (fundingTime == null || nextFundingTime == null || fundingTime <= 0 || nextFundingTime <= fundingTime) return null
        return hours((nextFundingTime - fundingTime) / 3_600_000.0)
    }

    private fun product(a: Double?, b: Double?): Double? = if (a != null && b != null) a * b else null

    private fun findBy(array: JSONArray, key: String, value: String): JSONObject? {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString(key) == value) return item
        }
        return null
    }

    /** Zahl als JSON-Zahl oder Text; null, NaN und ±∞ = kein Wert. */
    private fun num(json: JSONObject, key: String): Double? {
        val value = json.opt(key)
        val d = when (value) {
            null -> null
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        return d?.takeIf { it.isFinite() }
    }

    /** Zeitpunkt in Epoch-ms (Zahl oder Text); 0 oder negativ = keiner. */
    private fun time(json: JSONObject, key: String): Long? = num(json, key)?.toLong()?.takeIf { it > 0 }
}
