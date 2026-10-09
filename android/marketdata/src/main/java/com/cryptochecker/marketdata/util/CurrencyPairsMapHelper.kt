package com.cryptochecker.marketdata.util

import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.CurrencyPairsListWithDate
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.currency.CurrencyPairsMap

class CurrencyPairsMapHelper(currencyPairsListWithDate: CurrencyPairsListWithDate?) {
    constructor(currencyMap: CurrencyPairsMap?): this(convertPairsMapToPairList(currencyMap))

    val date: Long = currencyPairsListWithDate?.date ?: 0
    private val pairs: List<CurrencyPairInfo> = currencyPairsListWithDate?.pairs?.sorted() ?: listOf()

    fun isEmpty() = pairs.isEmpty()
    val size: Int get() = pairs.size

    val baseAssets: Iterable<String> get() =
        pairs
            .map { it.currencyBase }
            .distinct()

    fun getQuoteAssets(baseAsset: String): Iterable<String> =
        pairs
            .filter { it.currencyBase == baseAsset }
            .map { it.currencyCounter }
            .distinct()

    fun getCurrencyPairId(baseAsset: String?, quoteAsset: String?, contractType: FuturesContractType): String? {
        if(baseAsset == null || quoteAsset == null) return null
        return pairs
            .firstOrNull { it.currencyBase == baseAsset
                    && it.currencyCounter == quoteAsset
                    && it.contractType == contractType}
            ?.currencyPairId
    }

    fun getAvailableFuturesContractsTypes(baseAsset: String?, quoteAsset: String?): List<FuturesContractType> {
        if(baseAsset == null || quoteAsset == null) return listOf()

        return pairs
            .filter { it.currencyBase == baseAsset && it.currencyCounter == quoteAsset }
            .map { it.contractType }
    }

    companion object {
        private fun convertPairsMapToPairList(currencyMap: CurrencyPairsMap?): CurrencyPairsListWithDate? {
            if(currencyMap == null) return null

            return CurrencyPairsListWithDate(0,
                currencyMap.flatMap { item ->
                    item.value.map { quoteAsset ->
                        CurrencyPairInfo(item.key, quoteAsset, null)
                    }
                }
            )
        }
    }
}