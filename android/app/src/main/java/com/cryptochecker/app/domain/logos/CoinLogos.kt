package com.cryptochecker.app.domain.logos

/**
 * Regeln für die Coin-Logos (gleich in iOS `CoinLogos.swift`, gemeinsame Testfälle
 * `testdata/parity/coin_logos.json`):
 *
 * - Zuordnung Symbol → Bild-Adresse aus der öffentlichen CoinGecko-Rangliste (`/coins/markets`,
 *   Feld `image`), nach Marktkapitalisierung sortiert. Teilen sich Coins ein Symbol, gewinnt der
 *   grösste (der erste in der Rangliste).
 * - Nichts mitgeliefert, aber auch nichts einzeln: Die App lädt die Logos **aller** Coins der
 *   Rangliste auf einmal (danach nur neu dazugekommene) und zeigt sie nur aus dem Speicher auf dem
 *   Gerät. So sieht CoinGecko bei jedem Nutzer dieselben Abrufe und erfährt nie, welche Coins in
 *   einer Merkliste stehen. Coins ausserhalb der Rangliste bekommen Initialen.
 * - Zweite Quelle für Lücken: die öffentliche Symbolliste der Binance-Website ([BINANCE_LIST_URL],
 *   Feld `logo`) — vor allem TradFi (Gold, Silber, Aktien), die CoinGecko nicht führt. Sie füllt
 *   nur Symbole, die CoinGecko nicht kennt ([pick] lässt Vorhandenes stehen). Inoffizielle
 *   Schnittstelle: Fällt sie weg, bleiben dort Initialen.
 * - TradFi-Paare (Aktien, Rohstoffe, Devisen; Kennzeichen der Paarliste) nie aus CoinGecko: Dort
 *   heisst oft ein fremder Token gleich («CAT», «NVDA»). Ihr Logo kommt nur aus der Binance-Liste
 *   ([pickTradFi], Schlüssel [tradFiKey]); ohne Treffer Initialen.
 * - Nur Bilder von CoinGecko- und Binance-Bildservern über HTTPS ([isAllowedUrl]).
 * - Kein Logo (unbekannt, Fehler, Schalter aus): Kreis mit [initials] — nie ein kaputtes Bild.
 */
object CoinLogos {

    /** Zuordnung eine Woche lang gültig (neue Coins kommen dazu, Logos ändern sich selten). */
    const val MAP_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Fehlgeschlagenes Bild erst nach einem Tag erneut versuchen. */
    const val FAILURE_TTL_MILLIS = 24L * 60 * 60 * 1000

    /** Seiten à 250 Coins: die grössten 1000 zuerst (vor der Binance-Liste). */
    const val PAGES = 4

    /**
     * Danach weitere Seiten (Rang 1001–2500) nur für die übrigen Lücken — kleinere Coins wie AIN, AGT,
     * AIA oder LUNA (Terra 2.0). Erst nach der Binance-Liste, damit deren Logos bei gleichem Kürzel
     * vorgehen; mit Pause zwischen den Seiten (Abfragegrenze von CoinGecko).
     */
    const val EXTRA_PAGES = 6
    const val EXTRA_PAGE_PAUSE_MILLIS = 2_000L
    const val PER_PAGE = 250

    /** Kantenlänge der gespeicherten Bilder in Pixeln (scharf bis etwa 44 dp/pt bei 3×). */
    const val STORED_PX = 128

    /** Symbolliste der Binance-Website (inoffiziell, ohne Schlüssel), je Eintrag `name` und `logo`. */
    const val BINANCE_LIST_URL = "https://www.binance.com/bapi/composite/v1/public/marketing/symbol/list"

    fun marketsUrl(page: Int): String =
        "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc" +
            "&per_page=$PER_PAGE&page=$page&sparkline=false"

    /** Hebel-Präfixe der Futures («1000PEPE», «1MBABYDOGE») und Börsen-Eigenheiten («XBT»). */
    private val MULTIPLIER_PREFIXES = listOf("1000000", "100000", "10000", "1000", "1M")
    // «LUNA2»: Terra 2.0 heisst bei den Futures-Börsen so (LUNA2USDT), Logo und Name sind die von LUNA
    private val ALIASES = mapOf("XBT" to "BTC", "XDG" to "DOGE", "LUNA2" to "LUNA")

