package com.cryptochecker.app.domain.portfolio

import java.text.Normalizer
import java.util.Locale

/**
 * Namen von Börsen/Wallets bei der Eingabe (Umbuchung «Von»/«Nach»). Bei FIFO je Wallet ist
 * jeder anders geschriebene Name ([PortfolioWallets.key]) eine eigene Wallet — «Binanse» statt «Binance»
 * hätte einen zweiten Topf. Deshalb: bekannte Namen vorschlagen ([suggestions]) und bei einem fast
 * gleichen Namen nachfragen ([nearDuplicate], «Meintest du Binance?»).
 * Vergleich ohne Gross/klein, Leerzeichen und Akzente ([fold]); Tippfehler über die Editierdistanz.
 * Reines Kotlin, wie iOS `PortfolioWalletNames.swift`.
 */
object PortfolioWalletNames {

    /** Höchstens so viele Vorschläge. */
    const val MAX_SUGGESTIONS = 8

    private val MARKS = Regex("\\p{Mn}+")

    /** Vergleichsform: ohne Akzente (NFD, Zeichen ohne Breite weg), klein, ohne Leerraum. */
    fun fold(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .filterNot { it.isWhitespace() }
            .lowercase(Locale.ROOT)

    /**
     * Bekannte Namen: getrimmt, ohne leere, je Vergleichsform nur die erste Schreibweise (Reihenfolge bleibt —
     * zuerst die selbst benutzten, dann die Börsen der App).
     */
    fun known(names: Iterable<String?>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in names) {
            val name = raw?.trim().orEmpty()
            if (name.isEmpty()) continue
            if (seen.add(fold(name))) out += name
        }
        return out
    }

    /**
     * Vorschläge als Chips: ohne Eingabe die selbst benutzten Namen [used]; mit Eingabe aus [used] und [catalog]
     * zuerst «beginnt mit», dann «enthält» (Vergleichsform). Der schon getippte Name selbst nicht.
     */
    fun suggestions(used: List<String>, catalog: List<String>, query: String, limit: Int = MAX_SUGGESTIONS): List<String> {
        val q = fold(query.trim())
        if (q.isEmpty()) return known(used).take(limit)
        val all = known(used + catalog).filter { fold(it) != q }
        val starts = all.filter { fold(it).startsWith(q) }
        val contains = all.filter { val f = fold(it); !f.startsWith(q) && f.contains(q) }
        return (starts + contains).take(limit)
    }

    /**
     * Fast gleicher bekannter Name zu [input] («Binanse» → «Binance», «Bin ance» → «Binance», «Ledgér» → «Ledger»);
     * null, wenn leer, schon bekannt (gleiche Wallet: [PortfolioWallets.key]) oder nichts nah genug. Erst gleiche
     * Vergleichsform, sonst die kleinste Editierdistanz ≤ [maxDistance] (bei Gleichstand der erste in [known]).
     */
    fun nearDuplicate(input: String, known: List<String>): String? {
        val name = input.trim()
        if (name.isEmpty()) return null
        val key = PortfolioWallets.key(name)
        if (known.any { PortfolioWallets.key(it) == key }) return null
        val f = fold(name)
        if (f.isEmpty()) return null
        known.firstOrNull { fold(it) == f }?.let { return it }
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in known) {
            val c = fold(candidate)
            if (c.isEmpty()) continue
            val limit = maxDistance(minOf(f.length, c.length))
            if (limit == 0 || kotlin.math.abs(f.length - c.length) > limit) continue
            val d = distance(f, c)
            if (d in 1..limit && d < bestDistance) {
                best = candidate
                bestDistance = d
            }
        }
        return best
    }

    /** Erlaubte Tippfehler je Länge: unter 4 Zeichen keine (sonst «OKX» ≈ «BTC»), bis 5 einer, sonst zwei. */
    fun maxDistance(length: Int): Int = when {
        length < 4 -> 0
        length <= 5 -> 1
        else -> 2
    }

    /** Levenshtein-Distanz (Einfügen, Löschen, Ersetzen je 1). */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
