package com.cryptochecker.app.domain.watch

import java.text.Normalizer
import java.util.Locale

/**
 * «Gleicher Token?» über mehr als Kürzel + Kursband (K-8). Reines Kotlin, gespiegelt in
 * `TokenIdentity.swift` (gemeinsame Fälle: testdata/parity/token_identity.json).
 *
 * Identität eines Paars = voller Name aus der Paarliste der Börse (Gate `base_name`, Upbit/Bithumb
 * `english_name`, Latoken `name`; Binance: `fullName` der Symbolliste, die schon für die Logos geladen
 * wird) und optional Verträge «chain:adresse». Regel:
 *  - Verträge auf beiden Seiten: gemeinsamer Vertrag → [Match.STRONG]; gleiche Kette, andere Adresse →
 *    [Match.MISMATCH]; sonst weiter mit den Namen.
 *  - Namen auf beiden Seiten: gleich (normalisiert, bekannte Schreibweisen, [KNOWN]) → [Match.MEDIUM];
 *    klar verschieden (kein gemeinsames Wort, verschiedene Gruppen in [KNOWN], «Classic» nur auf einer
 *    Seite) → [Match.MISMATCH]; ähnlich, aber nicht sicher → [Match.WEAK].
 *  - Ohne Namen auf einer Seite: [Match.WEAK] — Kürzel + Kursband wie bisher.
 * [accept]: STRONG gilt ohne Kursband, MEDIUM und WEAK nur mit Kursband, MISMATCH nie — auch wenn die
 * Kurse nah beieinander liegen.
 */
object TokenIdentity {

    enum class Match { STRONG, MEDIUM, WEAK, MISMATCH }

    /** Identität einer Seite: Name (roh, wie die Börse ihn liefert) und Verträge ([contractKey]). */
    data class Identity(val name: String? = null, val contracts: Set<String> = emptySet())

    /**
     * Bekannte Kürzel mit mehreren Token bzw. mehreren Namen für denselben Token: je Kürzel Gruppen,
     * eine Gruppe = ein Token. Gleiche Gruppe → MEDIUM (auch bei ganz anderem Namen, z. B. nach einer
     * Umbenennung), verschiedene Gruppen → MISMATCH. Namen werden wie alle anderen normalisiert.
     */
    val KNOWN: Map<String, List<Set<String>>> = mapOf(
        // Terra 2.0 (neu) gegen Terra Classic (alt; bei manchen Börsen noch unter «LUNA»)
        "LUNA" to listOf(
            setOf("Terra", "Terra 2.0", "Terra Luna 2.0", "LUNA 2.0"),
            setOf("Terra Classic", "Terra Luna Classic", "LUNA Classic"),
        ),
        "LUNC" to listOf(
            setOf("Terra Classic", "Terra Luna Classic", "LUNA Classic"),
            setOf("Terra", "Terra 2.0", "Terra Luna 2.0", "LUNA 2.0"),
        ),
        "CAT" to listOf(setOf("Simon's Cat", "Simons Cat"), setOf("Catcoin")),
        // Aktie (TradFi) gegen tokenisierte Aktie
        "NVDA" to listOf(setOf("NVIDIA", "NVIDIA Corporation", "NVIDIA Corp"), setOf("NVIDIA xStock", "NVIDIA Tokenized Stock")),
        "TON" to listOf(setOf("Toncoin", "The Open Network", "Gram"), setOf("Tokamak Network")),
        "GMT" to listOf(setOf("STEPN", "Green Metaverse Token"), setOf("GoMining", "GoMining Token")),
        "HOT" to listOf(setOf("Holo", "Holochain", "HoloToken"), setOf("Hydro Protocol", "Hydro")),
        // Umbenennungen: andere Namen, derselbe Token
        "FET" to listOf(setOf("Fetch.ai", "Artificial Superintelligence Alliance", "ASI Alliance")),
        "POL" to listOf(setOf("Polygon", "Polygon Ecosystem Token", "Matic Network")),
        "MATIC" to listOf(setOf("Polygon", "Matic Network")),
        "EGLD" to listOf(setOf("MultiversX", "Elrond")),
        "KAIA" to listOf(setOf("Kaia", "Klaytn")),
        "XDC" to listOf(setOf("XDC Network", "XinFin", "XinFin Network")),
        "XRP" to listOf(setOf("Ripple", "XRP Ledger")),
        "QTUM" to listOf(setOf("Qtum", "Quantum")),
        "S" to listOf(setOf("Sonic", "Fantom")),
    )

