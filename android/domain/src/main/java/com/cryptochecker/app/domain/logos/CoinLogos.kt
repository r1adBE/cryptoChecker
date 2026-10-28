package com.cryptochecker.app.domain.logos

import com.cryptochecker.marketdata.model.exchange.BinanceAlpha

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
 * - Mitgeliefert: das freie Icon-Paket «Cryptocurrency Icons» (CC0, [ICON_PACK_FILE],
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

    /**
     * Höchstens so viele Pixel (Breite × Höhe laut Bildkopf) hat ein Logo-Quellbild; grössere werden verworfen,
     * ohne sie zu dekodieren. Die Übertragungsgrenze (4 MB) schützt nicht: Ein stark komprimiertes PNG kann
     * 20'000 × 20'000 Pixel ankündigen. Wie iOS `CoinLogos.maxSourcePixels`.
     */
    const val MAX_SOURCE_PIXELS = 4096L * 4096L

    /** Quellbild zu gross (oder ohne gültige Masse)? */
    fun sourceTooLarge(width: Int, height: Int): Boolean =
        width <= 0 || height <= 0 || width.toLong() * height.toLong() > MAX_SOURCE_PIXELS

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

    // Alpha-Liste lesen und auswerten (parseAlpha, alphaSymbols, applyAlpha …): CoinLogosAlpha.kt

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
    const val DEX_EXCHANGE_KEY = "DexScreener"

    /**
     * Logo für Paare dieser Börse? Bei DEX-Pools nie: Ein fremder Token darf «BTC» heissen —
     * dann nie das Bitcoin-Logo daneben, nur Initialen.
     */
    fun allowedFor(exchangeKey: String?): Boolean = exchangeKey != DEX_EXCHANGE_KEY

    /**
     * Nur HTTPS-Bilder von CoinGecko (`coin-images.coingecko.com` u. ä.) und Binance (`bin.bnbstatic.com`).
     * Adressen der früheren eigenen Logo-Liste (GitHub Pages) gelten nicht mehr. Mit URL-Parser
     * ([LogoUrlPolicy], SEC3-02).
     */
    fun isAllowedUrl(url: String?): Boolean = LogoUrlPolicy.isAllowed(url)

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
     * HTX/Phemex Futures, Aster, BingX Futures und Lighter kamen später dazu: ihre gespeicherten Listen tragen
     * das Kennzeichen erst nach dem nächsten Laden der Paarliste. Hyperliquid kennzeichnet TradFi nicht.
     */
    val TRADFI_EXCHANGES = setOf(
        "BinanceFutures", "BybitFutures", "OkexFutures", "MexcFutures", "BitgetFutures", "HtxFutures", "PhemexFutures",
        "Aster", "BingXFutures", "Lighter",
    )

    /** Vorsilbe der Schlüssel für TradFi-Logos (eigener Namensraum, nie ein Krypto-Logo). */
    const val TRADFI_PREFIX = "TRADFI:"

    /** Schlüssel des Logos für ein TradFi-Paar: «TRADFI:NVDA» (Symbol unverändert, ohne Hebel-Präfix-Regel). */
    fun tradFiKey(symbol: String): String = TRADFI_PREFIX + symbol.trim().uppercase()

    /** Schlüssel für die Plakette: TradFi-Paare im eigenen Namensraum, sonst das Symbol. */
    fun logoKey(symbol: String, tradFi: Boolean): String = if (tradFi) tradFiKey(symbol) else symbol

    /** Symbol zum Anzeigen (Initialen) ohne [TRADFI_PREFIX]. */
    fun displaySymbol(key: String): String = key.removePrefix(TRADFI_PREFIX)

    /** Kennung eines Paars für die Liste der TradFi-Paare: «BinanceFutures|NVDA|USDT|PERPETUAL». */
    fun pairKey(exchangeKey: String, base: String, quote: String, contractType: String): String =
        "$exchangeKey|${base.uppercase()}|${quote.uppercase()}|$contractType"

    /** Eintrag der Binance-Symbolliste: Kürzel, Logo, Schlagwörter («bStocks»), nur als Futures, voller Name. */
    data class BinanceEntry(
        val name: String,
        val logo: String?,
        val tags: List<String> = emptyList(),
        val onlyFutures: Boolean = false,
        val fullName: String? = null,
    )

    // ---- Namen («Bitcoin», «NVIDIA») aus denselben Listen

    /** Höchstlänge eines Namens in der Merkliste. */
    const val NAME_MAX = 40

    val SPACES = Regex("""\s+""")

    // ---- Aktiennamen aus der Nasdaq-Symbolliste (alle US-Aktien, zwei Textdateien)

    /** Offizielle Symbolliste von Nasdaq (Nasdaq-Aktien) und der übrigen US-Börsen (NYSE u. a.). */
    const val NASDAQ_LISTED_URL = "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt"
    const val OTHER_LISTED_URL = "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt"

    // Namen aus der Symbolliste (cleanStockName, parseSymbolDirectory, withStockNames): CoinLogosStockNames.kt

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

    // Icon-Paket lesen (bundledIconName, parseIconPack): CoinLogosIconPack.kt

    /** Stelle eines Icons im Paket: Anfang und Länge in Bytes. */
    data class IconSlice(val offset: Int, val length: Int)

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
