package com.cryptochecker.app.domain.model

import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Paarliste einer Börse mit dem Zeitpunkt des Abgleichs. Unveränderlich; die Verzeichnisse für
 * die Auswahl im Explorer entstehen je Instanz einmal beim ersten Gebrauch, statt bei jedem
 * Wechsel die ganze Liste zu durchlaufen. Reihenfolge stets wie in [pairs] (erstes Vorkommen),
 * Vergleiche beachten Gross-/Kleinschreibung.
 */
data class MarketPairsInfo(
    val lastSyncDate: Long = 0,
    val pairs: List<MarketPair> = emptyList(),
    /**
     * Kürzel → voller Coin-Name aus derselben Paarliste ([com.cryptochecker.marketdata.model.Market.parseBaseNames]).
     * Nur direkt nach dem Abruf vorhanden, nicht in Room (eigene Datei: TokenIdentityStore).
     */
    val baseNames: Map<String, String> = emptyMap(),
) {
    val size: Int get() = pairs.size

    private val lookup: Lookup by lazy { Lookup.of(pairs) }

    val baseCurrencies: Iterable<String> get() = lookup.bases

    fun getQuoteCurrencies(baseCurrency: String): Iterable<String> =
        lookup.quotes[baseCurrency] ?: emptyList()

    fun getAvailableFuturesContractsTypes(baseCurrency: String?, quoteCurrency: String?): List<FuturesContractType> {
        val base = baseCurrency ?: return emptyList()
        val quote = quoteCurrency ?: return emptyList()
        return lookup.contracts[base to quote] ?: emptyList()
    }

    /**
     * Gegenwährungen für «Alle …-Paare», nach Zahl der Paare absteigend, bei Gleichstand
     * alphabetisch. Schreibweisen wie USDT und USDT.P bleiben getrennt, so wie die Börse sie führt.
     */
    val bulkQuoteCurrencies: List<String> by lazy {
        val counts = HashMap<String, Int>()
        for (pair in pairs) {
            val quote = pair.quote
            if (quote.isNotBlank()) counts[quote] = (counts[quote] ?: 0) + 1
        }
        counts.keys.sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { it })
    }

    /** Vorgewählte Gegenwährung: USDT, wenn vorhanden, sonst die häufigste. */
    val defaultBulkQuote: String?
        get() {
            val quotes = bulkQuoteCurrencies
            return quotes.find { it.equals("USDT", ignoreCase = true) } ?: quotes.firstOrNull()
        }

    /**
     * Paare mit genau dieser Gegenwährung (Spot und Futures), auf [contractType] beschränkt, wenn
     * gesetzt; je Basis/Gegenwährung/Kontrakt nur das erste, nach Basis sortiert.
     */
    fun pairsWithQuote(quote: String, contractType: FuturesContractType? = null): List<MarketPair> {
        val seen = HashSet<Triple<String, String, FuturesContractType>>()
        val picked = ArrayList<MarketPair>()
        for (pair in pairs) {
            if (pair.quote != quote) continue
            if (contractType != null && pair.contractType != contractType) continue
            if (seen.add(pair.lookupKey())) picked += pair
        }
        return picked.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.base })
    }

    /** Das erste Paar mit dieser Basis, Gegenwährung und diesem Kontrakttyp, sonst null. */
    fun getMarketPair(baseCurrency: String, quoteCurrency: String, contractType: FuturesContractType): MarketPair? =
        lookup.byKey[Triple(baseCurrency, quoteCurrency, contractType)]
}

private fun MarketPair.lookupKey(): Triple<String, String, FuturesContractType> =
    Triple(base, quote, contractType)

/** Einmal gebaute Verzeichnisse über eine Paarliste, Listen jeweils in Reihenfolge des ersten Vorkommens. */
private class Lookup(
    val bases: List<String>,
    val quotes: Map<String, List<String>>,
    val contracts: Map<Pair<String, String>, List<FuturesContractType>>,
    val byKey: Map<Triple<String, String, FuturesContractType>, MarketPair>,
) {
    companion object {
        fun of(pairs: List<MarketPair>): Lookup {
            val bases = LinkedHashSet<String>()
            val quotes = HashMap<String, MutableSet<String>>()
            val contracts = HashMap<Pair<String, String>, MutableSet<FuturesContractType>>()
            val byKey = HashMap<Triple<String, String, FuturesContractType>, MarketPair>(pairs.size * 2)
            pairs.forEach { pair ->
                bases.add(pair.base)
                quotes.getOrPut(pair.base) { LinkedHashSet() }.add(pair.quote)
                contracts.getOrPut(pair.base to pair.quote) { LinkedHashSet() }.add(pair.contractType)
                val key = pair.lookupKey()
                if (key !in byKey) byKey[key] = pair
            }
            return Lookup(
                bases = bases.toList(),
                quotes = quotes.mapValues { (_, set) -> set.toList() },
                contracts = contracts.mapValues { (_, set) -> set.toList() },
                byKey = byKey,
            )
        }
    }
}
