package com.cryptochecker.app.domain.logos

import com.cryptochecker.app.util.json.JsonObject
import com.cryptochecker.app.util.json.JsonArray
import com.cryptochecker.app.util.json.JsonException
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import com.cryptochecker.app.domain.logos.CoinLogos.AlphaEntry

// Binance-Alpha-Liste (Teil von [CoinLogos]): Antwort lesen, Kürzel bereinigen, Alpha-Kürzel und -Token, Kennungen
// behalten, Namen und Logos in Lücken. Erweiterungsfunktionen, damit `CoinLogos.parseAlpha(…)` usw. bleiben;
// gleich in iOS `CoinLogos+Alpha.swift`.

/**
 * Antwort der Alpha-Liste ([ALPHA_LIST_URL], `{"code":"000000","data":[…]}`) → Einträge in
 * [rankAlpha]-Reihenfolge; null = unbrauchbar (kein `data`-Array). Kürzel über [cleanSymbol]
 * (Leer- und unsichtbare Zeichen der Token-Verträge fallen weg), `marketCap` als Text oder Zahl,
 * `iconUrl` auch relativ ([alphaIconUrl]). Einträge ohne Kürzel fallen weg; ein kaputter Eintrag
 * kostet nie die ganze Liste.
 */
fun CoinLogos.parseAlpha(text: String?): List<AlphaEntry>? {
    if (text.isNullOrBlank()) return null
    val array = try {
        JsonObject(text).optJSONArray("data")
    } catch (e: JsonException) {
        null
    } ?: return null
    return alphaEntries(array)
}

/** Einträge eines Alpha-`data`-Arrays (auch aus `coin_meta.json`, [CoinMeta]) wie [parseAlpha]. */
fun CoinLogos.alphaEntries(array: JsonArray): List<AlphaEntry> {
    val out = ArrayList<AlphaEntry>(array.length())
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val symbol = cleanSymbol(o.optString("symbol"))
        if (symbol.isEmpty()) continue
        out += AlphaEntry(
            symbol = symbol,
            name = if (o.isNull("name")) null else o.optString("name"),
            icon = if (o.isNull("iconUrl")) null else alphaIconUrl(o.optString("iconUrl")),
            marketCap = if (o.isNull("marketCap")) null else o.optString("marketCap").trim().toDoubleOrNull(),
            offline = o.optBoolean("offline", false),
            listingCex = o.optBoolean("listingCex", false),
            alphaId = if (o.isNull("alphaId")) null else BinanceAlpha.alphaId(o.optString("alphaId")),
        )
    }
    return rankAlpha(out)
}

/**
 * Kürzel aus einem Token-Vertrag: ohne Leerraum (auch geschütztes Leerzeichen) und ohne
 * unsichtbare Formatzeichen (z. B. Zero-Width-Space) — sonst fiel «FROGGIE​» bei [fileName] weg.
 */
fun CoinLogos.cleanSymbol(raw: String?): String =
    raw.orEmpty().filterNot { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }

/** Bild-Adresse der Alpha-Liste: relativ («/images/…») → Bild-Server von Binance; sonst unverändert. */
fun CoinLogos.alphaIconUrl(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    return if (s.startsWith("/") && !s.startsWith("//")) "https://bin.bnbstatic.com$s" else s
}

/**
 * Kürzel der Alpha-Token ([com.cryptochecker.app.domain.watch.AssetKind.ALPHA]): handelbare
 * Einträge der Alpha-Liste (nicht `offline`), die Binance nicht regulär listet (`listingCex`),
 * wie [normalize].
 */
fun CoinLogos.alphaSymbols(entries: List<AlphaEntry>): Set<String> =
    entries.filter { !it.offline && !it.listingCex }
        .mapNotNullTo(LinkedHashSet()) { e -> normalize(e.symbol).takeIf { it.isNotEmpty() && fileName(it) != null } }

/**
 * Token für die Alpha-Paare von Binance ([BinanceAlpha.pairs]) und «gleicher Token» ([alphaNames]):
 * handelbare, nicht regulär gelistete Einträge (wie [alphaSymbols]) in der Reihenfolge von [entries]
 * ([rankAlpha]: bei gleichem Kürzel der bekanntere zuerst).
 */
fun CoinLogos.alphaTokens(entries: List<AlphaEntry>): List<BinanceAlpha.Token> =
    entries.filter { !it.offline && !it.listingCex }.map { BinanceAlpha.Token(it.symbol, it.name, it.alphaId) }

/**
 * Neue Alpha-Liste ohne Kennungen (Ersatzdatei `coin_meta.json` führt kein `alphaId`): Kennungen der
 * bisherigen Liste [saved] je Kürzel übernehmen, damit die Alpha-Paare nicht bis zum nächsten
 * direkten Abruf verschwinden. Einträge mit eigener Kennung bleiben unverändert.
 */
fun CoinLogos.keepAlphaIds(fresh: List<AlphaEntry>, saved: List<AlphaEntry>): List<AlphaEntry> {
    val ids = HashMap<String, String>()
    for (e in saved) e.alphaId?.let { ids.putIfAbsent(e.symbol, it) }
    if (ids.isEmpty()) return fresh
    return fresh.map { e -> if (e.alphaId != null) e else ids[e.symbol]?.let { e.copy(alphaId = it) } ?: e }
}

/** Hat die Liste Kennungen für die Alpha-Paare (sonst einmal direkt neu holen)? */
fun CoinLogos.hasAlphaIds(entries: List<AlphaEntry>?): Boolean = entries?.any { it.alphaId != null } == true

/** Namen der Alpha-Token je Kürzel (wie [normalize]); bei gleichem Kürzel gilt der erste. */
fun CoinLogos.alphaNames(tokens: List<BinanceAlpha.Token>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (t in tokens) {
        val name = t.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        val key = normalize(t.symbol)
        if (key.isNotEmpty() && key !in out) out[key] = name
    }
    return out
}

/**
 * Logos und Namen der Alpha-Liste ([entries] in [rankAlpha]-Reihenfolge) füllen nur Lücken in
 * [into] bzw. [names] (Vorhandenes von CoinGecko und Binance bleibt, wie [pick]); Ergebnis: die
 * Alpha-Kürzel ([alphaSymbols]). Der Abgleich holt die Alpha-Liste zuerst und speichert die Kürzel
 * sofort (sonst kamen sie erst nach allen Seiten der Rangliste, oft Minuten später oder nie),
 * wendet die Logos aber erst nach CoinGecko und Binance an — gleiche Vorrangregel.
 */
fun CoinLogos.applyAlpha(
    entries: List<AlphaEntry>,
    into: MutableMap<String, String>,
    names: MutableMap<String, String>,
): Set<String> {
    pick(entries.map { it.symbol to it.icon }, into)
    pickNames(entries.map { it.symbol to it.name }, names)
    return alphaSymbols(entries)
}

/**
 * Reihenfolge für [pick]/[pickNames]: handelbare vor abgemeldeten, dann grösste
 * Marktkapitalisierung (unbekannt zuletzt); sonst Reihenfolge der Liste. Bei gleichem Kürzel
 * gewinnt so der bekanntere Token.
 */
fun CoinLogos.rankAlpha(entries: List<AlphaEntry>): List<AlphaEntry> =
    entries.withIndex().sortedWith(
        compareBy<IndexedValue<AlphaEntry>> { if (it.value.offline) 1 else 0 }
            .thenByDescending { it.value.marketCap?.takeIf { c -> c.isFinite() && c >= 0 } ?: -1.0 }
            .thenBy { it.index },
    ).map { it.value }
