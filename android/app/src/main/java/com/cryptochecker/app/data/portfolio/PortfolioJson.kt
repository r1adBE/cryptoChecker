package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.LegacyStorageMigration
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
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
        // Zahl für ältere Versionen (lesen nur sie), exakter Text für diese («amountText»: «0.00000001»)
        .put("amount", t.amount.toDouble())
        .put("amountText", PortfolioDecimal.text(t.amount))
        .put("priceUsdt", t.priceUsdt?.toDouble() ?: JSONObject.NULL)
        .put("priceText", t.priceUsdt?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        .put("time", t.time)
        .put("note", t.note ?: JSONObject.NULL)
        // Seit Portfolio-Datenbank v2; ältere Sicherungen haben sie nicht (= keine Angabe)
        .put("fee", t.fee?.toDouble() ?: JSONObject.NULL)
        .put("feeText", t.fee?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        .put("feeUnit", t.fee?.let { PortfolioFeeUnit.fromName(t.feeUnit).name } ?: JSONObject.NULL)
        .put("exchange", t.exchange ?: JSONObject.NULL)
        // Seit Build 46 (Portfolio-Datenbank v3); fehlen = keine Angabe. Nur exakter Text — ältere Versionen
        // kennen die Felder nicht (und die neuen Arten nicht: sie zählen sie als ungültig, Rückfrage)
        .put("transferFrom", t.transferFrom ?: JSONObject.NULL)
        .put("transferTo", t.transferTo ?: JSONObject.NULL)
        .put("feeCoin", t.feeCoin ?: JSONObject.NULL)
        .put("feeCoinAmountText", t.feeCoinAmount?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        .put("feeCoinPriceText", t.feeCoinPriceUsdt?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        // CSV-Import (Build 46): Herkunft der Zeile — nach dem Wiederherstellen weiterhin als doppelt erkannt
        .put("importKey", t.importKey ?: JSONObject.NULL)
        // Einzahlung: ursprüngliches Kaufdatum (Build 46); fehlt = Datum der Einzahlung
        .put("acquiredTime", t.acquiredTime?.takeIf { t.type == PortfolioTxType.DEPOSIT } ?: JSONObject.NULL)
        // Tausch (Build 46): erhaltener Coin, Menge und Wert (exakt als Text); ältere Versionen kennen die Art nicht
        // und zählen den Eintrag als ungültig (Rückfrage)
        .put("toCoin", t.toCoin?.takeIf { t.type == PortfolioTxType.SWAP } ?: JSONObject.NULL)
        .put("toAmountText", t.toAmount?.takeIf { t.type == PortfolioTxType.SWAP }?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        .put("swapValueText", t.swapValueUsdt?.takeIf { t.type == PortfolioTxType.SWAP }?.let { PortfolioDecimal.text(it) } ?: JSONObject.NULL)
        // CSV-Importlauf (Build 46): «Import rückgängig» auch nach dem Wiederherstellen
        .put("importBatch", t.importBatch ?: JSONObject.NULL)
        // Build 58 (Portfolio-Datenbank v4): eigene Felder statt der alten Formen in «transferTo»/«importKey». Ältere
        // Versionen ignorieren unbekannte Felder und lesen den Eintrag trotzdem — ohne Partner-Merker, mit nur dem
        // Schlüssel der Auszahlung und ohne «Rückgängig» der Verknüpfung (kein Absturz, nichts ungültig)
        .put("giftToSpouse", t.giftToSpouse && t.type == PortfolioTxType.GIFT)
        .put("linkedImportKey", t.linkedImportKey ?: JSONObject.NULL)
        .put("importRestoreState", t.importRestoreState?.toJson() ?: JSONObject.NULL)
        // Build 76 (Portfolio-Datenbank v5): Gebühr-Zeile und Zins als eigene Merker. Ältere Versionen ignorieren sie und
        // schliessen wie bisher aus Preis 0 + Herkunft bzw. aus der Notiz (beides bleibt gespeichert)
        .put("feeRow", t.feeRow && t.type == PortfolioTxType.SELL)
        .put("interest", t.interest && t.type == PortfolioTxType.REWARD)

    /** Gelesene Einträge und wie viele ungültig waren (nicht übernommen). */
    data class Parsed<T>(val items: List<T>, val invalid: Int)

    /** Alle Transaktionen einer Liste; ungültige (auch Nicht-Objekte) werden gezählt, nicht still verworfen. */
    fun parseTxs(array: JSONArray): Parsed<PortfolioTxEntity> = parse(array, ::jsonToTx)

    /** Alle Portfolio-Alarme einer Liste; ungültige werden gezählt. */
    fun parseAlarms(array: JSONArray): Parsed<PortfolioAlarmEntity> = parse(array, ::jsonToAlarm)

    private fun <T> parse(array: JSONArray, read: (JSONObject) -> T?): Parsed<T> {
        val items = ArrayList<T>(array.length())
        var invalid = 0
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)?.let(read)
            if (item == null) invalid++ else items += item
        }
        return Parsed(items, invalid)
    }

    /** Unvollständige oder ungültige Einträge → null (der Aufrufer zählt sie, [parseTxs]). */
    fun jsonToTx(o: JSONObject): PortfolioTxEntity? {
        val coin = PortfolioCalculator.normalizeCoin(o.optString("coin"))
        // Unbekannte Art (neuere Version): ungültig, wird gezählt ([parseTxs])
        val type = PortfolioTxType.fromName(o.optString("type")) ?: return null
        val amount = decimal(o, "amountText", "amount")?.takeIf { it.signum() > 0 } ?: return null
        if (coin.isEmpty()) return null
        // Fehlt (ältere Sicherung) oder ungültig: keine Gebühr
        val fee = decimal(o, "feeText", "fee")?.takeIf { it.signum() > 0 }
        // Gebühr im dritten Coin: nur mit Coin und Menge > 0, sonst keine
        val third = cleanFeeCoin(text(o, "feeCoin"), coin, text(o, "feeCoinAmountText")?.let { PortfolioDecimal.parse(it) })
        // Tausch: ohne gültigen erhaltenen Coin und Menge ungültig (gezählt)
        val swap = type == PortfolioTxType.SWAP
        val toCoin = if (swap) cleanToCoin(text(o, "toCoin"), coin) ?: return null else null
        val toAmount = if (swap) text(o, "toAmountText")?.let { PortfolioDecimal.parse(it) }?.takeIf { it.signum() > 0 } ?: return null else null
        val swapValue = if (swap) text(o, "swapValueText")?.let { PortfolioDecimal.parse(it) }?.takeIf { it.signum() >= 0 } else null
        // Sicherungen bis Build 57 tragen Partner-Merker und Verknüpfung noch in «transferTo»/«importKey» (alte Formen)
        val stored = LegacyStorageMigration.convert(
            type,
            LegacyStorageMigration.Fields(
                transferTo = cleanExchange(text(o, "transferTo")),
                giftToSpouse = o.optBoolean("giftToSpouse", false),
                importKey = cleanImportKey(text(o, "importKey")),
                linkedImportKey = cleanImportKey(text(o, "linkedImportKey")),
                importRestoreState = o.optJSONObject("importRestoreState")?.let { CsvRestoreState.decode(it.toString()) },
            ),
        )
        val linkedImportKey = stored.linkedImportKey?.takeIf { stored.importKey != null }
        val price = decimal(o, "priceText", "priceUsdt")?.takeIf { it.signum() >= 0 }
        val note = if (o.isNull("note")) null else TextLimit.clean(o.optString("note"), NOTE_MAX)
        return PortfolioTxEntity(
            id = o.optLong("id", 0L).coerceAtLeast(0L),
            coin = coin,
            type = type,
            amount = amount,
            priceUsdt = price,
            time = o.optLong("time", System.currentTimeMillis()),
            // Höchstens 120 Zeichen wie die Notiz in der Merkliste
            note = note,
            fee = fee,
            feeUnit = fee?.let { PortfolioFeeUnit.fromName(if (o.isNull("feeUnit")) null else o.optString("feeUnit")).name },
            exchange = if (o.isNull("exchange")) null else cleanExchange(o.optString("exchange")),
            transferFrom = cleanExchange(text(o, "transferFrom")),
            transferTo = stored.transferTo,
            feeCoin = third?.first,
            feeCoinAmount = third?.second,
            feeCoinPriceUsdt = third?.let { text(o, "feeCoinPriceText")?.let { t -> PortfolioDecimal.parse(t) }?.takeIf { it.signum() >= 0 } },
            importKey = stored.importKey,
            acquiredTime = cleanAcquiredTime(type, if (o.has("acquiredTime") && !o.isNull("acquiredTime")) o.optLong("acquiredTime", 0L) else null),
            toCoin = toCoin,
            toAmount = toAmount,
            swapValueUsdt = swapValue,
            importBatch = PortfolioImportBatch.clean(text(o, "importBatch")),
            giftToSpouse = stored.giftToSpouse && type == PortfolioTxType.GIFT,
            linkedImportKey = linkedImportKey,
            importRestoreState = stored.importRestoreState?.takeIf { linkedImportKey != null },
            // Fehlen die Merker (Sicherung bis Build 75), einmal nach der früheren Regel ableiten
            feeRow = type == PortfolioTxType.SELL &&
                (if (o.has("feeRow")) o.optBoolean("feeRow", false) else LegacyStorageMigration.legacyFeeRow(type, stored.importKey, price)),
            interest = type == PortfolioTxType.REWARD &&
                (if (o.has("interest")) o.optBoolean("interest", false) else LegacyStorageMigration.legacyInterest(type, note)),
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

    /** Text oder null (fehlt / JSON null). */
    private fun text(o: JSONObject, key: String): String? = if (!o.has(key) || o.isNull(key)) null else o.optString(key)

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

    /** Herkunftsschlüssel: getrimmt, höchstens [MAX_IMPORT_KEY] Zeichen (länger = ungültig, weggelassen); leer = null. */
    fun cleanImportKey(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_IMPORT_KEY }

    const val MAX_IMPORT_KEY = 200

    /** Höchstlänge eines Gebühr-Coins (wie die Coin-Eingabe des Erfassen-Blatts). */
    const val MAX_FEE_COIN = 15

    /**
     * Exakte Zahl: zuerst der Text [textKey] (seit Runde 35), sonst die Zahl [numberKey] älterer
     * Sicherungen (über ihre kürzeste Darstellung, 0.1 → «0.1»). Fehlt/ungültig = null.
     */
    private fun decimal(o: JSONObject, textKey: String, numberKey: String): BigDecimal? {
        if (o.has(textKey) && !o.isNull(textKey)) PortfolioDecimal.parse(o.optString(textKey))?.let { return it }
        if (!o.has(numberKey) || o.isNull(numberKey)) return null
        // Gleiche Grenzen wie der Text (absurde Grössen = ungültig)
        return PortfolioDecimal.of(o.optDouble(numberKey))?.takeIf { PortfolioDecimal.withinLimits(it) }
    }

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

    /** Unbekannte Art (neuere Version) oder ungültiger Schwellwert: überspringen. */
    fun jsonToAlarm(o: JSONObject): PortfolioAlarmEntity? {
        val kind = PortfolioAlarmKind.fromName(o.optString("kind")) ?: return null
        val threshold = o.optDouble("threshold").takeIf { PortfolioAlarmLogic.isValidThreshold(kind, it) } ?: return null
        val currency = if (o.isNull("currency")) null else o.optString("currency").trim().uppercase()
            .takeIf { code -> code.length == 3 && code.all { it in 'A'..'Z' } }
        if (kind.isValue && currency == null) return null
        return PortfolioAlarmEntity(
            id = o.optLong("id", 0L).coerceAtLeast(0L),
            kind = kind,
            threshold = threshold,
            currency = currency.takeIf { kind.isValue },
            enabled = o.optBoolean("enabled", true),
            repeating = o.optBoolean("repeating", false),
        )
    }
}
