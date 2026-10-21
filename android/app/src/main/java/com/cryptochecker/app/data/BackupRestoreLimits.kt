package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchlistLimits

/**
 * Obergrenzen für Merkliste und Paar-Alarme aus einer Sicherung ([BackupManager.restore]) — gleich wie iOS
 * `BackupRestoreLimits` (gemeinsame Fälle `testdata/parity/backup_limits.json`). Eine Datei kann von Hand
 * verändert sein: ohne Grenzen kämen beliebig lange Kennungen oder Hunderttausende Paare in die Datenbank,
 * in Abfragen und Adressen der Börsen. Seit Build 99.
 *
 * - Paar mit zu langer Börse, Basis, Quote, Börsenname oder Paar-Kennung fällt weg (Kürzen änderte, welches Paar
 *   gemeint ist). Gezählt werden Unicode-Codepunkte, auf beiden Plattformen gleich.
 * - Paar-Kennung nur aus Buchstaben und Ziffern jeder Schrift (Binance führt Coins mit chinesischem Namen) sowie
 *   «/ _ : . -» (DexScreener «chain/adresse», Bitfinex «tBTC:USD», Coinbase «BTC-USD»); Leerzeichen, «@», «&», «?»
 *   u. Ä. kommen in keiner Börse vor und landeten sonst unverändert in einer Adresse.
 * - Unbekannte Börsen bleiben (Platzhalter `UnknownMarket`, z. B. eine inzwischen entfernte Börse), nur begrenzt.
 * - Höchstens [MAX_PAIRS] Paare (die ersten der Datei) und je Paar [MAX_ALARMS_PER_PAIR] Alarme.
 */
internal object BackupRestoreLimits {

    const val MAX_MARKET_KEY = 64
    const val MAX_MARKET_NAME = 60
    const val MAX_ASSET = 30
    const val MAX_PAIR_ID = 128
    /** Gleiche Grenzen wie im Rest der App ([WatchlistLimits]), hier nur weitergereicht. */
    const val MAX_PAIRS = WatchlistLimits.MAX_PAIRS
    const val MAX_ALARMS_PER_PAIR = WatchlistLimits.MAX_ALARMS_PER_PAIR

    /** Darf das Paar aus der Sicherung übernommen werden? */
    fun acceptsWatch(w: WatchEntity): Boolean = acceptsPair(w.marketKey, w.marketName, w.baseAsset, w.quoteAsset, w.pairId)

    fun acceptsPair(marketKey: String, marketName: String, base: String, quote: String, pairId: String?): Boolean =
        codePoints(marketKey) <= MAX_MARKET_KEY &&
            codePoints(marketName) <= MAX_MARKET_NAME &&
            codePoints(base) <= MAX_ASSET &&
            codePoints(quote) <= MAX_ASSET &&
            (pairId == null || validPairId(pairId))

    /** Kennung höchstens [MAX_PAIR_ID] Codepunkte, nur Buchstaben/Ziffern jeder Schrift und «/ _ : . -». */
    fun validPairId(pairId: String): Boolean {
        if (codePoints(pairId) > MAX_PAIR_ID) return false
        return pairId.codePoints().allMatch { cp -> (cp < 0x80 && cp.toChar() in PAIR_ID_PUNCTUATION) || isLetterOrDigit(cp) }
    }

    /**
     * Höchstens [MAX_PAIRS] Paare (nach der Bereinigung, damit Doppel keine Plätze belegen) und je Paar die ersten
     * [MAX_ALARMS_PER_PAIR] Alarme; Alarme weggelassener Paare fallen mit weg.
     */
    fun <W, A> limit(watches: List<W>, alarms: List<A>, watchId: (W) -> Long, alarmWatchId: (A) -> Long): Pair<List<W>, List<A>> {
        val kept = watches.take(MAX_PAIRS)
        val keptIds = kept.map(watchId).toSet()
        val perPair = HashMap<Long, Int>()
        val keptAlarms = alarms.filter { a ->
            val id = alarmWatchId(a)
            if (id !in keptIds) return@filter false
            val n = perPair.getOrDefault(id, 0)
            perPair[id] = n + 1
            n < MAX_ALARMS_PER_PAIR
        }
        return kept to keptAlarms
    }

    private fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /** Buchstabe (alle L-Kategorien) oder Dezimalziffer — wie die Unicode-Kategorien in Swift. */
    private fun isLetterOrDigit(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt() -> true
        else -> false
    }

    private const val PAIR_ID_PUNCTUATION = "/_:.-"
}
