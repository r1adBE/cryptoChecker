package com.cryptochecker.app.domain.portfolio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Zwischenspeicher eines beobachteten xpub/ypub/zpub: abgeleitete Adressen je Kette (0 = Empfang, 1 = Wechselgeld,
 * Index = Position) und die benutzten Indizes (mindestens eine Transaktion). [fullScan] = letzte vollständige Suche
 * (ms, 0 = nie). [script] = gefundener Adresstyp eines xpub (Segwit-Konto mit xpub, [XpubScan.scripts]); null = noch
 * keiner mit Verlauf (bis Build 46 immer null: P2PKH). Gespeichert im JSON der beobachteten Adresse ([WatchedAddressExtras]).
 */
data class XpubCache(
    val receive: List<String> = emptyList(),
    val change: List<String> = emptyList(),
    val usedReceive: List<Int> = emptyList(),
    val usedChange: List<Int> = emptyList(),
    val fullScan: Long = 0,
    val script: Bip32.Script? = null,
) {
    fun addresses(chain: Int): List<String> = if (chain == 0) receive else change
    fun used(chain: Int): List<Int> = if (chain == 0) usedReceive else usedChange

    /** Anzahl benutzter Adressen (Anzeige). */
    val usedCount: Int get() = usedReceive.size + usedChange.size

    companion object {
        /** `{"r":[…],"c":[…],"ru":[…],"cu":[…],"f":…,"s":"P2WPKH"}` («s» nur mit gefundenem Typ). */
        fun toJson(c: XpubCache): JSONObject = JSONObject()
            .put("r", JSONArray(c.receive))
            .put("c", JSONArray(c.change))
            .put("ru", JSONArray(c.usedReceive))
            .put("cu", JSONArray(c.usedChange))
            .put("f", c.fullScan)
            .apply { c.script?.let { put("s", it.name) } }

        /** Lesen; unlesbare Teile = leer (dann sucht die nächste Aktualisierung vollständig). */
        fun fromJson(o: JSONObject?): XpubCache? {
            if (o == null) return null
            fun strings(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }.takeIf { list -> list.all { it.isNotEmpty() } } ?: emptyList()
            }
            fun ints(key: String, max: Int): List<Int> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toInt() }
                    .filter { it in 0 until max }.distinct().sorted()
            }
            val receive = strings("r")
            val change = strings("c")
            return XpubCache(
                receive = receive,
                change = change,
                usedReceive = ints("ru", receive.size),
                usedChange = ints("cu", change.size),
                fullScan = (o.opt("f") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
                // Unbekannter Typ = keiner (die nächste vollständige Suche prüft wieder alle)
                script = (o.opt("s") as? String)?.let { n -> Bip32.Script.entries.firstOrNull { it.name == n } },
            )
        }
    }
}

/**
 * Suche über die Adressen eines xpub nach der Gap-Regel (BIP44: 20 unbenutzte Adressen in Folge beenden eine Kette)
 * — ohne Netz, schrittweise: [next] nennt die nächste Adresse (Kette, Index), [report] nimmt die Antwort
 * (Anzahl Transaktionen, Satoshi). Reine Logik, gleich wie `XpubScan.swift`; der Abruf steht in
 * `WatchedAddressSource`.
 *
 * Arten:
 *  - [Mode.FULL] (neu oder ≥ [FULL_SCAN_MILLIS] seit der letzten): je Kette ab 0 bis [GAP] unbenutzte in Folge.
 *  - [Mode.QUICK] (Vordergrund sonst): bekannte benutzte Adressen + die nächsten [LOOKAHEAD] (= volles Gap-Limit)
 *    nach der letzten benutzten; wird dort eine benutzt, geht die Suche weiter, bis wieder [GAP] unbenutzte in Folge
 *    kommen. Mit nur 5 blieb eine Zahlung an eine weiter hinten abgeleitete Adresse (Wallets geben oft mehrere neue
 *    Adressen auf einmal aus) bis zur täglichen vollständigen Suche unsichtbar.
 *  - [Mode.BACKGROUND]: nur bekannte benutzte Adressen (Zeitbudget).
 * Guthaben = Summe der gefragten Adressen (nicht gefragte, unbenutzte zählen 0). Höchstens [MAX_REQUESTS] Anfragen,
 * sonst Fehler ([TooMany]).
 */
class XpubScan(val mode: Mode, cache: XpubCache?) {

    enum class Mode { FULL, QUICK, BACKGROUND }

    data class Target(val chain: Int, val index: Int)

    class TooMany : Exception("Zu viele Adressen")

    private class ChainState(
        val queue: ArrayDeque<Int>,
        val used: java.util.TreeSet<Int>,
        var gap: Boolean,
        var nextIndex: Int,
        var unusedRun: Int,
        /** Letzte bekannte benutzte vor der Suche (QUICK: Vorausblick dahinter). */
        val lastUsedBefore: Int,
        var lastQueried: Int,
    )

    private val states: List<ChainState>
    private val old: XpubCache = cache ?: XpubCache()
    private var current = 0
    private var pending: Target? = null

    /** Gestellte Anfragen. */
    var requests: Int = 0
        private set

    /** Summe der Satoshi aller gefragten Adressen. */
    var totalSats: Long = 0
        private set

    /** Benutzte Adressen nach der Suche (bekannte und neu gefundene). */
    val usedCount: Int get() = states.sumOf { it.used.size }

