package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.util.json.JsonObject
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import java.math.BigDecimal

/**
 * Die neue Zeile der späteren Datei, die in einer Verknüpfung mit einer gespeicherten Seite aufging (Einzahlung bzw.
 * Auszahlung) — «Import rückgängig» des FRÜHEREN Laufs stellt sie als eigene Zeile im späteren Lauf wieder her (Coin wie
 * die Umbuchung).
 */
data class CsvAbsorbedRow(
    val type: PortfolioTxType,
    val amount: BigDecimal,
    val priceUsdt: BigDecimal?,
    val time: Long,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val importKey: String,
)

/**
 * Zustand einer gespeicherten Transaktion vor der Verknüpfung zu einer Umbuchung ([CsvTransferMatch.restoreState]) —
 * gespeichert in der Spalte `importRestoreState` (Portfolio-Datenbank, iOS `PortfolioTx.importRestoreState`, Sicherung
 * «importRestoreState»). «Import rückgängig» des verknüpfenden Laufs setzt die Transaktion darauf zurück (Art, Menge, Preis,
 * Zeit, Gebühren, von/nach, Kaufdatum, Börse, Herkunftsschlüssel und Importlauf). [absorbed] = die darin aufgegangene neue
 * Zeile.
 *
 * Warum EIN JSON-Feld statt eigener Spalten: Der Zustand umfasst 23 Werte (15 der gespeicherten Seite, 8 der aufgegangenen
 * Zeile), die nur «Import rückgängig» liest — immer alle zusammen, nie einzeln gesucht oder sortiert. Als Spalten trügen
 * Entity, Sicherung und iOS-Modell 23 fast immer leere Felder mit; als ein Feld bleibt es eine Einheit mit eigener
 * Fassungsnummer ([VERSION]), die sich ohne weitere Datenbank-Migration erweitern lässt.
 *
 * Format (Fassung 1; Zahlen exakt als Text wie [PortfolioDecimal.text], fehlende Werte weggelassen):
 * ```
 * {"v": 1,
 *  "stored":   {"type", "amount", "priceUsdt"?, "time", "fee"?, "feeUnit"?, "feeCoin"?, "feeCoinAmount"?,
 *               "feeCoinPriceUsdt"?, "transferFrom"?, "transferTo"?, "acquiredTime"?, "exchange"?, "importKey",
 *               "importBatch"?},
 *  "absorbed": {"type", "amount", "priceUsdt"?, "time", "fee"?, "feeUnit"?, "feeCoin"?, "feeCoinAmount"?, "importKey"}?}
 * ```
 * Unbekannte Fassung oder unlesbar → null (die Transaktion wird beim «Rückgängig» dann wie eine eigene Zeile behandelt).
 */
