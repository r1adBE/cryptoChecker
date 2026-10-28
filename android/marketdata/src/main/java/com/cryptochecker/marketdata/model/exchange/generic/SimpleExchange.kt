package com.cryptochecker.marketdata.model.exchange.generic

import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.CurrencyPairsMap
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.util.getText
import org.json.JSONObject

/**
 * The typical exchange: one fixed pair-list URL (GET) and one ticker URL [tickerUrl] in which the pair id
 * replaces the placeholder [PAIR_ID]. A subclass still implements [key], [fillPairList]/[fillPairListJson] and
 * [fillTicker]/[fillTickerJson] (plus the optional bulk hooks of [Exchange]).
 *
 * Exchanges needing several pair lists, a combined list or POST requests extend [Exchange] directly.
 * iOS counterpart: `SimpleExchange` in `Exchange.swift`.
 *
 * @param name display name of the exchange.
 * @param pairListUrl URL of the pair list (GET).
 * @param tickerUrl single-ticker URL containing [PAIR_ID].
 * @param ttsName name for speech output; defaults to [name].
 * @param errorField field of an error response holding the exchange's text; null = none known (then override
 *   [errorMessageJson] or the exchange has no error text).
 * @param currencyPairs built-in pairs, see [Exchange.currencyPairs].
 */
abstract class SimpleExchange(
    name: String,
    private val pairListUrl: String,
    private val tickerUrl: String,
    ttsName: String = name,
    private val errorField: String? = null,
    currencyPairs: CurrencyPairsMap? = null,
) : Exchange(name, ttsName, currencyPairs) {

    /** Pair id inserted into [tickerUrl]; default: the one from the pair list. Override where it can be derived. */
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
        /** Placeholder for the pair id in [tickerUrl] (same as iOS `%1$s`). */
        const val PAIR_ID = "%1\$s"
    }
}
