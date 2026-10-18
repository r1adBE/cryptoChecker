// TradFi-Logos und Namen aus den Listen (Teil von [CoinLogos]): Aktien-Logos von Binance, Auswahl der TradFi-Einträge,
// Namen zum Anzeigen. Erweiterungsfunktionen, damit `CoinLogos.withStockLogos(…)` usw. bleiben; Konstanten in [CoinLogos].
package com.cryptochecker.app.domain.logos

import com.cryptochecker.app.domain.logos.CoinLogos.BinanceEntry

/**
 * Aktien-Logo auf dem Bild-Server von Binance («https://bin.bnbstatic.com/static/stock/BYD.png»)
 * für ein TradFi-Kürzel; null, wenn das Kürzel nicht passt (nur A–Z, 0–9, Punkt, höchstens 12).
 * Geladen wird es für **alle** TradFi-Kürzel der gespeicherten Paarlisten (nie nur die der
 * Merkliste) und nur, wenn die Binance-Liste für das Kürzel kein Logo hat. Rohstoffe und
 * Devisen haben dort meist keins → Initialen.
 */
fun CoinLogos.stockLogoUrl(symbol: String): String? {
    val s = symbol.trim().uppercase()
    if (s.isEmpty() || s.length > 12 || !s.all { it in 'A'..'Z' || it in '0'..'9' || it == '.' }) return null
    return "$STOCK_LOGO_BASE$s.png"
}

/**
 * Zuordnung samt Aktien-Logos: zu [map] (Rangliste, Binance-Liste) kommt für jedes Kürzel aus
 * [tradFiBases] ohne eigenen Eintrag ([tradFiKey]) das Logo aus [stockLogoUrl].
 */
fun CoinLogos.withStockLogos(map: Map<String, String>, tradFiBases: Collection<String>): Map<String, String> {
    if (tradFiBases.isEmpty()) return map
    val out = LinkedHashMap(map)
    for (base in tradFiBases.sorted()) {
        val key = tradFiKey(base)
        if (key in out || fileName(key) == null) continue
        out[key] = stockLogoUrl(base) ?: continue
    }
    return out
}

private const val STOCK_LOGO_BASE = "https://bin.bnbstatic.com/static/stock/"

/**
 * TradFi-Logos aus der Binance-Liste, Schlüssel [tradFiKey]; vorhandene bleiben. Reihenfolge:
 *  1. Tokenisierte Aktie (Schlagwort «bStocks», Name mit «B» am Ende: «NVDAB» → NVDA) — sicher
 *     das Firmenlogo.
 *  2. Gleichnamiger Eintrag, den es nur als Futures gibt.
 *  3. Gleichnamiger Eintrag, den CoinGecko nicht kennt ([crypto], z. B. Gold, Silber).
 * Nie über [normalize] («1000CAT» ist ein Krypto-Token, nicht die Aktie CAT).
 */
fun CoinLogos.pickTradFi(entries: List<BinanceEntry>, crypto: Set<String>, into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> =
    pickTradFi(entries, crypto, into) { e -> e.logo?.takeIf { isAllowedUrl(it) }?.trim() }

/** Namen der TradFi-Paare in derselben Reihenfolge wie [pickTradFi] («NVDAB» → «NVIDIA»). */
fun CoinLogos.pickTradFiNames(entries: List<BinanceEntry>, crypto: Set<String>, into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> =
    pickTradFi(entries, crypto, into) { e -> cleanName(e.fullName, e.name) }

private fun CoinLogos.pickTradFi(
    entries: List<BinanceEntry>,
    crypto: Set<String>,
    into: MutableMap<String, String>,
    value: (BinanceEntry) -> String?,
): Map<String, String> {
    fun put(symbol: String, e: BinanceEntry) {
        val key = tradFiKey(symbol)
        if (fileName(key) == null || key in into) return
        into[key] = value(e) ?: return
    }
    for (e in entries) {
        val name = e.name.trim().uppercase()
        if (name.length > 1 && name.endsWith("B") && e.tags.any { it.equals(STOCK_TAG, ignoreCase = true) }) put(name.dropLast(1), e)
    }
    for (e in entries) if (e.onlyFutures) put(e.name, e)
    for (e in entries) {
        val name = e.name.trim().uppercase()
        // Ohne Hebel-Präfix («1000CAT» ist ein Krypto-Token)
        if (name !in crypto && normalize(name) == name) put(name, e)
    }
    return into
}

private val STOCK_SUFFIX = Regex("""[\s,(]*(bStocks?|xStocks?|Tokeni[sz]ed Stock)\)?\s*$""", RegexOption.IGNORE_CASE)

/**
 * Name zum Anzeigen: ohne Zusatz der tokenisierten Aktien («NVIDIA bStocks» → «NVIDIA»), ohne
 * Tabs und Zeilenumbrüche, gekürzt auf [NAME_MAX]. null, wenn leer. Ein Name gleich dem Kürzel
 * bleibt («BNB», «XRP», «Bonk» zu BONK) — besser als «–». [symbol] ist nur noch der Vollständigkeit
 * halber da (Aufrufer und gemeinsame Testfälle).
 */
@Suppress("UNUSED_PARAMETER")
fun CoinLogos.cleanName(raw: String?, symbol: String? = null): String? {
    var s = raw?.replace(SPACES, " ")?.trim() ?: return null
    s = s.replace(STOCK_SUFFIX, "").trim()
    if (s.isEmpty()) return null
    return if (s.length > NAME_MAX) s.take(NAME_MAX - 1).trimEnd() + "…" else s
}

/** Rangliste → Symbol → Name; erstes Vorkommen gewinnt (wie [pick]). */
fun CoinLogos.pickNames(ranked: List<Pair<String, String?>>, into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> {
    for ((symbol, name) in ranked) {
        val key = normalize(symbol)
        if (key.isEmpty() || key in into) continue
        into[key] = cleanName(name, symbol) ?: continue
    }
    return into
}
