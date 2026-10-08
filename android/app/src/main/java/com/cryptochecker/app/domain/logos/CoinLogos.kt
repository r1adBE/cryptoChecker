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
 * - Nur Bilder von CoinGecko- und Binance-Bildservern über HTTPS ([isAllowedUrl]).
 * - Kein Logo (unbekannt, Fehler, Schalter aus): Kreis mit [initials] — nie ein kaputtes Bild.
 */
object CoinLogos {

    /** Zuordnung eine Woche lang gültig (neue Coins kommen dazu, Logos ändern sich selten). */
    const val MAP_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Fehlgeschlagenes Bild erst nach einem Tag erneut versuchen. */
    const val FAILURE_TTL_MILLIS = 24L * 60 * 60 * 1000

    /** Seiten à 250 Coins: die grössten 1000 decken praktisch jede Merkliste ab. */
    const val PAGES = 4
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
    private val ALIASES = mapOf("XBT" to "BTC", "XDG" to "DOGE")

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
        val s = symbol.trim().uppercase()
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
     * damit zwei Symbole nie dieselbe Datei teilen.
     */
    fun fileName(symbol: String): String? {
        val key = normalize(symbol)
        if (key.isEmpty() || key.any { it !in 'A'..'Z' && it !in '0'..'9' }) return null
        return "$key.png"
    }
}
