package com.cryptochecker.marketdata.model.market

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.currency.VirtualCurrency
import com.cryptochecker.marketdata.model.market.generic.SimpleMarket
import com.cryptochecker.marketdata.util.BulkPairChunks
import com.cryptochecker.marketdata.util.forEachName
import org.json.JSONObject

// Ref: https://docs.kraken.com/rest/#tag/Market-Data
class Kraken : SimpleMarket(
    "Kraken",
    "https://api.kraken.com/0/public/AssetPairs",
    "https://api.kraken.com/0/public/Ticker?pair=%1\$s"
) {

    override fun parseCurrencyPairsFromJsonObject(
        requestId: Int,
        jsonObject: JSONObject,
        pairs: MutableList<CurrencyPairInfo>
    ) {
        jsonObject
            .getJSONObject("result")
            .forEachName { pairId, pairJsonObject ->
                if (pairId.indexOf('.') == -1) {
                    pairs.add(
                        CurrencyPairInfo(
                            parseCurrency(pairJsonObject.getString("base")),
                            parseCurrency(pairJsonObject.getString("quote")),
                            pairId
                        )
                    )
                }
            }
    }

    override fun getPairId(checkerInfo: CheckerInfo): String {
        return super.getPairId(checkerInfo) ?: (fixCurrency(checkerInfo.currencyBase) + fixCurrency(checkerInfo.currencyCounter))
    }

    override fun parseTickerFromJsonObject(
        requestId: Int,
        jsonObject: JSONObject,
        ticker: Ticker,
        checkerInfo: CheckerInfo
    ) {
        val resultObject = jsonObject.getJSONObject("result")

        // Ein Paar je Anfrage: der erste (einzige) Schlüssel ist das Paar; leeres Ergebnis = Fehler
        val pairKey = resultObject.names()?.getString(0) ?: throw MarketParseException("Empty result")
        readTicker(resultObject.getJSONObject(pairKey), ticker)
    }

    /** Einzelabruf und Massenabfrage liefern je Paar dieselbe Struktur. */
    @Throws(Exception::class)
    private fun readTicker(json: JSONObject, ticker: Ticker) {
        // a/b/c: [Preis, …] – Index 0 ist der Kurs
        ticker.bid = getDoubleFromJsonArrayObject(json, "b", 0)
        ticker.ask = getDoubleFromJsonArrayObject(json, "a", 0)

        // h/l/v: [heute seit 00:00 UTC, gleitende 24 h] – die App zeigt 24-h-Werte, also Index 1
        ticker.high = getDoubleFromJsonArrayObject(json, "h", 1)
        ticker.low = getDoubleFromJsonArrayObject(json, "l", 1)

        ticker.vol = getDoubleFromJsonArrayObject(json, "v", 1)
        ticker.last = getDoubleFromJsonArrayObject(json, "c", 0)
        // Kein 24-h-Wert: „o“ ist die Eröffnung des UTC-Tages, nicht der Kurs vor 24 h.
    }

    /** Ohne pair-Parameter liefert der Endpunkt alle handelbaren Paare. */
    override val bulkTickersNumOfRequests: Int
        get() = 1

    override fun getBulkTickersUrl(requestId: Int): String = ALL_TICKERS_URL

    /**
     * Nur die beobachteten Paare («?pair=A,B,C») statt des ganzen Kursbuchs;
     * lange Listen auf mehrere Anfragen verteilt (URL < 2000 Zeichen).
     */
    override fun bulkTickersRequestCount(pairIds: Collection<String>): Int =
        BulkPairChunks.chunks(FILTERED_TICKERS_PREFIX, pairIds)?.size ?: bulkTickersNumOfRequests

    override fun getBulkTickersUrl(requestId: Int, pairIds: Collection<String>): String? {
        val chunks = BulkPairChunks.chunks(FILTERED_TICKERS_PREFIX, pairIds) ?: return ALL_TICKERS_URL
        return chunks.getOrNull(requestId)?.let { BulkPairChunks.url(FILTERED_TICKERS_PREFIX, it) }
    }

    @Throws(Exception::class)
    override fun parseBulkTickers(
        requestId: Int,
        responseString: String,
        tickers: MutableMap<String, Ticker>,
    ) {
        val json = JSONObject(responseString)
        val result = json.optJSONObject("result")
        // Kennt Kraken ein Paar der Liste nicht, scheitert die ganze Anfrage
        // ({"error":["EQuery:Unknown asset pair"]}) — dann folgt die ungefilterte.
        if (result == null || result.length() == 0) {
            throw MarketParseException(json.optJSONArray("error")?.optString(0).orEmpty().ifEmpty { "Empty result" })
        }
        result.forEachName { pairId, pairJson ->
            val ticker = SimpleTicker()
            readTicker(pairJson, ticker)
            // Ohne letzten Kurs (leeres «c») kein Eintrag – der Einzelabruf meldet dann den Fehler
            if (ticker.last <= Ticker.NO_DATA) return@forEachName
            tickers[pairId] = ticker
        }
    }

    override fun parseErrorFromJsonObject(
        requestId: Int,
        jsonObject: JSONObject,
        checkerInfo: CheckerInfo
    ): String? {
        return jsonObject
            .getJSONArray("error")
            .getString(0)
    }

    companion object {
        private const val ALL_TICKERS_URL = "https://api.kraken.com/0/public/Ticker"
        private const val FILTERED_TICKERS_PREFIX = "$ALL_TICKERS_URL?pair="

        private fun fixCurrency(currency: String): String {
            if (VirtualCurrency.BTC == currency) return VirtualCurrency.XBT
            if (VirtualCurrency.VEN == currency) return VirtualCurrency.XVN
            return if (VirtualCurrency.DOGE == currency) VirtualCurrency.XDG else currency
        }

        /** Wert an [index] der Liste [arrayKey]; fehlt die Liste oder ist sie zu kurz: kein Wert (nicht 0). */
        private fun getDoubleFromJsonArrayObject(jsonObject: JSONObject, arrayKey: String, index: Int): Double {
            val array = jsonObject.optJSONArray(arrayKey) ?: return Ticker.NO_DATA.toDouble()
            return if (array.length() > index) array.getDouble(index) else Ticker.NO_DATA.toDouble()
        }

        private fun parseCurrency(currency: String): String = KrakenAssetCodes.normalize(currency)
    }
}