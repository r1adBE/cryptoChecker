package com.cryptochecker.app.domain.logos

import com.cryptochecker.marketdata.model.market.BinanceAlpha

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
 * - Nur Bilder von CoinGecko- und Binance-Bildservern über HTTPS ([isAllowedUrl]). Kein eigenes
 *   Logo-Paket (CoinGecko-API-Bedingungen verbieten Weitergabe und Speicherung ihrer Daten).
 * - Mitgeliefert (Build 46): das freie Icon-Paket «Cryptocurrency Icons» (CC0, [ICON_PACK_FILE],
 *   rund 480 grössere Coins) — sofort und ohne Netz; Reihenfolge: CoinGecko → Binance-Liste/Alpha →
 *   mitgeliefertes Icon → Initialen. Kein Abruf bei jsDelivr mehr.
 * - Kein Logo (unbekannt, Fehler, Schalter aus): Kreis mit [initials] — nie ein kaputtes Bild.
 */
object CoinLogos {

    /**
     * Zuordnung (Rangliste/Namen von CoinGecko) höchstens 24 h gültig — CoinGecko-Bedingungen:
     * Daten höchstens 24 h zwischenspeichern. Schon geladene Bilder bleiben im Zwischenspeicher.
     */
    const val MAP_TTL_MILLIS = 24L * 60 * 60 * 1000

    /** Aktiennamen (Nasdaq-Symbolliste, nicht CoinGecko) weiterhin eine Woche lang gültig. */
    const val STOCK_NAMES_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Fehlgeschlagenes Bild erst nach einem Tag erneut versuchen. */
    const val FAILURE_TTL_MILLIS = 24L * 60 * 60 * 1000

    /** Seiten à 250 Coins: die grössten 1000 zuerst (vor der Binance-Liste). */
    const val PAGES = 4

    /**
     * Danach weitere Seiten (Rang 1001–2500) nur für die übrigen Lücken — kleinere Coins wie AIN
     * oder LUNA (Terra 2.0). Erst nach der Binance- und der Alpha-Liste, damit deren Logos bei
     * gleichem Kürzel vorgehen.
     * Nur auf nicht getakteten Netzen (WLAN, Ethernet): Auf mobilen Daten fehlen die Zusatzseiten, und die
     * Logos der Kürzel, die nur sie bringen, werden nicht geladen ([downloadable]) — die grössten 1000 sowie
     * die Binance- und Alpha-Liste laden wie bisher. Gleich in iOS (`CoinLogos.extraPages`).
     */
    const val EXTRA_PAGES = 6
    const val PER_PAGE = 250

    /**
     * Logos für den Sammel-Abruf: auf getakteten Netzen ([unmetered] = false) ohne die Kürzel, die nur die
     * Zusatzseiten ([EXTRA_PAGES]) bringen ([extraOnly]); sonst alle aus [wanted].
     */
    fun downloadable(wanted: Map<String, String>, extraOnly: Set<String>, unmetered: Boolean): Map<String, String> =
        if (unmetered || extraOnly.isEmpty()) wanted else wanted.filterKeys { it !in extraOnly }

    /** Kürzel, die erst die Zusatzseiten in die Zuordnung bringen ([all] ohne die Kürzel von [top]). */
    fun extraOnlyKeys(all: Map<String, String>, top: Map<String, String>): Set<String> = all.keys - top.keys

    /**
     * Pause vor jeder weiteren Seite der Rangliste. Ohne Schlüssel erlaubt CoinGecko nur wenige
     * Abrufe pro Minute (und die App fragt dort auch für Puls und Einordnung) — Seiten ohne Pause
     * scheiterten mit «429», die Liste blieb unvollständig.
     */
    const val PAGE_PAUSE_MILLIS = 6_000L

    /** «Zu viele Anfragen» ohne Angabe: so lange warten, dann die Seite einmal wiederholen. */
    const val RATE_LIMIT_WAIT_MILLIS = 30_000L
    const val RATE_LIMIT_WAIT_MAX_MILLIS = 60_000L

    /** Wartezeit nach «429» (Angabe «Retry-After» in Sekunden), begrenzt auf 1–60 s. */
    fun rateLimitWaitMillis(retryAfterSeconds: Long?): Long =
        (retryAfterSeconds?.times(1000) ?: RATE_LIMIT_WAIT_MILLIS).coerceIn(1_000L, RATE_LIMIT_WAIT_MAX_MILLIS)

