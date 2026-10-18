package com.cryptochecker.app.domain.logos

import com.cryptochecker.app.domain.mirror.DataMirror

/**
 * Namen, Alpha-Kennzeichen und Bild-Adressen der Coins: Zusammenführen der Listen und Ersatz, wenn
 * die Binance-Listen direkt nicht kommen (Build 46). Reines Kotlin, gleich in iOS `CoinMeta.swift`
 * (gemeinsame Testfälle `testdata/parity/coin_meta.json`).
 *
 * - [merge]: Rangliste (CoinGecko), Binance-Symbolliste und Alpha-Liste → Logo-Adressen, Namen und
 *   Alpha-Kürzel, mit derselben Vorrangregel wie bisher (CoinGecko Rang 1–1000 → Binance → Alpha →
 *   CoinGecko Rang 1001–2500; erstes Vorkommen gewinnt).
 * - Ersatz [URL]: `coin_meta.json` der Release «data» auf GitHub, stündlich von der GitHub Action
 *   «Market data» gebaut — **nur** aus den öffentlichen Binance-Listen (Kürzel, Namen, Bild-Adressen,
 *   Alpha-Kennzeichen; keine Bilder, keine CoinGecko-Daten). Die App holt sie nur, wenn die direkte
 *   Binance- oder Alpha-Liste ausfällt ([needed], [CoinLists]); Bilder kommen weiter direkt von
 *   `bin.bnbstatic.com`.
 * - Mitgeliefert [SNAPSHOT_FILE]: kleiner Auszug derselben Datei (Binance-Kürzel und Alpha-Token, ohne
 *   Bild-Adressen; erzeugt mit `android/tools/gen_coin_assets.py`), damit eine frische Installation ohne
 *   Netz oder bei ausgefallenen Quellen sofort Namen und Alpha-Kennzeichen zeigt.
 *
 * Datei: `{"v":1,"generated":<ms>,"binance":[{"name","fullName","logo","tags","onlyFutures"}…],
 * "alpha":[{"symbol","name","iconUrl","chainId","contractAddress","marketCap","offline","listingCex"}…]}`
 * — dieselben Felder wie die Binance-Antworten, gelesen mit denselben Regeln.
 */
object CoinMeta {
    const val FILE = "coin_meta.json"
    const val URL = DataMirror.BASE + FILE

    /** Mitgelieferter Auszug (Android `assets/`, iOS `Shared/Resources`). */
    const val SNAPSHOT_FILE = "coin_meta_snapshot.json"

    const val VERSION = 1

    /** Ersatzdatei höchstens so alt (Action ausgefallen: lieber Initialen als uralte Alpha-Liste). */
    const val MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000

    /**
     * Direkt geholte Binance-Listen gelten so lange; älter (oder gescheitert) → Ersatzdatei. Da die
     * Listen höchstens einen Tag (unvollständig 6 h) gelten, greift das praktisch nur nach Fehlschlägen.
     */
    const val DIRECT_STALE_MILLIS = 48L * 60 * 60 * 1000

    /** Zeitpunkt in der Zukunft bis zu so viel gilt noch (Uhren gehen nicht genau gleich). */
    private const val CLOCK_SKEW_MILLIS = 5L * 60 * 1000

    /** Zeile der CoinGecko-Rangliste (`/coins/markets`): Kürzel, Bild, Name. */
    data class GeckoRow(val symbol: String, val image: String?, val name: String?)

    /** Gelesene Ersatzdatei; [generatedAt] in ms (0 = unbekannt, nur beim mitgelieferten Auszug). */
    data class Meta(
        val generatedAt: Long,
        val binance: List<CoinLogos.BinanceEntry>,
        val alpha: List<CoinLogos.AlphaEntry>,
    )

    /** Ergebnis von [merge]; [alpha] = null, wenn keine Alpha-Liste vorlag (bisherige bleiben). */
    data class Lists(
        val map: Map<String, String>,
        val names: Map<String, String>,
        val alpha: Set<String>?,
    )

