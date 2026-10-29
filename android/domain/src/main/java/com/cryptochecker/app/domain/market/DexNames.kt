package com.cryptochecker.app.domain.market

import java.util.Locale

/**
 * DEX-Namen in der Coin-Suche von «Paar hinzufügen»: Wer «Uniswap» oder «raydium pepe» tippt, bekommt DexScreener als
 * Treffer (DEX-Pools gibt es nur dort). Reine Logik — wie iOS `DexNames` (`testdata/parity/dex_names.json`).
 *
 * Regel: Suchtext ohne Gross-/Kleinschreibung, in Wörter zerlegt. Die ersten ein oder zwei Wörter, ohne Leerzeichen
 * zusammengesetzt, müssen der Anfang eines Namens sein (mindestens [MIN_PREFIX] Zeichen; «trader joe» = «traderjoe»);
 * zwei Wörter gehen vor einem. Der Rest ist der Token für die DEX-Suche («uniswap pepe» → «pepe»).
 */
object DexNames {

    /** Dezentrale Börsen in kanonischer Schreibweise; der Hinweis nennt den getroffenen Namen. */
    val NAMED: List<String> = listOf(
        "Uniswap", "PancakeSwap", "Raydium", "Aerodrome", "SushiSwap", "Orca", "Meteora", "Curve", "Balancer",
        "Trader Joe", "Jupiter",
    )

    /** Treffer mit allgemeinem Hinweis («Pools dezentraler Börsen über DexScreener»). */
    val GENERIC: List<String> = listOf("GeckoTerminal", "DexScreener", "DEX")

    /** So viele Zeichen muss der Anfang mindestens haben («uni» ja, «un» nein). */
    const val MIN_PREFIX = 3

    /** Höchstens so viele Wörter bilden den Namen («Trader Joe»). */
    private const val MAX_NAME_WORDS = 2

    /** Treffer: [dexName] kanonisch oder null (allgemein), [token] der übrige Suchtext (leer = nichts vorbelegen). */
    data class Match(val dexName: String?, val token: String)

    private val ALL = NAMED.map { it to false } + GENERIC.map { it to true }

    private fun key(text: String): String = text.lowercase(Locale.ROOT).replace(" ", "")

    /** DEX zum Suchtext; null = kein DEX-Name. */
    fun match(query: String): Match? {
        // Jedes Leerzeichen trennt (auch geschütztes und ideografisches), wie `Character.isWhitespace` in Swift
        val words = query.map { if (it.isWhitespace()) ' ' else it }.joinToString("").split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        for (take in minOf(MAX_NAME_WORDS, words.size) downTo 1) {
            val head = key(words.take(take).joinToString(""))
            if (head.length < MIN_PREFIX) continue
            val (name, generic) = ALL.firstOrNull { key(it.first).startsWith(head) } ?: continue
            return Match(if (generic) null else name, words.drop(take).joinToString(" "))
        }
        return null
    }
}
