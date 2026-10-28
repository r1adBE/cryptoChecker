package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.asOrgJson
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImport
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import com.cryptochecker.app.data.local.model.NOTE_MAX
import com.cryptochecker.app.util.TextLimit
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * JSON-Form von Portfolio-Transaktionen und -Alarmen — gleiches Format wie in der eigenen
 * Sicherung (BackupManager, auch iOS) und in der Kopie für die Systemsicherung ([PortfolioBackupMirror]).
 */
object PortfolioJson {

    fun txToJson(t: PortfolioTxEntity): JSONObject = JSONObject()
        .put("id", t.id)
        .put("coin", t.coin)
        .put("type", t.type.name)
        // Zahlen exakt als Text («amountText»: «0.00000001»)
        .put("amountText", PortfolioDecimal.text(t.amount))
        .put("priceText", t.priceUsdt?.let { PortfolioDecimal.priceText(it) } ?: JSONObject.NULL)
        .put("time", t.time)
        .put("note", t.note ?: JSONObject.NULL)
        // Gebühr und Börse; fehlen = keine Angabe
        .put("feeText", t.fee?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        .put("feeUnit", t.fee?.let { PortfolioFeeUnit.fromName(t.feeUnit).name } ?: JSONObject.NULL)
        .put("exchange", t.exchange ?: JSONObject.NULL)
        // Umbuchung und Gebühr im dritten Coin; fehlen = keine Angabe. Nur exakter Text
        .put("transferFrom", t.transferFrom ?: JSONObject.NULL)
        .put("transferTo", t.transferTo ?: JSONObject.NULL)
        .put("feeCoin", t.feeCoin ?: JSONObject.NULL)
        .put("feeCoinAmountText", t.feeCoinAmount?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        .put("feeCoinPriceText", t.feeCoinPriceUsdt?.let { PortfolioDecimal.priceText(it) } ?: JSONObject.NULL)
        // CSV-Import: Herkunft der Zeile — nach dem Wiederherstellen weiterhin als doppelt erkannt
        .put("importKey", t.importKey ?: JSONObject.NULL)
        // Einzahlung: ursprüngliches Kaufdatum; fehlt = Datum der Einzahlung
        .put("acquiredTime", t.acquiredTime?.takeIf { t.type == PortfolioTxType.DEPOSIT } ?: JSONObject.NULL)
        // Tausch: erhaltener Coin, Menge und Wert (exakt als Text)
        .put("toCoin", t.toCoin?.takeIf { t.type == PortfolioTxType.SWAP } ?: JSONObject.NULL)
        .put("toAmountText", t.toAmount?.takeIf { t.type == PortfolioTxType.SWAP }?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        .put("swapValueText", t.swapValueUsdt?.takeIf { t.type == PortfolioTxType.SWAP }?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        // CSV-Importlauf: «Import rückgängig» auch nach dem Wiederherstellen
        .put("importBatch", t.importBatch ?: JSONObject.NULL)
        // Partner-Merker und Verknüpfung (zweiter Herkunftsschlüssel, Zustand davor) als eigene Felder
        .put("giftToSpouse", t.giftToSpouse && t.type == PortfolioTxType.GIFT)
        .put("linkedImportKey", t.linkedImportKey ?: JSONObject.NULL)
        .put("importRestoreState", t.importRestoreState?.toJson()?.asOrgJson() ?: JSONObject.NULL)
        // Gebühr-Zeile, Zins und Staking-Umbuchung als eigene Merker
        .put("feeRow", t.feeRow && t.type == PortfolioTxType.SELL)
        .put("interest", t.interest && t.type == PortfolioTxType.REWARD)
        .put("stakingMove", t.stakingMove && t.type == PortfolioTxType.TRANSFER)

    /** Gelesene Einträge und wie viele ungültig waren (nicht übernommen). */
    data class Parsed<T>(val items: List<T>, val invalid: Int)

    /**
     * Alle Transaktionen einer Liste (Felder geprüft, [com.cryptochecker.app.data.BackupFields.TX]); ungültige werden
     * gezählt, nicht still verworfen.
     */
    fun parseTxs(array: JSONArray): Parsed<PortfolioTxEntity> = parse(array, ::jsonToTx)

    /** Alle Portfolio-Alarme einer Liste (Felder geprüft); ungültige werden gezählt. */
    fun parseAlarms(array: JSONArray): Parsed<PortfolioAlarmEntity> = parse(array, ::jsonToAlarm)

    private fun <T> parse(array: JSONArray, read: (JSONObject) -> T?): Parsed<T> {
        val items = ArrayList<T>(array.length())
        var invalid = 0
        for (i in 0 until array.length()) {
            val item = read(array.getJSONObject(i))
            if (item == null) invalid++ else items += item
        }
        return Parsed(items, invalid)
    }

    /**
     * Eine Transaktion; jedes Feld, das [txToJson] schreibt, muss da sein ([org.json.JSONException] sonst — geprüft wird
     * vorher mit `BackupFields`). Ungültige Werte → null (der Aufrufer zählt sie, [parseTxs]). Mengen, Gebühren und Werte
     * gelesen wie gespeichert ([amountOf]: 18 Nachkommastellen) — eine Menge, die dabei 0 wird (unter 10⁻¹⁸), ist ungültig
     * wie eine fehlende. Wie iOS.
     */
    fun jsonToTx(o: JSONObject): PortfolioTxEntity? {
        val coin = PortfolioCalculator.normalizeCoin(o.getString("coin"))
        // Unbekannte Art (neuere Version): ungültig, wird gezählt ([parseTxs])
        val type = PortfolioTxType.fromName(o.getString("type")) ?: return null
        val amount = amountOf(o, "amountText")?.takeIf { it.signum() > 0 } ?: return null
        if (coin.isEmpty()) return null
        // null oder ungültig: keine Gebühr
        val fee = amountOf(o, "feeText")?.takeIf { it.signum() > 0 }
        // Gebühr im dritten Coin: nur mit Coin und Menge > 0, sonst keine
        val third = cleanFeeCoin(text(o, "feeCoin"), coin, amountOf(o, "feeCoinAmountText"))
        // Tausch: ohne gültigen erhaltenen Coin und Menge ungültig (gezählt)
        val swap = type == PortfolioTxType.SWAP
        val toCoin = if (swap) cleanToCoin(text(o, "toCoin"), coin) ?: return null else null
        val toAmount = if (swap) amountOf(o, "toAmountText")?.takeIf { it.signum() > 0 } ?: return null else null
        val swapValue = if (swap) amountOf(o, "swapValueText")?.takeIf { it.signum() >= 0 } else null
        // Zweiter Schlüssel nur mit erstem, Zustand nur mit Verknüpfung
        val importKey = cleanImportKey(text(o, "importKey"))
        val linkedImportKey = cleanImportKey(text(o, "linkedImportKey"))?.takeIf { importKey != null }
        val price = decimal(o, "priceText")?.takeIf { it.signum() >= 0 }
        val note = text(o, "note")?.let { TextLimit.clean(it, NOTE_MAX) }
        return PortfolioTxEntity(
            id = o.getLong("id").coerceAtLeast(0L),
            coin = coin,
            type = type,
            amount = amount,
            priceUsdt = price,
            time = o.getLong("time"),
            // Höchstens 120 Zeichen wie die Notiz in der Merkliste
            note = note,
            fee = fee,
            feeUnit = fee?.let { PortfolioFeeUnit.fromName(text(o, "feeUnit")).name },
            exchange = cleanExchange(text(o, "exchange")),
            transferFrom = cleanExchange(text(o, "transferFrom")),
            transferTo = cleanExchange(text(o, "transferTo")),
            feeCoin = third?.first,
            feeCoinAmount = third?.second,
            feeCoinPriceUsdt = third?.let { text(o, "feeCoinPriceText")?.let { t -> PortfolioDecimal.parse(t) }?.takeIf { it.signum() >= 0 } },
            importKey = importKey,
            acquiredTime = cleanAcquiredTime(type, if (o.isNull("acquiredTime")) null else o.getLong("acquiredTime")),
            toCoin = toCoin,
            toAmount = toAmount,
            swapValueUsdt = swapValue,
            importBatch = PortfolioImportBatch.clean(text(o, "importBatch")),
            giftToSpouse = o.getBoolean("giftToSpouse") && type == PortfolioTxType.GIFT,
            linkedImportKey = linkedImportKey,
            importRestoreState = linkedImportKey?.let {
                if (o.isNull("importRestoreState")) null else CsvRestoreState.decode(o.getJSONObject("importRestoreState").toString())
            },
            feeRow = type == PortfolioTxType.SELL && o.getBoolean("feeRow"),
            interest = type == PortfolioTxType.REWARD && o.getBoolean("interest"),
            stakingMove = type == PortfolioTxType.TRANSFER && o.getBoolean("stakingMove"),
        )
    }

    /** Tausch: erhaltener Coin normalisiert (höchstens [MAX_FEE_COIN] Zeichen, nicht [coin]); sonst null. */
    fun cleanToCoin(toCoin: String?, coin: String): String? {
        val c = toCoin?.let { PortfolioCalculator.normalizeCoin(it) }?.takeIf { it.isNotEmpty() && it.length <= MAX_FEE_COIN } ?: return null
        return c.takeIf { it != PortfolioCalculator.normalizeCoin(coin) }
    }

    /** Ursprüngliches Kaufdatum: nur bei einer Einzahlung und > 0, sonst null. */
    fun cleanAcquiredTime(type: PortfolioTxType, acquiredTime: Long?): Long? =
        acquiredTime?.takeIf { type == PortfolioTxType.DEPOSIT && it > 0L }

    /** Text oder null (JSON null); das Feld muss da sein. */
    private fun text(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.getString(key)

    /**
     * Gebühr im dritten Coin bereinigt: Coin normalisiert (höchstens [MAX_FEE_COIN] Zeichen, nicht der Coin
     * der Transaktion [coin]) und Menge > 0 — sonst null (keine solche Gebühr).
     */
    fun cleanFeeCoin(feeCoin: String?, coin: String, amount: BigDecimal?): Pair<String, BigDecimal>? {
        val c = feeCoin?.let { PortfolioCalculator.normalizeCoin(it) }?.takeIf { it.isNotEmpty() && it.length <= MAX_FEE_COIN } ?: return null
        if (c == PortfolioCalculator.normalizeCoin(coin)) return null
        val a = amount?.takeIf { it.signum() > 0 } ?: return null
        return c to a
    }

    /**
     * Herkunftsschlüssel: getrimmt, höchstens [MAX_IMPORT_KEY] UTF-8-Bytes (länger = ungültig, weggelassen; der CSV-Import
     * ersetzt längere durch ihren Hash, [com.cryptochecker.app.domain.portfolio.csvimport.CsvImport.stableKey]); leer =
     * null. In Bytes gezählt, damit beide Plattformen dieselben Schlüssel annehmen. Wie iOS.
     */
    fun cleanImportKey(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_KEY }

    const val MAX_IMPORT_KEY = CsvImport.MAX_KEY_BYTES

    /** Höchstlänge eines Gebühr-Coins (wie die Coin-Eingabe des Erfassen-Blatts). */
    const val MAX_FEE_COIN = 15

    /** Exakte Zahl aus dem Text [key]; null/ungültig = null. */
    private fun decimal(o: JSONObject, key: String): BigDecimal? = text(o, key)?.let { PortfolioDecimal.parse(it) }

    /** Menge, Gebühr oder Wert aus dem Text [key], gerundet wie gespeichert ([PortfolioDecimal.amount]); null/ungültig = null. */
    private fun amountOf(o: JSONObject, key: String): BigDecimal? = decimal(o, key)?.let { PortfolioDecimal.amount(it) }

    /** Börse: getrimmt, höchstens [MAX_EXCHANGE] sichtbare Zeichen (nie mitten im Emoji gekürzt); leer = null. */
    fun cleanExchange(raw: String?): String? = TextLimit.clean(raw, MAX_EXCHANGE)

    const val MAX_EXCHANGE = 40

    /** Portfolio-Alarm (gleiches Format wie iOS); Zustand (gemeldet, zuletzt) wird nicht gesichert. */
    fun alarmToJson(a: PortfolioAlarmEntity): JSONObject = JSONObject()
        .put("id", a.id)
        .put("kind", a.kind.name)
        .put("threshold", a.threshold)
        .put("currency", a.currency ?: JSONObject.NULL)
        .put("enabled", a.enabled)
        .put("repeating", a.repeating)

    /**
     * Ein Portfolio-Alarm (Felder geprüft, sonst [org.json.JSONException]). Unbekannte Art (neuere Version) oder
     * ungültiger Schwellwert: ungültig (gezählt). «fällt um ≥ 100 %» übernommen, aber ausgeschaltet
     * ([PortfolioAlarmLogic.exceedsFall], wie die Paar-Alarme).
     */
    fun jsonToAlarm(o: JSONObject): PortfolioAlarmEntity? {
        val kind = PortfolioAlarmKind.fromName(o.getString("kind")) ?: return null
        val threshold = o.getDouble("threshold").takeIf {
            PortfolioAlarmLogic.isValidThreshold(kind, it) || PortfolioAlarmLogic.exceedsFall(kind, it)
        } ?: return null
        val currency = text(o, "currency")?.trim()?.uppercase()
            ?.takeIf { code -> code.length == 3 && code.all { it in 'A'..'Z' } }
        if (kind.isValue && currency == null) return null
        return PortfolioAlarmEntity(
            id = o.getLong("id").coerceAtLeast(0L),
            kind = kind,
            threshold = threshold,
            currency = currency.takeIf { kind.isValue },
            enabled = o.getBoolean("enabled") && !PortfolioAlarmLogic.exceedsFall(kind, threshold),
            repeating = o.getBoolean("repeating"),
        )
    }
}
