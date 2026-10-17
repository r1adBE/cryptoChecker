package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Bitpanda «Transaction history» (Verlauf › CSV exportieren) → [CsvImportRow]. Gespiegelt in `CsvImportBitpanda.swift`.
 * Aufbau nur aus der öffentlichen Beschreibung bekannt (synthetische Testdateien in `testdata/csv/`), echte Beispiele
 * folgen.
 *
 * Vor der Kopfzeile steht ein Block (Hinweis, «Bitpanda transaction history», Name, Konto …; die Kopfzeilensuche
 * überspringt ihn). Spalten: «Transaction ID, Timestamp, Transaction Type, In/Out, Amount Fiat, Fiat, Amount Asset, Asset,
 * Asset market price, Asset market price currency, Asset class, Product ID, Fee, Fee asset, Spread, Spread Currency,
 * Tax Fiat»; fehlende Werte als «-», Zeit mit Versatz, Punkt als Dezimalzeichen.
 *  - Nur «Asset class» Kryptowährung; Fiat, Aktien, ETF, Metalle → [CsvImportSkipReason.NOT_CRYPTO].
 *  - «buy»/«sell» → Kauf/Verkauf, Preis = Amount Fiat / Amount Asset in «Fiat» (der Spread steckt schon im Fiat-Betrag
 *    und wird nicht noch einmal gezählt), Gebühr in «Fee asset» (oft BEST = dritter Coin).
 *  - «deposit»/«withdrawal» → Einzahlung/Auszahlung (Netzwerkgebühr in «Fee asset»).
 *  - «reward» (Staking) → Belohnung zum Marktpreis; «transfer(stake)»/«transfer(unstake)» → [CsvImportSkipReason.INTERNAL_MOVE].
 *  - «transfer» (Cashback, Bonus oder Verschiebung zu Bitpanda Pro — aus der Datei nicht unterscheidbar) und andere
 *    Arten → Prüfliste ([CsvImportSkipReason.UNSUPPORTED_KIND]).
 */
internal object CsvImportBitpanda {

    private val REQUIRED = setOf("transactionid", "timestamp", "transactiontype", "inout", "amountasset", "asset")

    fun matches(names: Set<String>): Boolean = names.containsAll(REQUIRED)

    private val INTERNAL = setOf("transferstake", "transferunstake")

    private fun kind(type: String): CsvImportKind? = when (type) {
        "buy" -> CsvImportKind.BUY
        "sell" -> CsvImportKind.SELL
        "deposit" -> CsvImportKind.DEPOSIT
        "withdrawal" -> CsvImportKind.WITHDRAWAL
        "reward" -> CsvImportKind.REWARD
        else -> null
    }

    private class Skip(val reason: CsvImportSkipReason) : Exception()

    fun build(names: List<String>, data: List<CsvImportParser.Record>, zone: String): CsvImportResult {
        fun col(n: String) = names.indexOf(n).takeIf { it >= 0 }
        val iId = col("transactionid")
        val iTime = col("timestamp")
        val iType = col("transactiontype")
        val iFiatAmount = col("amountfiat")
        val iFiat = col("fiat")
        val iAmount = col("amountasset")
        val iAsset = col("asset")
        val iMarketPrice = col("assetmarketprice")
        val iMarketCurrency = col("assetmarketpricecurrency")
        val iClass = col("assetclass")
        val iFee = col("fee")
        val iFeeAsset = col("feeasset")
        val rows = ArrayList<CsvImportRow>()
        val skipped = ArrayList<CsvImportSkip>()
        var localTimes = false
        for (rec in data) {
            fun cell(i: Int?) = i?.let { rec.cells.getOrNull(it) }.orEmpty().let { if (it == "-") "" else it }
            fun number(i: Int?): BigDecimal? {
                val t = cell(i)
                if (t.isEmpty()) return null
                return CsvImportParser.parseNumber(t, '.') ?: throw Skip(CsvImportSkipReason.BAD_NUMBER)
            }
            try {
                val type = CsvImportParser.normalize(cell(iType))
                val kind = kind(type) ?: throw Skip(
                    if (type in INTERNAL) CsvImportSkipReason.INTERNAL_MOVE else CsvImportSkipReason.UNSUPPORTED_KIND,
                )
                val coin = cell(iAsset).uppercase()
                if (coin.isEmpty()) throw Skip(CsvImportSkipReason.MISSING_FIELD)
                val assetClass = CsvImportParser.normalize(cell(iClass))
                if (CsvImport.isFiat(coin) || (assetClass.isNotEmpty() && "crypto" !in assetClass)) {
                    throw Skip(CsvImportSkipReason.NOT_CRYPTO)
                }
                val timeText = cell(iTime)
                val time = CsvImportParser.parseTime(timeText, zone) ?: throw Skip(CsvImportSkipReason.BAD_DATE)
                val local = CsvImportParser.localTime(timeText)
                if (local != null) localTimes = true
                val amount = (number(iAmount) ?: throw Skip(CsvImportSkipReason.MISSING_FIELD)).abs()
                if (amount.signum() == 0) throw Skip(CsvImportSkipReason.BAD_NUMBER)
                val fiatAmount = number(iFiatAmount)?.abs()?.takeIf { it.signum() > 0 }
                val fiat = cell(iFiat).uppercase().ifEmpty { null }
                val marketPrice = number(iMarketPrice)?.abs()?.takeIf { it.signum() > 0 }
                val marketCurrency = cell(iMarketCurrency).uppercase().ifEmpty { null }
                // Kauf/Verkauf: bezahlter bzw. erhaltener Fiat-Betrag je Coin; Belohnung: Marktpreis beim Erhalt
                var price: BigDecimal? = null
                var quote: String? = null
                if (kind == CsvImportKind.BUY || kind == CsvImportKind.SELL || kind == CsvImportKind.REWARD) {
                    val paid = fiatAmount?.takeIf { fiat != null && kind != CsvImportKind.REWARD }
                    if (paid != null) {
                        price = paid.divide(amount, CsvImport.DERIVED_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros()
                        quote = fiat
                    } else if (marketPrice != null && marketCurrency != null) {
                        price = marketPrice
                        quote = marketCurrency
                    }
                }
                val fee = number(iFee)?.abs()?.takeIf { it.signum() > 0 }
                val feeUnit = cell(iFeeAsset).uppercase().ifEmpty { null }
                if (fee != null && feeUnit == null) throw Skip(CsvImportSkipReason.MISSING_FIELD)
                val externalId = cell(iId).ifEmpty { null }
                rows += CsvImportRow(
                    line = rec.line,
                    kind = kind,
                    rawKind = cell(iType),
                    coin = coin,
                    amount = amount,
                    price = price,
                    quote = quote,
                    fee = fee,
                    feeUnit = feeUnit.takeIf { fee != null },
                    timeMillis = time,
                    externalId = externalId,
                    key = CsvImport.key(CsvImportFormat.BITPANDA, kind, coin, amount, price, time, externalId, local),
                    localTime = local,
                )
            } catch (s: Skip) {
                skipped += CsvImportSkip(rec.line, s.reason)
            }
        }
        return CsvImport.finish(CsvImportFormat.BITPANDA, rows, skipped, zone, localTimes)
    }
}
