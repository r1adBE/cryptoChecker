package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser.Header
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser.Role

// CSV-Import ([CsvImportParser]): Format aus der Kopfzeile erkennen und Arten aus dem Text einer Zeile einordnen.

internal fun CsvImportParser.detect(h: Header): CsvImportFormat {
    val names = h.names.toSet()
    // Kraken «Ledgers»-Export: txid, refid, time, type, subtype, aclass, asset, (wallet,) amount, fee, balance
    if ("refid" in names && "txid" in names && "asset" in names && "amount" in names && ("aclass" in names || "balance" in names)) {
        return CsvImportFormat.KRAKEN
    }
    // Börsen-Exporte mit eigenem Aufbau vor der allgemeinen Erkennung (Coinbase hätte auch Datum/Art/Coin)
    if (CsvImportBinance.matches(names)) return CsvImportFormat.BINANCE
    if (CsvImportCoinbase.matches(names)) return CsvImportFormat.COINBASE
    if (CsvImportBitpanda.matches(names)) return CsvImportFormat.BITPANDA
    val hasAmount = h.has(Role.AMOUNT) || h.has(Role.AMOUNT_SATS)
    if (!h.has(Role.DATE) || !h.has(Role.KIND) || !hasAmount) return CsvImportFormat.UNKNOWN
    val relai = names.any { it in setOf("btcamount", "btcprice", "satsamount", "amountsats") } ||
        ("operationid" in names && "counterparty" in names)
    if (relai) return CsvImportFormat.RELAI
    val order = names.any { it in setOf("order", "auftrag", "ordre") }
    if (h.has(Role.ISIN) && order && h.has(Role.PRICE)) return CsvImportFormat.SWISSQUOTE
    if (h.has(Role.COIN)) return CsvImportFormat.GENERIC
    return CsvImportFormat.UNKNOWN
}

/** Wörter einer Art: wie [normalize], aber an allem ausser a–z/0–9 getrennt («Staking-Belohnung» → staking, belohnung). */
internal fun CsvImportParser.words(text: String): List<String> {
    val sb = StringBuilder()
    for (ch in text) {
        val n = normalize(ch.toString())
        sb.append(n.ifEmpty { " " })
    }
    return sb.split(' ').filter { it.isNotEmpty() }
}

/**
 * Muster gegen [words]: «wort» genau, «wort*» Anfang, «*wort» Ende, «*wort*» enthalten (zusammengesetzte Wörter wie
 * «Depotgebühren»), «zwei wörter» nacheinander oder zusammengeschrieben («Transfer out», «TransferOut»).
 */
private fun CsvImportParser.matches(words: List<String>, pattern: String): Boolean {
    if (' ' in pattern) {
        val parts = pattern.split(' ')
        if (words.any { it == parts.joinToString("") }) return true
        return (0..words.size - parts.size).any { i -> parts.indices.all { k -> words[i + k] == parts[k] } }
    }
    val core = pattern.trim('*')
    val head = pattern.startsWith('*')
    val tail = pattern.endsWith('*')
    return words.any { w ->
        when {
            head && tail -> w.contains(core)
            tail -> w.startsWith(core)
            head -> w.endsWith(core)
            else -> w == core
        }
    }
}

private val UNSTAKE = listOf("unstak*", "redeem*", "redemption*", "entstak*", "stake", "staked")
private val STAKING = listOf("staking*", "stake", "staked")
private val MOVE = listOf(
    "withdraw*", "*auszahlung*", "retrait*", "deposit*", "*einzahlung*", "depot", "transfer*", "send*", "receive*",
    "unlock*", "lock*",
)
private val SWAP = listOf("*tausch*", "swap*", "convert*", "conversion*", "konvert*", "echange*")
private val SELL = listOf("sell*", "sold", "*verkauf*", "vente*", "vendu", "vendita")
private val BUY = listOf(
    "buy*", "bought", "*kauf*", "achat*", "purchase*", "savingsplan*", "savings plan", "sparplan*", "dca", "recurring*",
    "acquisto",
)
private val REWARD = listOf(
    "reward*", "*belohnung*", "praemie*", "bonus*", "referral*", "cashback*", "airdrop*", "interest*", "interet*",
    "recompense*",
)
private val FEE = listOf("fee", "fees", "*gebuehr*", "frais")
private val WITHDRAWAL = listOf(
    "withdraw*", "send*", "sent", "*auszahlung*", "transfer out", "retrait*", "outgoing", "*ausgang*", "envoi*",
)
private val DEPOSIT = listOf(
    "deposit*", "receive*", "*einzahlung*", "transfer in", "depot", "incoming", "*eingang*", "reception*",
)

/**
 * Art aus dem Text der Datei (Wörter statt Teiltext, feste Reihenfolge); null = nicht unterstützt (Dividende, Forex …):
 *  1. Eigene Mittel zurück aus Staking/Earn («Unstaking», «Redeem», «Staking withdrawal», «Auszahlung Staking») →
 *     [CsvImportKind.TRANSFER] (kein Gewinn, keine Belohnung); mit Belohnungswort bleibt es eine Belohnung.
 *  2. Tausch, Verkauf, Kauf wie bisher.
 *  3. Belohnung («Staking reward», «Staking-Belohnung», «Interest», «Airdrop», «Staking» allein).
 *  4. Gebühr, Auszahlung («Withdraw», «Send», «Retrait»), Einzahlung («Deposit», «Receive»).
 */
fun CsvImportParser.classify(text: String): CsvImportKind? {
    val w = words(text)
    if (w.isEmpty()) return null
    fun any(patterns: List<String>) = patterns.any { matches(w, it) }
    val reward = any(REWARD)
    return when {
        !reward && (any(UNSTAKE) || (any(STAKING) && any(MOVE))) -> CsvImportKind.TRANSFER
        any(SWAP) -> CsvImportKind.SWAP
        any(SELL) -> CsvImportKind.SELL
        any(BUY) -> CsvImportKind.BUY
        reward || any(STAKING) -> CsvImportKind.REWARD
        any(FEE) -> CsvImportKind.FEE
        any(WITHDRAWAL) -> CsvImportKind.WITHDRAWAL
        any(DEPOSIT) -> CsvImportKind.DEPOSIT
        else -> null
    }
}
