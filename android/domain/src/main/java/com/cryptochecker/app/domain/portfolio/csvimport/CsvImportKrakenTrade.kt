// Kraken: Handel aus zwei Ledger-Zeilen (gleiche refid) — Teil von CsvImportKraken.
package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal

internal fun CsvImportKraken.trade(
    refid: String,
    group: List<CsvImportKraken.Leg>,
    rows: MutableList<CsvImportRow>,
    skipped: MutableList<CsvImportSkip>,
) {
    val given = group.filter { it.signed.signum() < 0 }
    val received = group.filter { it.signed.signum() > 0 }
    if (given.size != 1 || received.size != 1) {
        group.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.MISSING_FIELD) }
        return
    }
    if (given[0].asset == received[0].asset) {
        // Gleiches Basis-Asset (ETH → ETH2.S): Umbuchung ins/aus dem Staking, kein Geschäft
        group.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.UNSUPPORTED_KIND) }
        return
    }
    val out = given[0]
    val inn = received[0]
    val outAmount = out.signed.abs()
    val inAmount = inn.signed
    // Gebühren beider Seiten: gleiche Einheit zusammen; Fiat- und Coin-Gebühr eines Kaufs/Verkaufs → Fiat in den Preis;
    // sonst verschiedene → eine am Geschäft, die andere eigene Gebühr-Zeile
    val feeList = when {
        out.fee != null && inn.fee != null && out.asset == inn.asset -> listOf(out.asset to out.fee.add(inn.fee))
        else -> listOfNotNull(out.fee?.let { out.asset to it }, inn.fee?.let { inn.asset to it })
    }
    val folded = when {
        isMoney(out.asset) && !isMoney(inn.asset) -> CsvImport.fiatFeeInPrice(true, inn.asset, out.asset, outAmount, feeList)
        isMoney(inn.asset) && !isMoney(out.asset) -> CsvImport.fiatFeeInPrice(false, out.asset, inn.asset, inAmount, feeList)
        else -> null
    }
    val split = CsvImport.splitFees(folded?.second ?: feeList, out.asset, inn.asset)
    if (split == null) {
        group.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.TWO_FEES) }
        return
    }
    val fees = split.first?.second to split.first?.first
    val line = minOf(out.line, inn.line)
    when {
        isMoney(out.asset) && isMoney(inn.asset) ->
            group.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.NOT_CRYPTO) }
        isMoney(out.asset) -> {
            // Kauf: Geld weg, Coin rein
            val price = CsvImport.derive(folded?.first ?: outAmount, inAmount)
            rows +=
                row(
                    line, CsvImportKind.BUY,
                    rawKind(
                        inn
                    ),
                    inn.asset, inAmount, price, out.asset, fees.first, fees.second, inn.time, refid
                )
                    .copy(feeInPrice = folded != null)
        }
        isMoney(inn.asset) -> {
            // Verkauf: Coin weg, Geld rein
            val price = CsvImport.derive(folded?.first ?: inAmount, outAmount)
            rows +=
                row(
                    line, CsvImportKind.SELL,
                    rawKind(
                        out
                    ),
                    out.asset, outAmount, price, inn.asset, fees.first, fees.second, out.time, refid
                )
                    .copy(feeInPrice = folded != null)
        }
        else -> {
            // Tausch; mit Stablecoin-Seite Hinweis in der Vorschau, mit USDT-Seite Wert in USDT
            val (value, valueQuote) = CsvImport.stableSwapValue(out.asset, outAmount, inn.asset, inAmount)
            rows += CsvImportRow(
                line = line,
                kind = CsvImportKind.SWAP,
                rawKind = rawKind(out),
                coin = out.asset,
                amount = outAmount,
                price = null,
                quote = valueQuote,
                fee = fees.first,
                feeUnit = fees.second,
                timeMillis = out.time,
                externalId = refid,
                key = CsvImport.key(CsvImportFormat.KRAKEN, CsvImportKind.SWAP, out.asset, outAmount, null, out.time, refid),
                toCoin = inn.asset,
                toAmount = inAmount,
                swapValue = value,
                stableSwap = CsvImport.isStable(out.asset) || CsvImport.isStable(inn.asset),
            )
        }
    }
    // Weitere Gebühr (andere Einheit): eigene Gebühr-Zeile nach dem Geschäft, eigene Kennung (die refid trägt das Geschäft)
    if (!(isMoney(out.asset) && isMoney(inn.asset))) {
        for ((unit, amount) in split.second) {
            val leg = if (unit == out.asset) out else inn
            rows += row(line, CsvImportKind.FEE, rawKind(leg), unit, amount, null, null, null, null, leg.time, "$refid/fee")
        }
    }
}