    /** Füllwörter, die zwei Namen desselben Tokens unterscheiden können («Tether USD», «Gate Token»). */
    private val STOP_WORDS = setOf("token", "tokens", "coin", "network", "protocol", "finance", "the", "new", "labs", "foundation")

    /** Zusätze, die einen anderen Token bezeichnen, wenn nur eine Seite sie trägt. */
    private val MARKERS = setOf("classic", "xstock", "tokenized")

    /** Kleinbuchstaben ohne Akzente; Klammern («(prev. FTM)») entfallen, Apostrophe verbinden («simons»). */
    private fun words(raw: String): List<String> {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
            .replace(Regex("['’`]"), "")
        return folded.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in STOP_WORDS }
    }

    private fun compactSymbol(symbol: String): String =
        symbol.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }

    /**
     * Name für den Vergleich (Wörter mit Leerzeichen); null = kein brauchbarer Name (leer, nur Füllwörter,
     * nicht lateinisch oder bloss das Kürzel, z. B. «BNB» für BNB).
     */
    fun normalizeName(name: String?, symbol: String): String? {
        if (name.isNullOrBlank()) return null
        val w = words(name)
        if (w.isEmpty()) return null
        val compact = w.joinToString("")
        if (compact == compactSymbol(symbol)) return null
        return w.joinToString(" ")
    }

    /** Vertrag «chain:adresse»; EVM-Adressen (0x…) ohne Gross-/Kleinschreibung. null = unbrauchbar. */
    fun contractKey(chain: String, address: String): String? {
        val c = chain.trim().lowercase(Locale.ROOT)
        val a = address.trim().let { if (it.startsWith("0x", ignoreCase = true)) it.lowercase(Locale.ROOT) else it }
        if (c.isEmpty() || a.isEmpty()) return null
        return "$c:$a"
    }

    private fun normalizedContract(key: String): String? {
        val i = key.indexOf(':')
        if (i <= 0) return null
        return contractKey(key.substring(0, i), key.substring(i + 1))
    }

    private fun groupOf(symbol: String, normalized: String): Int? {
        val groups = KNOWN[symbol.trim().uppercase(Locale.ROOT)] ?: return null
        val index = groups.indexOfFirst { g -> g.any { normalizeName(it, symbol) == normalized } }
        return index.takeIf { it >= 0 }
    }

    /** Ist [short] (mind. 3 Zeichen) eine Teilfolge von [long] («qtum» in «quantum»)? */
    private fun isSubsequence(short: String, long: String): Boolean {
        if (short.length < 3) return false
        var i = 0
        for (ch in long) if (i < short.length && short[i] == ch) i++
        return i == short.length
    }

    /** Vergleich zweier Namen desselben Kürzels [symbol]. */
    fun matchNames(symbol: String, a: String?, b: String?): Match {
        val na = normalizeName(a, symbol) ?: return Match.WEAK
        val nb = normalizeName(b, symbol) ?: return Match.WEAK
        val ga = groupOf(symbol, na)
        val gb = groupOf(symbol, nb)
        if (ga != null && gb != null) return if (ga == gb) Match.MEDIUM else Match.MISMATCH
        if (na == nb) return Match.MEDIUM
        val wa = na.split(' ').toSet()
        val wb = nb.split(' ').toSet()
        if (MARKERS.any { (it in wa) != (it in wb) }) return Match.MISMATCH
        val ca = na.replace(" ", "")
        val cb = nb.replace(" ", "")
        val (short, long) = if (ca.length <= cb.length) ca to cb else cb to ca
        if (short.length >= 4 && long.contains(short)) return Match.MEDIUM
        if (wa.any { it.length >= 3 && it in wb } || isSubsequence(short, long)) return Match.WEAK
        return Match.MISMATCH
    }

    /** Vergleich zweier Identitäten desselben Kürzels [symbol]; null = keine Angaben (WEAK). */
    fun match(symbol: String, a: Identity?, b: Identity?): Match {
        val ca = a?.contracts.orEmpty().mapNotNullTo(HashSet()) { normalizedContract(it) }
        val cb = b?.contracts.orEmpty().mapNotNullTo(HashSet()) { normalizedContract(it) }
        if (ca.isNotEmpty() && cb.isNotEmpty()) {
            if (ca.any { it in cb }) return Match.STRONG
            val chains = ca.mapTo(HashSet()) { it.substringBefore(':') }
            if (cb.any { it.substringBefore(':') in chains }) return Match.MISMATCH
        }
        return matchNames(symbol, a?.name, b?.name)
    }

    /** Darf die Reihe/der Kurs der anderen Seite überhaupt geprüft werden (kein klarer Widerspruch)? */
    fun compatible(symbol: String, a: String?, b: String?): Boolean = matchNames(symbol, a, b) != Match.MISMATCH

    /**
     * Börsen-Schlüssel ([NameIndex.markets]) der übrigen Anbieter der Ausweich-Kette — deren Namen aus
     * der eigenen Paarliste, sobald die Börse Namen liefert (Binance-Anbieter: [NameIndex.binance]).
     */
    val PROVIDER_MARKETS = mapOf(
        com.cryptochecker.app.domain.market.DataFreshness.BINANCE_US to "BinanceUs",
        com.cryptochecker.app.domain.market.DataFreshness.COINBASE to "Coinbase",
        com.cryptochecker.app.domain.market.DataFreshness.BYBIT to "Bybit",
    )

    /**
     * Darf eine Kerzenreihe der Ausweich-Kette von [provider] für den Coin [symbol] erscheinen (Mini-Chart,
     * 24-h-Bezug, Widget-Chart)? Nie bei klar anderem Namen ([compatible]): Widerspricht der eigene Name
     * [ownName] dem Binance-Namen [binanceName], steht das Kürzel an den grossen Börsen für einen anderen
     * Token — dann keine Reihe, von keinem Anbieter (auch Binance.US, Coinbase …). Dazu der Name des Anbieters
     * selbst [providerName] (falls bekannt). Unbekannte Namen: Kürzel + Kursband wie bisher. Gleiche Regel
     * wie die Kette bei «Warum?» und Blatt («binanceAllowed»).
     */
    fun fallbackSeriesAllowed(
        symbol: String,
        ownName: String?,
        provider: String?,
        binanceName: String?,
        providerName: String? = null,
    ): Boolean {
        if (provider?.trim() == null) return true
        if (!compatible(symbol, ownName, binanceName)) return false
        return compatible(symbol, ownName, providerName)
    }

    /**
     * Darf die Reihe der Ausweich-Kette von [provider] (eine ANDERE Börse) die %-Veränderung des Paars liefern?
     * Strenger als [fallbackSeriesAllowed]: Kurs des Paars gegen die Eröffnung einer fremden Reihe ist nur
     * sinnvoll, wenn es belegt derselbe Token ist — Kürzel + Kursband (±25 %) allein liessen einen anderen Token
     * oder eine fremde Prämie als Veränderung durch (bis gut 20 Punkte daneben). Verlangt also den eigenen Namen
     * und einen gleichen Namen ([Match.MEDIUM]) beim Anbieter ([providerName], sonst [binanceName]). Unbekannt →
     * false: lieber «—» als ein geratener Wert. Kerzen der eigenen Börse prüft der Aufrufer nicht hiermit.
     */
    fun fallbackChangeAllowed(
        symbol: String,
        ownName: String?,
        provider: String?,
        binanceName: String?,
        providerName: String? = null,
    ): Boolean {
        if (provider?.trim().isNullOrEmpty()) return false
        if (!fallbackSeriesAllowed(symbol, ownName, provider, binanceName, providerName)) return false
        return matchNames(symbol, ownName, providerName ?: binanceName) == Match.MEDIUM
    }

    /**
     * Darf die Quelle [host] der Kerzen-Kette (Aktionsblatt-Chart, Volumen-Reihe) für [symbol] einspringen?
     * Gleiche Regel wie [fallbackSeriesAllowed] mit dem Anbieter des Hosts
     * ([com.cryptochecker.app.domain.market.DataFreshness.candleProvider]): Widerspricht der eigene Name
     * [ownName] dem Binance-Namen, fällt die ganze Kette aus (auch Binance.US, Coinbase) — Gate «CAT» zeigt
     * nie den Chart eines anderen Tokens. [providerName]: Name beim Anbieter selbst (falls bekannt).
     */
    fun chainSourceAllowed(
        symbol: String,
        ownName: String?,
        host: String,
        binanceName: String?,
        providerName: String? = null,
    ): Boolean = fallbackSeriesAllowed(
        symbol, ownName, com.cryptochecker.app.domain.market.DataFreshness.candleProvider(host), binanceName, providerName,
    )

    /** Gleicher Token? STRONG ohne Kursband, MEDIUM/WEAK nur mit [bandOk], MISMATCH nie. */
    fun accept(match: Match, bandOk: Boolean): Boolean = when (match) {
        Match.STRONG -> true
        Match.MEDIUM, Match.WEAK -> bandOk
        Match.MISMATCH -> false
    }

    /** Widersprechen sich bekannte Namen ([names], je Paar) desselben Kürzels? */
    fun conflicting(symbol: String, names: List<String?>): Boolean {
        val known = names.filter { normalizeName(it, symbol) != null }
        for (i in known.indices) for (j in i + 1 until known.size) {
            if (matchNames(symbol, known[i], known[j]) == Match.MISMATCH) return true
        }
        return false
    }

    /** Gemeinsamer Name mehrerer Paare: der erste bekannte, wenn keiner widerspricht; sonst null. */
    fun consensus(symbol: String, names: List<String?>): String? {
        if (conflicting(symbol, names)) return null
        return names.firstOrNull { normalizeName(it, symbol) != null }
    }

    /** Längster gespeicherter Name (Schutz vor unbrauchbaren Einträgen). */
    const val NAME_MAX = 80

    /**
     * Namen je Börse ([markets]: Börsen-Schlüssel → Kürzel → Name, aus der Paarliste) und von Binance
     * ([binance]: Kürzel → `fullName` der Symbolliste). Kürzel gross geschrieben.
     */
    data class NameIndex(
        val markets: Map<String, Map<String, String>> = emptyMap(),
        val binance: Map<String, String> = emptyMap(),
    ) {
        fun pairName(marketKey: String, base: String): String? = markets[marketKey]?.get(key(base))
        fun binanceName(base: String): String? = binance[key(base)]
    }

    fun key(base: String): String = base.trim().uppercase(Locale.ROOT)

    /** Nur brauchbare Einträge: Kürzel und Name nicht leer, Name höchstens [NAME_MAX] Zeichen. */
    fun cleaned(names: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((base, name) in names) {
            val k = key(base)
            val n = name.trim()
            if (k.isEmpty() || n.isEmpty() || n.length > NAME_MAX) continue
            out.putIfAbsent(k, n)
        }
        return out
    }

    fun encode(index: NameIndex): String {
        val markets = org.json.JSONObject()
        index.markets.forEach { (market, names) -> markets.put(market, org.json.JSONObject(names as Map<*, *>)) }
        return org.json.JSONObject()
            .put("v", 1)
            .put("markets", markets)
            .put("binance", org.json.JSONObject(index.binance as Map<*, *>))
            .toString()
    }

    /** Gespeicherter Stand; unlesbar oder leer → leerer Index. */
    fun decode(text: String?): NameIndex {
        if (text.isNullOrBlank()) return NameIndex()
        return runCatching {
            val root = org.json.JSONObject(text)
            fun names(o: org.json.JSONObject?): Map<String, String> {
                if (o == null) return emptyMap()
                val m = LinkedHashMap<String, String>()
                o.keys().forEach { k -> o.optString(k).takeIf { it.isNotEmpty() }?.let { m[k] = it } }
                return cleaned(m)
            }
            val marketsJson = root.optJSONObject("markets")
            val markets = LinkedHashMap<String, Map<String, String>>()
            marketsJson?.keys()?.forEach { k -> names(marketsJson.optJSONObject(k)).takeIf { it.isNotEmpty() }?.let { markets[k] = it } }
            NameIndex(markets, names(root.optJSONObject("binance")))
        }.getOrDefault(NameIndex())
    }
}