    /** Seite der Rangliste (JSON-Array) → Zeilen; null = unlesbar. */
    fun parseMarketsPage(text: String?): List<GeckoRow>? {
        if (text.isNullOrBlank()) return null
        val array = try {
            org.json.JSONArray(text)
        } catch (e: org.json.JSONException) {
            return null
        }
        val out = ArrayList<GeckoRow>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out += GeckoRow(
                symbol = o.optString("symbol"),
                image = if (o.isNull("image")) null else o.optString("image"),
                name = if (o.isNull("name")) null else o.optString("name"),
            )
        }
        return out
    }

    /** Antwort der Binance-Symbolliste (`{"data":[…]}`) → Einträge; null = unbrauchbar (kein `data`-Array). */
    fun parseBinanceList(text: String?): List<CoinLogos.BinanceEntry>? {
        if (text.isNullOrBlank()) return null
        val array = try {
            org.json.JSONObject(text).optJSONArray("data")
        } catch (e: org.json.JSONException) {
            null
        } ?: return null
        return binanceEntries(array)
    }

    /** Einträge eines Binance-`data`-Arrays; `name` leer → `baseAsset`. */
    fun binanceEntries(array: org.json.JSONArray): List<CoinLogos.BinanceEntry> =
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()
            CoinLogos.BinanceEntry(
                name = o.optString("name").ifEmpty { o.optString("baseAsset") },
                logo = if (o.isNull("logo")) null else o.optString("logo"),
                tags = tags,
                onlyFutures = o.optBoolean("onlyFutures", false),
                fullName = if (o.isNull("fullName")) null else o.optString("fullName"),
            )
        }

    /**
     * Ersatzdatei bzw. mitgelieferter Auszug → [Meta]; null bei falscher Version, unlesbar oder ohne
     * jeden Eintrag. Einzelne kaputte Einträge fallen weg (wie bei den Binance-Antworten).
     */
    fun parse(text: String?): Meta? {
        if (text.isNullOrBlank()) return null
        val root = try {
            org.json.JSONObject(text)
        } catch (e: org.json.JSONException) {
            return null
        }
        if (root.optInt("v", -1) != VERSION) return null
        val binance = root.optJSONArray("binance")?.let { binanceEntries(it) }.orEmpty()
        val alpha = root.optJSONArray("alpha")?.let { CoinLogos.alphaEntries(it) }.orEmpty()
        if (binance.isEmpty() && alpha.isEmpty()) return null
        return Meta(root.optLong("generated", 0L).coerceAtLeast(0L), binance, alpha)
    }

    /** Ersatzdatei aus dem Netz brauchbar? Erzeugt vor höchstens [MAX_AGE_MILLIS], nicht in der Zukunft. */
    fun isUsable(meta: Meta, now: Long): Boolean =
        meta.generatedAt > 0 && meta.generatedAt <= now + CLOCK_SKEW_MILLIS && now - meta.generatedAt < MAX_AGE_MILLIS

    /**
     * Ersatzdatei holen? Wenn die direkte Binance- bzw. Alpha-Liste in diesem Abgleich fehlte
     * ([directOk] false) oder ihr letzter direkter Erfolg ([lastDirectAt], ms) älter als
     * [DIRECT_STALE_MILLIS] ist.
     */
    fun needed(directOk: Boolean, lastDirectAt: Long, now: Long): Boolean =
        !directOk || !CoinLogos.isFresh(lastDirectAt, now, DIRECT_STALE_MILLIS)

    /**
     * Listen zusammenführen, Vorrang wie bisher: [geckoTop] (Rang 1–1000), dann Binance (Lücken,
     * TradFi gegen die Kürzel von [geckoTop]), dann Alpha (Lücken), zuletzt [geckoExtra]. Fehlende
     * Quellen (null bzw. leer) überspringen.
     */
    fun merge(
        geckoTop: List<GeckoRow>,
        binance: List<CoinLogos.BinanceEntry>?,
        alpha: List<CoinLogos.AlphaEntry>?,
        geckoExtra: List<GeckoRow> = emptyList(),
        crypto: Set<String>? = null,
    ): Lists {
        val map = LinkedHashMap<String, String>()
        val names = LinkedHashMap<String, String>()
        CoinLogos.pick(geckoTop.map { it.symbol to it.image }, map)
        CoinLogos.pickNames(geckoTop.map { it.symbol to it.name }, names)
        // Krypto-Kürzel für die TradFi-Regel: CoinGecko, sonst (nur Binance-Listen) [listCrypto]
        val cryptoKeys = crypto ?: map.keys.toSet()
        if (binance != null) {
            CoinLogos.pick(binance.map { it.name to it.logo }, map)
            CoinLogos.pickTradFi(binance, cryptoKeys, map)
            CoinLogos.pickNames(binance.map { it.name to it.fullName }, names)
            CoinLogos.pickTradFiNames(binance, cryptoKeys, names)
        }
        val alphaSymbols = alpha?.let { CoinLogos.applyAlpha(it, map, names) }
        CoinLogos.pick(geckoExtra.map { it.symbol to it.image }, map)
        CoinLogos.pickNames(geckoExtra.map { it.symbol to it.name }, names)
        return Lists(map, names, alphaSymbols)
    }

    /**
     * Krypto-Kürzel, wenn keine CoinGecko-Rangliste vorliegt (Namen sofort nach den Binance-Listen):
     * die Spot-Einträge der Binance-Liste (nicht nur Futures, keine tokenisierten Aktien), gross. So
     * bekommt ein TradFi-Paar «CAT» nicht den Namen des gleichnamigen Krypto-Tokens.
     */
    fun listCrypto(binance: List<CoinLogos.BinanceEntry>?): Set<String> =
        binance.orEmpty()
            .filter { e -> !e.onlyFutures && e.tags.none { it.equals(CoinLogos.STOCK_TAG, ignoreCase = true) } }
            .mapTo(LinkedHashSet()) { it.name.trim().uppercase() }

    /** Namen nur aus den Binance-Listen (ohne Rangliste), Vorrang wie [merge]. */
    fun listNames(binance: List<CoinLogos.BinanceEntry>?, alpha: List<CoinLogos.AlphaEntry>?): Map<String, String> =
        merge(emptyList(), binance, alpha, crypto = listCrypto(binance)).names

    /**
     * [Meta] als Datei (gleiches Format wie `coin_meta.json`; die App speichert so die zuletzt geholten
     * Binance-Listen). Nur die Felder, die die App liest.
     */
    fun encode(meta: Meta): String {
        val root = org.json.JSONObject()
        root.put("v", VERSION)
        root.put("generated", meta.generatedAt)
        val binance = org.json.JSONArray()
        for (e in meta.binance) {
            val o = org.json.JSONObject()
            o.put("name", e.name)
            e.fullName?.let { o.put("fullName", it) }
            e.logo?.let { o.put("logo", it) }
            if (e.tags.isNotEmpty()) o.put("tags", org.json.JSONArray(e.tags))
            if (e.onlyFutures) o.put("onlyFutures", true)
            binance.put(o)
        }
        root.put("binance", binance)
        val alpha = org.json.JSONArray()
        for (e in meta.alpha) {
            val o = org.json.JSONObject()
            o.put("symbol", e.symbol)
            e.name?.let { o.put("name", it) }
            e.icon?.let { o.put("iconUrl", it) }
            e.marketCap?.takeIf { it.isFinite() }?.let { o.put("marketCap", it) }
            if (e.offline) o.put("offline", true)
            if (e.listingCex) o.put("listingCex", true)
            e.alphaId?.let { o.put("alphaId", it) }
            alpha.put(o)
        }
        root.put("alpha", alpha)
        return root.toString()
    }

    /**
     * Binance-Liste aus der Ersatzdatei gegen die gespeicherte [saved] abwägen: Die Ersatzdatei kann nur
     * Kürzel tragen (ohne `fullName`, Bild-Adressen, Schlagworte). Eine Liste ohne Namen ersetzt nie eine
     * mit Namen — sonst wären nach 48 h ohne direkte Liste Namen und Logos weg —, sie ergänzt nur Kürzel,
     * die dort fehlen (hinten angehängt). Trägt die Ersatzdatei Namen oder [saved] keine, gilt sie ganz.
     */
    fun mirrorBinance(mirror: List<CoinLogos.BinanceEntry>, saved: List<CoinLogos.BinanceEntry>): List<CoinLogos.BinanceEntry> {
        if (hasNames(mirror) || !hasNames(saved)) return mirror
        val known = saved.mapTo(HashSet()) { it.name.trim().uppercase() }
        return saved + mirror.filter { known.add(it.name.trim().uppercase()) }
    }

    /** Trägt mindestens ein Eintrag einen Namen (`fullName`)? */
    fun hasNames(entries: List<CoinLogos.BinanceEntry>): Boolean = entries.any { !it.fullName.isNullOrBlank() }

    /**
     * Binance-Namen für «gleicher Token» (K-8, [com.cryptochecker.app.domain.watch.TokenIdentity]):
     * Kürzel gross → `fullName`, Spot vor Futures.
     */
    fun binanceFullNames(entries: List<CoinLogos.BinanceEntry>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        (entries.filter { !it.onlyFutures } + entries.filter { it.onlyFutures }).forEach { e ->
            e.fullName?.let { out.putIfAbsent(e.name.trim().uppercase(), it) }
        }
        return out
    }
}