    /** Unvollständige Liste (eine Quelle fehlte): nach so langer Zeit neu versuchen statt nach 24 h. */
    const val PARTIAL_TTL_MILLIS = 6L * 60 * 60 * 1000

    /**
     * Gespeicherter Zeitpunkt der Liste: vollständig → jetzt (24 h gültig), sonst so, dass sie
     * nach [PARTIAL_TTL_MILLIS] abläuft.
     */
    fun savedAtFor(now: Long, complete: Boolean): Long =
        if (complete) now else now - MAP_TTL_MILLIS + PARTIAL_TTL_MILLIS

    /**
     * Nur die Alpha-Liste holen (ohne Rangliste, ohne Bilder)? Wenn alle Logo-Schalter ([logos]) und
     * «Namen anzeigen» ([names]) aus sind, «inkl. Alpha-Tokens» ([includeAlpha]) aber an — sonst kommt
     * die Liste mit dem ganzen Abgleich bzw. wird gar nichts geladen.
     */
    fun alphaOnly(logos: Boolean, names: Boolean, includeAlpha: Boolean): Boolean = !logos && !names && includeAlpha

    /** Alpha-Liste allein nicht erreichbar (Server): frühestens nach einer Stunde erneut (wie die Rangliste). */
    const val ALPHA_RETRY_MILLIS = 60L * 60 * 1000

    /**
     * Alpha-Liste allein fällig ([alphaOnly])? Nein, solange sie gespeichert ([hasSymbols]) und höchstens
     * einen Tag alt ist ([savedAt], wie [MAP_TTL_MILLIS]) oder der letzte Versuch ([attemptAt]) weniger
     * als [ALPHA_RETRY_MILLIS] zurückliegt.
     */
    fun alphaListDue(savedAt: Long, attemptAt: Long, now: Long, hasSymbols: Boolean): Boolean =
        !(hasSymbols && isFresh(savedAt, now)) && !isFresh(attemptAt, now, ALPHA_RETRY_MILLIS)

    /**
     * Neue Liste, ergänzt um bisher bekannte Einträge, die diesmal fehlen (eine Quelle war nicht
     * erreichbar) — ein Teil-Abruf nimmt so nie vorhandene Logos oder Namen weg.
     */
    fun withKnown(fresh: Map<String, String>, known: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(fresh)
        for ((k, v) in known) if (k !in out) out[k] = v
        return out
    }

    /** Kantenlänge der gespeicherten Bilder in Pixeln (scharf bis etwa 44 dp/pt bei 3×). */
    const val STORED_PX = 128

    /** Symbolliste der Binance-Website (inoffiziell, ohne Schlüssel), je Eintrag `name` und `logo`. */
    const val BINANCE_LIST_URL = "https://www.binance.com/bapi/composite/v1/public/marketing/symbol/list"

    /**
     * Kürzel, deren Bild-Adresse sich geändert hat (in beiden Listen, verschiedene Adresse): ihr
     * gespeichertes Bild wird gelöscht und neu geladen.
     */
    fun changedKeys(old: Map<String, String>, new: Map<String, String>): Set<String> =
        new.keys.filterTo(LinkedHashSet()) { k -> old[k] != null && old[k] != new[k] }

    /**
     * Offizielle Token-Liste von Binance Alpha (ganze Liste, ohne Schlüssel; Felder `symbol`, `name`,
     * `iconUrl`): kleinere Token, die Binance als Futures führt (AIA, AGT, AIO …), oft ohne
     * Marktkapitalisierung bei CoinGecko und daher nicht in deren Rangliste.
     */
    const val ALPHA_LIST_URL = "https://www.binance.com/bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list"

    /**
     * Eintrag der Alpha-Liste; [listingCex] = auch regulär an der Börse Binance gelistet (Feld `listingCex`);
     * [alphaId] = Kennung für Kurse und Kerzen («ALPHA_175», [BinanceAlpha]; null = unbekannt).
     */
    data class AlphaEntry(
        val symbol: String,
        val name: String?,
        val icon: String?,
        val marketCap: Double? = null,
        val offline: Boolean = false,
        val listingCex: Boolean = false,
        val alphaId: String? = null,
    )