    /**
     * Symbol, unter dem das Logo gesucht wird: gross, ohne Leerraum, ohne Hebel-Präfix
     * («1000PEPE» → «PEPE»), Börsen-Kürzel vereinheitlicht («XBT» → «BTC»). Leer → leer.
     */
    fun normalize(symbol: String): String {
        var s = symbol.trim().uppercase()
        for (prefix in MULTIPLIER_PREFIXES) {
            if (s.startsWith(prefix) && s.length - prefix.length >= 2 && s[prefix.length].isLetter()) {
                s = s.removePrefix(prefix)
                break
            }
        }
        return ALIASES[s] ?: s
    }

    /**
     * Initialen für den Ersatz-Kreis: bis vier Zeichen ganz, sonst die ersten drei
     * (wie bisher in CoinBadge). Bewusst vom Original-Symbol, nicht von [normalize].
     */
    fun initials(symbol: String): String {
        val s = displaySymbol(symbol).trim().uppercase()
        return if (s.length <= 4) s else s.take(3)
    }

    /** Börse, deren Symbole jeder frei wählen kann (DEX-Pools). */
    const val DEX_MARKET_KEY = "DexScreener"

    /**
     * Logo für Paare dieser Börse? Bei DEX-Pools nie: Ein fremder Token darf «BTC» heissen —
     * dann nie das Bitcoin-Logo daneben, nur Initialen.
     */
    fun allowedFor(marketKey: String?): Boolean = marketKey != DEX_MARKET_KEY

    /** Nur HTTPS-Bilder von CoinGecko (`coin-images.coingecko.com` u. ä.) und Binance (`bin.bnbstatic.com`). */
    fun isAllowedUrl(url: String?): Boolean {
        if (url == null) return false
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').lowercase()
        if (host.isEmpty() || host.contains('@') || host.contains(':')) return false
        val known = host == "coingecko.com" || host.endsWith(".coingecko.com") || host.endsWith(".bnbstatic.com")
        if (!known) return false
        // Platzhalter für Coins ohne Logo («missing_large.png»): lieber Initialen
        return !url.contains("/missing_")
    }

    /**
     * Kleine Fassung des Bildes (CoinGecko liefert «thumb», «small», «large»; die Rangliste nennt
     * «large»). «small» reicht für 24–48 pt/dp und spart beim Laden aller Logos viel Datenvolumen.
     */
    fun smallUrl(url: String): String {
        val i = url.indexOf("/large/")
        return if (i < 0) url else url.substring(0, i) + "/small/" + url.substring(i + "/large/".length)
    }