data class CsvRestoreState(
    val type: PortfolioTxType,
    val amount: BigDecimal,
    val priceUsdt: BigDecimal?,
    val time: Long,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val feeCoinPrice: BigDecimal?,
    val transferFrom: String?,
    val transferTo: String?,
    val acquiredTime: Long?,
    val exchange: String?,
    val importKey: String,
    val importBatch: String?,
    val absorbed: CsvAbsorbedRow?,
) {
    /** JSON-Form (Format oben). */
    fun toJson(): JsonObject {
        val stored = JsonObject()
            .put("type", type.name)
            .put("amount", PortfolioDecimal.text(amount))
            .putDecimal("priceUsdt", priceUsdt)
            .put("time", time)
            .putDecimal("fee", fee)
            .putText("feeUnit", feeUnit?.name)
            .putText("feeCoin", feeCoin)
            .putDecimal("feeCoinAmount", feeCoinAmount)
            .putDecimal("feeCoinPriceUsdt", feeCoinPrice)
            .putText("transferFrom", transferFrom)
            .putText("transferTo", transferTo)
            .apply { acquiredTime?.let { put("acquiredTime", it) } }
            .putText("exchange", exchange)
            .put("importKey", importKey)
            .putText("importBatch", importBatch)
        val out = JsonObject().put("v", VERSION).put("stored", stored)
        absorbed?.let { a ->
            out.put(
                "absorbed",
                JsonObject()
                    .put("type", a.type.name)
                    .put("amount", PortfolioDecimal.text(a.amount))
                    .putDecimal("priceUsdt", a.priceUsdt)
                    .put("time", a.time)
                    .putDecimal("fee", a.fee)
                    .putText("feeUnit", a.feeUnit?.name)
                    .putText("feeCoin", a.feeCoin)
                    .putDecimal("feeCoinAmount", a.feeCoinAmount)
                    .put("importKey", a.importKey),
            )
        }
        return out
    }

    /** Text für die Datenbank-Spalte ([toJson]). */
    fun encode(): String = toJson().toString()

    companion object {
        /** Fassung des Formats; eine neue Fassung bekommt eine neue Zahl, ältere bleiben lesbar. */
        const val VERSION = 1

        /**
         * Längster gespeicherter Text — nur gegen unsinnige Eingaben aus einer Sicherung; ein echter Zustand (zwei
         * Herkunftsschlüssel à höchstens 200 Zeichen, Börsen à 40, Zahlen) bleibt weit darunter.
         */
        const val MAX_TEXT = 4_000

        /** Aus dem Text der Datenbank-Spalte; null = keiner, zu lang, unbekannte Fassung oder unlesbar. */
        fun decode(text: String?): CsvRestoreState? {
            if (text.isNullOrBlank() || text.length > MAX_TEXT) return null
            return runCatching { JsonObject(text) }.getOrNull()?.let(::fromJson)
        }

        /** Aus der JSON-Form ([toJson]); null = unbekannte Fassung oder unlesbar. */
        fun fromJson(o: JsonObject?): CsvRestoreState? {
            if (o == null) return null
            // Fassung nur als Zahl (kein Text, keine Nachkommastellen) — wie iOS
            if ((o.opt("v") as? Number)?.toDouble() != VERSION.toDouble()) return null
            val s = o.optJSONObject("stored") ?: return null
            val absorbed = if (o.has("absorbed") && !o.isNull("absorbed")) absorbedRow(o.optJSONObject("absorbed")) ?: return null else null
            return CsvRestoreState(
                type = PortfolioTxType.fromName(s.text("type")) ?: return null,
                amount = s.decimal("amount") ?: return null,
                priceUsdt = s.decimal("priceUsdt"),
                time = s.long("time") ?: return null,
                fee = s.decimal("fee"),
                feeUnit = s.text("feeUnit")?.let { u -> PortfolioFeeUnit.entries.firstOrNull { it.name == u } },
                feeCoin = s.text("feeCoin"),
                feeCoinAmount = s.decimal("feeCoinAmount"),
                feeCoinPrice = s.decimal("feeCoinPriceUsdt"),
                transferFrom = s.text("transferFrom"),
                transferTo = s.text("transferTo"),
                acquiredTime = s.long("acquiredTime"),
                exchange = s.text("exchange"),
                importKey = s.text("importKey") ?: return null,
                importBatch = s.text("importBatch"),
                absorbed = absorbed,
            )
        }

        private fun absorbedRow(a: JsonObject?): CsvAbsorbedRow? {
            if (a == null) return null
            return CsvAbsorbedRow(
                type = PortfolioTxType.fromName(a.text("type")) ?: return null,
                amount = a.decimal("amount") ?: return null,
                priceUsdt = a.decimal("priceUsdt"),
                time = a.long("time") ?: return null,
                fee = a.decimal("fee"),
                feeUnit = a.text("feeUnit")?.let { u -> PortfolioFeeUnit.entries.firstOrNull { it.name == u } },
                feeCoin = a.text("feeCoin"),
                feeCoinAmount = a.decimal("feeCoinAmount"),
                importKey = a.text("importKey") ?: return null,
            )
        }

        private fun JsonObject.putText(name: String, value: String?): JsonObject = apply { if (value != null) put(name, value) }

        private fun JsonObject.putDecimal(name: String, value: BigDecimal?): JsonObject =
            apply { if (value != null) put(name, PortfolioDecimal.text(value)) }

        /** Text oder null (fehlt, JSON null, kein Text oder leer). */
        private fun JsonObject.text(name: String): String? =
            if (!has(name) || isNull(name)) null else (opt(name) as? String)?.takeIf { it.isNotEmpty() }

        private fun JsonObject.decimal(name: String): BigDecimal? = text(name)?.let { PortfolioDecimal.parse(it) }

        /** Ganze Zahl (ms) oder null — nur echte Zahlen ohne Nachkommastellen. */
        private fun JsonObject.long(name: String): Long? {
            if (!has(name) || isNull(name)) return null
            val n = opt(name) as? Number ?: return null
            val d = n.toDouble()
            return n.toLong().takeIf { d.isFinite() && d == it.toDouble() }
        }
    }
}
