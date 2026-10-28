package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.marketdata.model.exchange.DexScreener

/**
 * Obergrenzen für Merkliste und Paar-Alarme aus einer Sicherung ([BackupManager.restore]) — gleich wie iOS
 * `BackupRestoreLimits` (gemeinsame Fälle `testdata/parity/backup_limits.json`). Eine Datei kann von Hand
 * verändert sein: ohne Grenzen kämen beliebig lange Kennungen oder Hunderttausende Paare in die Datenbank,
 * in Abfragen und Adressen der Börsen.
 *
 * - Paar mit zu langer Börse, Basis, Quote, Börsenname oder Paar-Kennung fällt weg (Kürzen änderte, welches Paar
 *   gemeint ist). Gezählt werden Unicode-Codepunkte, auf beiden Plattformen gleich.
 * - Paar-Kennung nur aus Buchstaben und Ziffern jeder Schrift (Binance führt Coins mit chinesischem Namen) sowie
 *   «/ _ : . -» (DexScreener «chain/adresse», Bitfinex «tBTC:USD», Coinbase «BTC-USD»); Leerzeichen, «@», «&», «?»
 *   u. Ä. kommen in keiner Börse vor und landeten sonst unverändert in einer Adresse.
 * - Basis und Quote wie Symbole ([validAsset]): Buchstaben und Ziffern jeder Schrift sowie «. _ -» («1000PEPE»,
 *   «USDC.e», «BTC-PERP», «币安人生»). Ohne Kennung bauen die Börsen die Adresse daraus («BTC-USD»); «/», «?», «#»,
 *   «&» u. Ä. veränderten sonst Pfad oder Abfrage.
 * - DexScreener-Paar nur mit gültiger Kennung «chain/adresse» ([DexScreener.isSafePairId]); nur diese kommt in die
 *   Adresse, Basis und Quote sind dort Anzeigetext der Börse («USD (ethereum)», «$WIF») und nur begrenzt.
 * - Unbekannte Börsen bleiben (Platzhalter `UnknownExchange`, z. B. eine inzwischen entfernte Börse), nur begrenzt.
 * - Höchstens [MAX_PAIRS] Paare (die ersten der Datei) und je Paar [MAX_ALARMS_PER_PAIR] Alarme.
 */
object BackupRestoreLimits {

    const val MAX_EXCHANGE_KEY = 64
    const val MAX_EXCHANGE_NAME = 60
    const val MAX_ASSET = 30
    const val MAX_PAIR_ID = 128
    /** Gleiche Grenzen wie im Rest der App ([WatchlistLimits]), hier nur weitergereicht. */
    const val MAX_PAIRS = WatchlistLimits.MAX_PAIRS
    const val MAX_ALARMS_PER_PAIR = WatchlistLimits.MAX_ALARMS_PER_PAIR

    /** Darf das Paar aus der Sicherung übernommen werden? */
    fun acceptsWatch(w: WatchEntity): Boolean = acceptsPair(w.exchangeKey, w.exchangeName, w.baseAsset, w.quoteAsset, w.pairId)

    fun acceptsPair(exchangeKey: String, exchangeName: String, base: String, quote: String, pairId: String?): Boolean =
        codePoints(exchangeKey) <= MAX_EXCHANGE_KEY &&
            codePoints(exchangeName) <= MAX_EXCHANGE_NAME &&
            assetAllowed(exchangeKey, base) &&
            assetAllowed(exchangeKey, quote) &&
            (pairId == null || validPairId(pairId)) &&
            // DexScreener nur mit «chain/adresse», die unkodiert in den Pfad darf
            (exchangeKey != DexScreener.KEY || (pairId != null && DexScreener.isSafePairId(pairId)))

    /** Kennung höchstens [MAX_PAIR_ID] Codepunkte, nur Buchstaben/Ziffern jeder Schrift und «/ _ : . -». */
    fun validPairId(pairId: String): Boolean {
        if (codePoints(pairId) > MAX_PAIR_ID) return false
        return pairId.codePoints().allMatch { cp -> (cp < 0x80 && cp.toChar() in PAIR_ID_PUNCTUATION) || isLetterOrDigit(cp) }
    }

    /** Basis bzw. Quote höchstens [MAX_ASSET] Codepunkte, nur Buchstaben/Ziffern jeder Schrift und «. _ -». */
    fun validAsset(asset: String): Boolean {
        if (codePoints(asset) > MAX_ASSET) return false
        return asset.codePoints().allMatch { cp ->
            (cp < 0x80 && cp.toChar() in ASSET_PUNCTUATION) || isLetterOrDigit(cp)
        }
    }

    /** DexScreener: Basis/Quote nur begrenzt (Anzeigetext, nie in der Adresse); sonst wie Symbole ([validAsset]). */
    private fun assetAllowed(exchangeKey: String, asset: String): Boolean =
        if (exchangeKey == DexScreener.KEY) codePoints(asset) <= MAX_ASSET else validAsset(asset)

    /**
     * Was nach [accept] bzw. [limit] übernommen wird; [droppedPairs]/[droppedAlarms] = wie viele wegen der Grenzen
     * wegfallen. Gezählt, damit Rückfrage und Erfolgsmeldung sie nennen — eine Sicherung mit mehr Paaren oder
     * Alarmen als erlaubt verlöre sonst still Daten.
     */
    data class Limited<W, A>(val watches: List<W>, val alarms: List<A>, val droppedPairs: Int = 0, val droppedAlarms: Int = 0)

    /**
     * Paare, die [accepts] ablehnt, weglassen und mit ihnen ihre Alarme (vor der Bereinigung, damit ein ungültiges
     * Paar kein gültiges verdrängt). Alarme ohne Paar in der Datei zählen nicht: die fielen schon immer weg.
     */
    fun <W, A> accept(
        watches: List<W>, alarms: List<A>, accepts: (W) -> Boolean, watchId: (W) -> Long, alarmWatchId: (A) -> Long,
    ): Limited<W, A> {
        val (kept, rejected) = watches.partition(accepts)
        if (rejected.isEmpty()) return Limited(watches, alarms)
        val rejectedIds = rejected.map(watchId).toSet() - kept.map(watchId).toSet()
        val (dropped, keptAlarms) = alarms.partition { alarmWatchId(it) in rejectedIds }
        return Limited(kept, keptAlarms, rejected.size, dropped.size)
    }

    /**
     * Höchstens [MAX_PAIRS] Paare (nach der Bereinigung, damit Doppel keine Plätze belegen) und je Paar die ersten
     * [MAX_ALARMS_PER_PAIR] Alarme; Alarme weggelassener Paare fallen mit weg. Nach der Bereinigung hängt jeder Alarm
     * an einem Paar: jeder weggelassene zählt.
     */
    fun <W, A> limit(watches: List<W>, alarms: List<A>, watchId: (W) -> Long, alarmWatchId: (A) -> Long): Limited<W, A> {
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
        return Limited(kept, keptAlarms, watches.size - kept.size, alarms.size - keptAlarms.size)
    }

    private fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /** Buchstabe (alle L-Kategorien) oder Dezimalziffer — wie die Unicode-Kategorien in Swift. */
    private fun isLetterOrDigit(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt() -> true
        else -> false
    }

    private const val PAIR_ID_PUNCTUATION = "/_:.-"
    private const val ASSET_PUNCTUATION = "._-"
}