    /**
     * Rangliste (in Reihenfolge der Marktkapitalisierung) → Symbol → Adresse.
     * Erstes Vorkommen gewinnt; ungültige Adressen und leere Symbole fallen weg.
     */
    fun pick(ranked: List<Pair<String, String?>>, into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> {
        for ((symbol, url) in ranked) {
            val key = normalize(symbol)
            if (key.isEmpty() || key in into || !isAllowedUrl(url)) continue
            into[key] = url!!.trim()
        }
        return into
    }

    // ---- TradFi (Aktien, Rohstoffe, Devisen)

    /** Börsen, deren Paarliste TradFi kennzeichnet (wie die Migration 11 → 12 und iOS `PairCache`). */
    val TRADFI_MARKETS = setOf("BinanceFutures", "BybitFutures", "OkexFutures", "MexcFutures", "BitgetFutures")

    /** Vorsilbe der Schlüssel für TradFi-Logos (eigener Namensraum, nie ein Krypto-Logo). */
    const val TRADFI_PREFIX = "TRADFI:"

    /** Schlüssel des Logos für ein TradFi-Paar: «TRADFI:NVDA» (Symbol unverändert, ohne Hebel-Präfix-Regel). */
    fun tradFiKey(symbol: String): String = TRADFI_PREFIX + symbol.trim().uppercase()

    /** Schlüssel für die Plakette: TradFi-Paare im eigenen Namensraum, sonst das Symbol. */
    fun logoKey(symbol: String, tradFi: Boolean): String = if (tradFi) tradFiKey(symbol) else symbol

    /**
     * Aktien-Logo auf dem Bild-Server von Binance («https://bin.bnbstatic.com/static/stock/BYD.png»)
     * für ein TradFi-Kürzel; null, wenn das Kürzel nicht passt (nur A–Z, 0–9, Punkt, höchstens 12).
     * Geladen wird es für **alle** TradFi-Kürzel der gespeicherten Paarlisten (nie nur die der
     * Merkliste) und nur, wenn die Binance-Liste für das Kürzel kein Logo hat. Rohstoffe und
     * Devisen haben dort meist keins → Initialen.
     */
    fun stockLogoUrl(symbol: String): String? {
        val s = symbol.trim().uppercase()
        if (s.isEmpty() || s.length > 12 || !s.all { it in 'A'..'Z' || it in '0'..'9' || it == '.' }) return null
        return "$STOCK_LOGO_BASE$s.png"
    }

    /**
     * Zuordnung samt Aktien-Logos: zu [map] (Rangliste, Binance-Liste) kommt für jedes Kürzel aus
     * [tradFiBases] ohne eigenen Eintrag ([tradFiKey]) das Logo aus [stockLogoUrl].
     */
    fun withStockLogos(map: Map<String, String>, tradFiBases: Collection<String>): Map<String, String> {
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

    /** Symbol zum Anzeigen (Initialen) ohne [TRADFI_PREFIX]. */
    fun displaySymbol(key: String): String = key.removePrefix(TRADFI_PREFIX)

    /** Kennung eines Paars für die Liste der TradFi-Paare: «BinanceFutures|NVDA|USDT|PERPETUAL». */
    fun pairKey(marketKey: String, base: String, quote: String, contractType: String): String =
        "$marketKey|${base.uppercase()}|${quote.uppercase()}|$contractType"

    /** Eintrag der Binance-Symbolliste: Kürzel, Logo, Schlagwörter («bStocks»), nur als Futures, voller Name. */
    data class BinanceEntry(
        val name: String,
        val logo: String?,
        val tags: List<String> = emptyList(),
        val onlyFutures: Boolean = false,
        val fullName: String? = null,
    )

    /**
     * TradFi-Logos aus der Binance-Liste, Schlüssel [tradFiKey]; vorhandene bleiben. Reihenfolge:
     *  1. Tokenisierte Aktie (Schlagwort «bStocks», Name mit «B» am Ende: «NVDAB» → NVDA) — sicher
     *     das Firmenlogo.
     *  2. Gleichnamiger Eintrag, den es nur als Futures gibt.
     *  3. Gleichnamiger Eintrag, den CoinGecko nicht kennt ([crypto], z. B. Gold, Silber).
     * Nie über [normalize] («1000CAT» ist ein Krypto-Token, nicht die Aktie CAT).
     */
    fun pickTradFi(entries: List<BinanceEntry>, crypto: Set<String>, into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> =
        pickTradFi(entries, crypto, into) { e -> e.logo?.takeIf { isAllowedUrl(it) }?.trim() }

    /** Namen der TradFi-Paare in derselben Reihenfolge wie [pickTradFi] («NVDAB» → «NVIDIA»). */
    fun pickTradFiNames(entries: List<BinanceEntry>, crypto: Set<String>, into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> =
        pickTradFi(entries, crypto, into) { e -> cleanName(e.fullName, e.name) }

    private fun pickTradFi(
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

    // ---- Namen («Bitcoin», «NVIDIA») aus denselben Listen

    /** Höchstlänge eines Namens in der Merkliste. */
    const val NAME_MAX = 40

    private val STOCK_SUFFIX = Regex("""[\s,(]*(bStocks?|xStocks?|Tokeni[sz]ed Stock)\)?\s*$""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    /**
     * Name zum Anzeigen: ohne Zusatz der tokenisierten Aktien («NVIDIA bStocks» → «NVIDIA»), ohne
     * Tabs und Zeilenumbrüche, gekürzt auf [NAME_MAX]. null, wenn leer. Ein Name gleich dem Kürzel
     * bleibt («BNB», «XRP», «Bonk» zu BONK) — besser als «–». [symbol] ist nur noch der Vollständigkeit
     * halber da (Aufrufer und gemeinsame Testfälle).
     */
    @Suppress("UNUSED_PARAMETER")
    fun cleanName(raw: String?, symbol: String? = null): String? {
        var s = raw?.replace(SPACES, " ")?.trim() ?: return null
        s = s.replace(STOCK_SUFFIX, "").trim()
        if (s.isEmpty()) return null
        return if (s.length > NAME_MAX) s.take(NAME_MAX - 1).trimEnd() + "…" else s
    }

    /** Rangliste → Symbol → Name; erstes Vorkommen gewinnt (wie [pick]). */
    fun pickNames(ranked: List<Pair<String, String?>>, into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> {
        for ((symbol, name) in ranked) {
            val key = normalize(symbol)
            if (key.isEmpty() || key in into) continue
            into[key] = cleanName(name, symbol) ?: continue
        }
        return into
    }

    // ---- Aktiennamen aus der Nasdaq-Symbolliste (alle US-Aktien, zwei Textdateien)

    /** Offizielle Symbolliste von Nasdaq (Nasdaq-Aktien) und der übrigen US-Börsen (NYSE u. a.). */
    const val NASDAQ_LISTED_URL = "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt"
    const val OTHER_LISTED_URL = "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt"

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
    fun cleanStockName(raw: String?): String? {
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
    fun parseSymbolDirectory(text: String?, into: MutableMap<String, String> = LinkedHashMap()): Map<String, String> {
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
    fun withStockNames(names: Map<String, String>, stockNames: Map<String, String>, tradFiBases: Collection<String>): Map<String, String> {
        if (tradFiBases.isEmpty() || stockNames.isEmpty()) return names
        val out = LinkedHashMap(names)
        for (base in tradFiBases.sorted()) {
            val key = tradFiKey(base)
            if (key in out) continue
            out[key] = stockNames[base.trim().uppercase()] ?: continue
        }
        return out
    }

    /** Namen-Zwischenspeicher: eine Zeile je Coin, «BTC\tBitcoin». */
    fun encodeNames(map: Map<String, String>): String =
        map.entries.joinToString("\n") { (key, name) -> "$key\t$name" }

    fun decodeNames(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val key = line.substringBefore('\t').trim()
            val name = cleanName(line.substringAfter('\t', ""))
            if (key.isNotEmpty() && name != null && key !in out) out[key] = name
        }
        return out
    }

    /** Schlüssel eines Namens: wie das Logo ([logoKey]), Krypto-Symbole vereinheitlicht ([normalize]). */
    fun nameKey(symbol: String, tradFi: Boolean): String = if (tradFi) tradFiKey(symbol) else normalize(symbol)

    /** Schlagwort der tokenisierten Aktien in der Binance-Liste. */
    const val STOCK_TAG = "bStocks"

    // ---- Zwischenspeicher: eine Zeile je Coin, «BTC\thttps://…»

    fun encode(map: Map<String, String>): String =
        map.entries.joinToString("\n") { (symbol, url) -> "$symbol\t$url" }

    fun decode(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val symbol = line.substringBefore('\t').trim()
            val url = line.substringAfter('\t', "").trim()
            if (symbol.isNotEmpty() && isAllowedUrl(url) && symbol !in out) out[symbol] = url
        }
        return out
    }

    fun isFresh(savedAt: Long, now: Long, ttl: Long = MAP_TTL_MILLIS): Boolean =
        savedAt in 1..now && now - savedAt < ttl

    /**
     * Dateiname im Bild-Zwischenspeicher. Nur Symbole aus A–Z und 0–9 (sonst null → Initialen),
     * damit zwei Symbole nie dieselbe Datei teilen; TradFi-Logos als «T_NVDA.png».
     */
    fun fileName(symbol: String): String? {
        if (symbol.startsWith(TRADFI_PREFIX)) {
            val raw = symbol.removePrefix(TRADFI_PREFIX).trim().uppercase()
            if (raw.isEmpty() || raw.any { it !in 'A'..'Z' && it !in '0'..'9' }) return null
            return "T_$raw.png"
        }
        val key = normalize(symbol)
        if (key.isEmpty() || key.any { it !in 'A'..'Z' && it !in '0'..'9' }) return null
        return "$key.png"
    }
}
