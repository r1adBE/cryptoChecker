package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Paare einer Börse. Unveränderlich: Die Nachschlage-Verzeichnisse werden je Instanz
 * einmal beim ersten Zugriff aufgebaut (statt bei jeder Auswahl im Explorer die ganze
 * Liste zu durchsuchen). Reihenfolgen wie in [pairs] (erstes Vorkommen), Vergleiche
 * mit Gross-/Kleinschreibung.
 */
data class MarketPairsInfo(
    val lastSyncDate: Long = 0,
    val pairs: List<CurrencyPairInfo> = emptyList(),
) {
    val size: Int get() = pairs.size

    private val index: PairIndex by lazy { PairIndex(pairs) }

    val baseCurrencies: Iterable<String> get() = index.bases

    fun getQuoteCurrencies(baseCurrency: String): Iterable<String> =
        index.quotesByBase[baseCurrency].orEmpty()

    fun getAvailableFuturesContractsTypes(baseCurrency: String?, quoteCurrency: String?): List<FuturesContractType> {
        if(baseCurrency == null || quoteCurrency == null) return emptyList()

        return index.contractsByPair[baseCurrency to quoteCurrency].orEmpty()
    }

    /**
     * Gegenwährungen der Börse für «Alle …-Paare», die häufigste zuerst.
     * USDT-Schreibweisen (USDT, USDT.P …) bleiben getrennt, wie die Börse sie führt.
     */
    val bulkQuoteCurrencies: List<String> by lazy {
        pairs
            .mapNotNull { it.currencyCounter.takeIf { q -> q.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    /** Vorauswahl: USDT, sonst die häufigste Gegenwährung. */
    val defaultBulkQuote: String? get() =
        bulkQuoteCurrencies.let { quotes -> quotes.firstOrNull { it.equals("USDT", true) } ?: quotes.firstOrNull() }

    /**
     * Alle Paare der Börse mit der gewählten Gegenwährung (Spot wie Futures),
     * eingegrenzt auf den gewählten Kontrakttyp.
     */
    fun pairsWithQuote(quote: String, contractType: FuturesContractType? = null): List<CurrencyPairInfo> =
        pairs
            .filter { it.currencyCounter == quote }
            .filter { contractType == null || it.contractType == contractType }
            .distinctBy { Triple(it.currencyBase, it.currencyCounter, it.contractType) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.currencyBase })

    /** Erstes Paar mit genau dieser Basis, Gegenwährung und diesem Kontrakttyp. */
    fun getCurrencyPairInfo(baseCurrency: String, quoteCurrency: String, contractType: FuturesContractType): CurrencyPairInfo? =
        index.pairByKey[Triple(baseCurrency, quoteCurrency, contractType)]
}

/** Einmal aufgebaute Verzeichnisse über [pairs]; jede Liste in der Reihenfolge des ersten Vorkommens. */
private class PairIndex(pairs: List<CurrencyPairInfo>) {
    val bases: List<String>
    val quotesByBase: Map<String, List<String>>
    val contractsByPair: Map<Pair<String, String>, List<FuturesContractType>>
    val pairByKey: Map<Triple<String, String, FuturesContractType>, CurrencyPairInfo>

    init {
        val baseSet = LinkedHashSet<String>()
        val quotes = HashMap<String, LinkedHashSet<String>>()
        val contracts = HashMap<Pair<String, String>, LinkedHashSet<FuturesContractType>>()
        val byKey = HashMap<Triple<String, String, FuturesContractType>, CurrencyPairInfo>(pairs.size * 2)
        for (pair in pairs) {
            val base = pair.currencyBase
            val quote = pair.currencyCounter
            baseSet += base
            quotes.getOrPut(base) { LinkedHashSet() } += quote
            contracts.getOrPut(base to quote) { LinkedHashSet() } += pair.contractType
            byKey.putIfAbsent(Triple(base, quote, pair.contractType), pair)
        }
        bases = baseSet.toList()
        quotesByBase = quotes.mapValues { it.value.toList() }
        contractsByPair = contracts.mapValues { it.value.toList() }
        pairByKey = byKey
    }
}
