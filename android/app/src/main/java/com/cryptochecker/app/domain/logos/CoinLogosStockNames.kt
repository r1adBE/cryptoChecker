package com.cryptochecker.app.domain.logos

// Aktiennamen aus der Nasdaq-Symbolliste (Teil von [CoinLogos]): Firmenname ohne Wertpapier-Zusatz, Datei der
// Symbolliste lesen, Namen der TradFi-Kürzel. Erweiterungsfunktionen, damit `CoinLogos.cleanStockName(…)` usw.
// bleiben; gleich in iOS `CoinLogos+StockNames.swift`.

private val STOCK_NAME_TAIL = Regex(
    "[\\s,]*(?:New\\s+)?(?:(?:Class|Series)\\s+[A-Z0-9]+\\s+)?(?:Common Stock|Common Shares|Ordinary Shares|" +
        "American Deposit[ao]ry Shares|Depositary Shares|Shares of Beneficial Interest)\\b.*$",
    RegexOption.IGNORE_CASE
)

/**
 * Firmenname aus der Symbolliste ohne Wertpapier-Zusatz: «Caterpillar, Inc. Common Stock» →
 * «Caterpillar, Inc.», «Apple Inc. - Common Stock» → «Apple Inc.», «Alphabet Inc. - Class A
 * Common Stock» → «Alphabet Inc.». Danach wie [cleanName]; null, wenn nichts bleibt.
 */
fun CoinLogos.cleanStockName(raw: String?): String? {
    var s = raw?.replace(SPACES, " ")?.trim() ?: return null
    val dash = s.indexOf(" - ")
    if (dash > 0) s = s.substring(0, dash)
    s = s.replace(STOCK_NAME_TAIL, "").trim().trimEnd(',', ' ')
    return cleanName(s)
}

/**
 * Eine Datei der Nasdaq-Symbolliste («Symbol|Security Name|…» bzw. «ACT Symbol|…») → Kürzel →
 * Name ([cleanStockName]). Test-Einträge («Test Issue» = Y) und die Schlusszeile fallen weg;
 * erstes Vorkommen gewinnt.
 */
fun CoinLogos.parseSymbolDirectory(text: String?, into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> {
    val lines = text?.lineSequence()?.iterator() ?: return into
    if (!lines.hasNext()) return into
    val header = lines.next().split('|').map { it.trim() }
    val sym = header.indexOfFirst { it == "Symbol" || it == "ACT Symbol" }
    val name = header.indexOf("Security Name")
    val test = header.indexOf("Test Issue")
    if (sym < 0 || name < 0) return into
    for (line in lines) {
        val parts = line.split('|')
        if (parts.size <= maxOf(sym, name)) continue
        if (test >= 0 && parts.getOrNull(test)?.trim() == "Y") continue
        val symbol = parts[sym].trim().uppercase()
        if (symbol.isEmpty() || symbol in into) continue
        into[symbol] = cleanStockName(parts[name]) ?: continue
    }
    return into
}

/**
 * Namen der TradFi-Kürzel [tradFiBases] aus der Symbolliste [stockNames] ([tradFiKey] als
 * Schlüssel), nur wo [names] noch keinen hat (bStocks-Namen der Binance-Liste gehen vor).
 */
fun CoinLogos.withStockNames(names: Map<String, String>, stockNames: Map<String, String>, tradFiBases: Collection<String>): Map<String, String> {
    if (tradFiBases.isEmpty() || stockNames.isEmpty()) return names
    val out = LinkedHashMap(names)
    for (base in tradFiBases.sorted()) {
        val key = tradFiKey(base)
        if (key in out) continue
        out[key] = stockNames[base.trim().uppercase()] ?: continue
    }
    return out
}
