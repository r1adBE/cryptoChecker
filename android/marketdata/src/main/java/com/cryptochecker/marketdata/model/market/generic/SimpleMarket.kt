package com.cryptochecker.marketdata.model.market.generic

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.CurrencyPairsMap
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.util.getText
import org.json.JSONObject

/**
 * Die übliche Börse: eine feste Adresse für die Paarliste (GET) und eine Kursadresse [tickerUrl], in die die
 * Paar-Kennung an der Stelle [PAIR_ID] eingesetzt wird. [errorField]: Feld einer Fehlerantwort mit dem Text der
 * Börse, null = keines bekannt (dann überschreibt die Börse [errorMessageJson] oder hat keinen Fehlertext).
 *
 * Wer mehrere Paarlisten, eine zusammengesetzte Liste oder eine POST-Anfrage braucht, erbt direkt von [Market].
 * Gegenstück auf iOS: `SimpleMarket` in `Market.swift`.
 */
abstract class SimpleMarket(
    name: String,
    private val pairListUrl: String,
    private val tickerUrl: String,
    ttsName: String = name,
    private val errorField: String? = null,
    currencyPairs: CurrencyPairsMap? = null,
) : Market(name, ttsName, currencyPairs) {

    /** Kennung für die Kursadresse; Standard: die aus der Paarliste. Überschreiben, wo sie sich bilden lässt. */
    protected open fun pairId(info: PairQuery): String? = info.pairId

    /** Ohne Kennung steht wie seit jeher «null» in der Adresse; die Börse antwortet dann mit ihrem Fehler. */
    override fun tickerAddress(call: Int, info: PairQuery): String =
        tickerUrl.replace(PAIR_ID, pairId(info).toString())

    override fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? {
        val field = errorField ?: return super.errorMessageJson(call, json, info)
        // getText statt getString: JSON null ergibt keinen Fehlertext «null» (wie iOS)
        return json.getText(field)
    }

    final override fun pairListAddress(call: Int): String = pairListUrl

    companion object {
        /** Platzhalter der Paar-Kennung in [tickerUrl] (wie iOS `%1$s`). */
        const val PAIR_ID = "%1\$s"
    }
}