    init {
        states = (0..1).map { chain ->
            val known = old.used(chain)
            val last = known.maxOrNull() ?: -1
            when (mode) {
                Mode.FULL -> ChainState(ArrayDeque(), java.util.TreeSet(), gap = true, nextIndex = 0, unusedRun = 0, lastUsedBefore = -1, lastQueried = -1)
                Mode.QUICK -> ChainState(
                    ArrayDeque(known + (last + 1..last + LOOKAHEAD)), java.util.TreeSet(known),
                    gap = false, nextIndex = 0, unusedRun = 0, lastUsedBefore = last, lastQueried = -1,
                )
                Mode.BACKGROUND -> ChainState(ArrayDeque(known), java.util.TreeSet(known), gap = false, nextIndex = 0, unusedRun = 0, lastUsedBefore = last, lastQueried = -1)
            }
        }
    }

    /** Nächste Adresse oder null = fertig. Erst [report], dann wieder [next]. */
    fun next(): Target? {
        pending?.let { return it }
        while (current < states.size) {
            val s = states[current]
            if (s.queue.isNotEmpty()) {
                return Target(current, s.queue.removeFirst()).also { pending = it; count() }
            }
            if (!s.gap && mode == Mode.QUICK) {
                // Vorausblick fertig: neue benutzte dahinter → weiter bis GAP unbenutzte in Folge
                val newest = s.used.lastOrNull() ?: -1
                if (newest > s.lastUsedBefore) {
                    s.gap = true
                    s.nextIndex = s.lastQueried + 1
                    s.unusedRun = s.lastQueried - newest
                }
            }
            if (s.gap && s.unusedRun < GAP) {
                return Target(current, s.nextIndex).also { pending = it; count() }
            }
            current++
        }
        return null
    }

    private fun count() {
        requests++
        if (requests > MAX_REQUESTS) throw TooMany()
    }

    /** Antwort zu [target] (= letztes [next]): benutzt, wenn [txCount] > 0. */
    fun report(target: Target, txCount: Int, sats: Long) {
        require(target == pending) { "Antwort passt nicht" }
        pending = null
        val s = states[target.chain]
        totalSats += sats.coerceAtLeast(0)
        if (target.index > s.lastQueried) s.lastQueried = target.index
        if (txCount > 0) s.used.add(target.index)
        if (s.gap && target.index == s.nextIndex) {
            s.unusedRun = if (txCount > 0) 0 else s.unusedRun + 1
            s.nextIndex++
        }
    }

    /**
     * Neuer Zwischenspeicher nach der Suche: Adressen [addresses] je Kette (vom Aufrufer abgeleitet, mindestens bis
     * zum höchsten gefragten Index), benutzte Indizes, Zeit der vollständigen Suche ([now] bei [Mode.FULL]).
     */
    fun cache(receive: List<String>, change: List<String>, now: Long, script: Bip32.Script? = null): XpubCache = XpubCache(
        receive = receive,
        change = change,
        usedReceive = states[0].used.filter { it < receive.size },
        usedChange = states[1].used.filter { it < change.size },
        fullScan = if (mode == Mode.FULL) now else old.fullScan,
        script = script,
    )

    companion object {
        /** Gap-Limit (BIP44). */
        const val GAP = 20
        /** QUICK: so viele Adressen nach der letzten benutzten je Kette — das volle Gap-Limit (BIP44). */
        const val LOOKAHEAD = GAP
        /** Vollständige Suche höchstens einmal täglich. */
        const val FULL_SCAN_MILLIS = 24 * 60 * 60_000L
        /** Grenze je Aktualisierung (schont die Anbieter). */
        const val MAX_REQUESTS = 400

        /**
         * Art der Suche: Hintergrund nur mit Zwischenspeicher ([Mode.BACKGROUND], sonst null = später im
         * Vordergrund); Vordergrund vollständig ohne Zwischenspeicher, nach einem Tag oder bei zurückgestellter Uhr.
         */
        /**
         * Adresstypen der Suche in dieser Reihenfolge: ypub/zpub und Taproot-Konto nur ihr Typ; xpub mit gemerktem Typ ([XpubCache.script])
         * oder mit benutzten Adressen im Zwischenspeicher bis Build 46 (gefunden als P2PKH) nur dieser; sonst P2PKH,
         * P2SH-P2WPKH, P2WPKH — Ledger Live zeigt die xpub auch für Segwit-Konten. Mehr als ein Typ = vollständig suchen
         * und den ersten mit benutzter Adresse behalten ([pickScript]). [cache] muss zum Schlüssel gehören.
         */
        fun scripts(key: Bip32.ExtendedKey, cache: XpubCache?): List<Bip32.Script> {
            if (!key.detectsScript) return listOf(key.script)
            cache?.script?.let { return listOf(it) }
            if (cache != null && cache.usedCount > 0) return listOf(Bip32.Script.P2PKH)
            return listOf(Bip32.Script.P2PKH, Bip32.Script.P2SH_P2WPKH, Bip32.Script.P2WPKH)
        }

        /**
         * Zu merkender Typ nach der Suche über [tried] (je Typ: benutzte Adressen): der erste mit benutzter Adresse; bei
         * nur einem Typ dieser (xpub; ypub/zpub nichts zu merken: null); keiner mit Verlauf = null (wieder alle prüfen).
         */
        fun pickScript(key: Bip32.ExtendedKey, tried: List<Pair<Bip32.Script, Int>>): Bip32.Script? {
            if (!key.detectsScript) return null
            tried.firstOrNull { it.second > 0 }?.let { return it.first }
            return tried.singleOrNull()?.first
        }

        fun modeFor(cache: XpubCache?, now: Long, background: Boolean): Mode? {
            val hasCache = cache != null && cache.fullScan > 0
            if (background) return if (hasCache) Mode.BACKGROUND else null
            if (!hasCache) return Mode.FULL
            val elapsed = now - cache.fullScan
            return if (elapsed < 0 || elapsed >= FULL_SCAN_MILLIS) Mode.FULL else Mode.QUICK
        }
    }
}