    /**
     * Antwort der Alpha-Liste ([ALPHA_LIST_URL], `{"code":"000000","data":[…]}`) → Einträge in
     * [rankAlpha]-Reihenfolge; null = unbrauchbar (kein `data`-Array). Kürzel über [cleanSymbol]
     * (Leer- und unsichtbare Zeichen der Token-Verträge fallen weg), `marketCap` als Text oder Zahl,
     * `iconUrl` auch relativ ([alphaIconUrl]). Einträge ohne Kürzel fallen weg; ein kaputter Eintrag
     * kostet nie die ganze Liste.
     */
    fun parseAlpha(text: String?): List<AlphaEntry>? {
        if (text.isNullOrBlank()) return null
        val array = try {
            org.json.JSONObject(text).optJSONArray("data")
        } catch (e: org.json.JSONException) {
            null
        } ?: return null
        return alphaEntries(array)
    }

    /** Einträge eines Alpha-`data`-Arrays (auch aus `coin_meta.json`, [CoinMeta]) wie [parseAlpha]. */
    fun alphaEntries(array: org.json.JSONArray): List<AlphaEntry> {
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
    fun cleanSymbol(raw: String?): String =
        raw.orEmpty().filterNot { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }

    /** Bild-Adresse der Alpha-Liste: relativ («/images/…») → Bild-Server von Binance; sonst unverändert. */
    fun alphaIconUrl(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return if (s.startsWith("/") && !s.startsWith("//")) "https://bin.bnbstatic.com$s" else s
    }

    /**
     * Kürzel der Alpha-Token ([com.cryptochecker.app.domain.watch.AssetKind.ALPHA]): handelbare
     * Einträge der Alpha-Liste (nicht `offline`), die Binance nicht regulär listet (`listingCex`),
     * wie [normalize].
     */
    fun alphaSymbols(entries: List<AlphaEntry>): Set<String> =
        entries.filter { !it.offline && !it.listingCex }
            .mapNotNullTo(LinkedHashSet()) { e -> normalize(e.symbol).takeIf { it.isNotEmpty() && fileName(it) != null } }

    /**
     * Token für die Alpha-Paare von Binance ([BinanceAlpha.pairs]) und «gleicher Token» ([alphaNames]):
     * handelbare, nicht regulär gelistete Einträge (wie [alphaSymbols]) in der Reihenfolge von [entries]
     * ([rankAlpha]: bei gleichem Kürzel der bekanntere zuerst).
     */
    fun alphaTokens(entries: List<AlphaEntry>): List<BinanceAlpha.Token> =
        entries.filter { !it.offline && !it.listingCex }.map { BinanceAlpha.Token(it.symbol, it.name, it.alphaId) }

    /**
     * Neue Alpha-Liste ohne Kennungen (Ersatzdatei `coin_meta.json` führt kein `alphaId`): Kennungen der
     * bisherigen Liste [saved] je Kürzel übernehmen, damit die Alpha-Paare nicht bis zum nächsten
     * direkten Abruf verschwinden. Einträge mit eigener Kennung bleiben unverändert.
     */
    fun keepAlphaIds(fresh: List<AlphaEntry>, saved: List<AlphaEntry>): List<AlphaEntry> {
        val ids = HashMap<String, String>()
        for (e in saved) e.alphaId?.let { ids.putIfAbsent(e.symbol, it) }
        if (ids.isEmpty()) return fresh
        return fresh.map { e -> if (e.alphaId != null) e else ids[e.symbol]?.let { e.copy(alphaId = it) } ?: e }
    }

    /** Hat die Liste Kennungen für die Alpha-Paare (sonst einmal direkt neu holen, z. B. nach dem Update)? */
    fun hasAlphaIds(entries: List<AlphaEntry>?): Boolean = entries?.any { it.alphaId != null } == true

    /** Namen der Alpha-Token je Kürzel (wie [normalize]); bei gleichem Kürzel gilt der erste. */
    fun alphaNames(tokens: List<BinanceAlpha.Token>): Map<String, String> {
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
     * wendet die Logos aber erst nach CoinGecko und Binance an — gleiche Vorrangregel wie bisher.
     */
    fun applyAlpha(
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
    fun rankAlpha(entries: List<AlphaEntry>): List<AlphaEntry> =
        entries.withIndex().sortedWith(
            compareBy<IndexedValue<AlphaEntry>> { if (it.value.offline) 1 else 0 }
                .thenByDescending { it.value.marketCap?.takeIf { c -> c.isFinite() && c >= 0 } ?: -1.0 }
                .thenBy { it.index },
        ).map { it.value }

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
     * Geprüft (Parity-Fälle): Auch «1000SATS» und «1000CAT» sind bei Binance Hebel-Kürzel
     * (1000 × SATS (Ordinals) bzw. 1000 × Simon's Cat, CoinGecko-Kürzel SATS bzw. CAT) — die
     * Zuordnung zu SATS/CAT ist richtig. Ein echter Token «1000…» ohne Hebel ist nicht bekannt;
     * nach dem Präfix müssen mindestens zwei Zeichen mit Buchstabe vorne folgen («1000X» bleibt).
     * Dieselbe Regel nutzt [com.cryptochecker.app.domain.spot.SpotMarket.lookupSymbol].
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

    /**
     * Nur HTTPS-Bilder von CoinGecko (`coin-images.coingecko.com` u. ä.) und Binance (`bin.bnbstatic.com`).
     * Adressen der früheren eigenen Logo-Liste (GitHub Pages) gelten nicht mehr.
     */
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
            if (url == null || key.isEmpty() || key in into || !isAllowedUrl(url)) continue
            into[key] = url.trim()
        }
        return into
    }

    // ---- TradFi (Aktien, Rohstoffe, Devisen)

    /**
     * Börsen, deren Paarliste TradFi kennzeichnet (wie die Migration 11 → 12 und iOS `PairCache`).
     * HTX/Phemex Futures kamen später dazu: ihre gespeicherten Listen tragen das Kennzeichen erst nach
     * dem nächsten Laden der Paarliste.
     */
    val TRADFI_MARKETS = setOf(
        "BinanceFutures", "BybitFutures", "OkexFutures", "MexcFutures", "BitgetFutures", "HtxFutures", "PhemexFutures",
    )

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

    // ---- Mitgelieferte Icons: «Cryptocurrency Icons» 0.18.1 (CC0 1.0, github.com/spothq/cryptocurrency-icons)

    /**
     * Mitgeliefertes Icon-Paket: die 64-px-PNGs (`32@2x/color`) der rund 480 grösseren Coins in einer
     * Datei (Android `assets/`, iOS `Shared/Resources` für App und Widgets; erzeugt mit
     * `android/tools/gen_coin_assets.py`). Vorrang haben immer die geladenen Logos der Listen.
     */
    const val ICON_PACK_FILE = "coin_icons.bin"
    private const val ICON_PACK_MAGIC = "CCIP1"

    /** Höchstgrösse des Inhaltsverzeichnisses (Schutz gegen kaputte Dateien). */
    private const val ICON_PACK_HEADER_MAX = 64 * 1024

    /** Stelle eines Icons im Paket: Anfang und Länge in Bytes. */
    data class IconSlice(val offset: Int, val length: Int)

    /** Name eines Icons im Paket («btc»); null bei TradFi oder ungültigem Kürzel. */
    fun bundledIconName(symbol: String): String? {
        if (symbol.startsWith(TRADFI_PREFIX)) return null
        val key = normalize(symbol)
        if (fileName(key) == null) return null
        return key.lowercase()
    }

    /**
     * Inhaltsverzeichnis des Icon-Pakets: Zeile «CCIP1», je Icon eine Zeile «name länge», eine
     * Leerzeile, dann die PNG-Bytes in derselben Reihenfolge. Ergebnis: Name → [IconSlice] in [bytes];
     * null = kein Paket oder kaputt (falsche Kennung, ungültige Zeile, Daten zu kurz).
     */
    fun parseIconPack(bytes: ByteArray): Map<String, IconSlice>? {
        val limit = minOf(bytes.size, ICON_PACK_HEADER_MAX)
        var end = -1
        for (i in 0 until limit - 1) {
            if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
                end = i
                break
            }
        }
        if (end < 0) return null
        val lines = String(bytes, 0, end, Charsets.US_ASCII).split('\n')
        if (lines.firstOrNull() != ICON_PACK_MAGIC) return null
        val out = LinkedHashMap<String, IconSlice>()
        var offset = end + 2
        for (line in lines.drop(1)) {
            val parts = line.split(' ')
            if (parts.size != 2) return null
            val name = parts[0]
            val length = parts[1].toIntOrNull() ?: return null
            if (name.isEmpty() || name.any { it !in 'a'..'z' && it !in '0'..'9' } || length <= 0) return null
            if (length > bytes.size - offset) return null
            if (name !in out) out[name] = IconSlice(offset, length)
            offset += length
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
