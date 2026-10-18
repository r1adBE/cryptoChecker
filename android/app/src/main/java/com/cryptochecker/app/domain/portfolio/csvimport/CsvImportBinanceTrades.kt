// Binance-Handelsverlauf (Teil von [CsvImportBinance], gespiegelt in `CsvImportBinance+Trades.swift`): Paare teilen,
// Ausführungen je Order zusammenfassen, fast gleiche Geschäfte erkennen. Erweiterungsfunktionen des Objekts.
package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal

// ---------- Handelsverlauf ----------

/** Quote-Währungen der Binance-Paare, längste zuerst («BTCFDUSD» endet auf FDUSD, nicht USD). */
private val QUOTES = listOf(
    "FDUSD", "USDT", "USDC", "BUSD", "TUSD", "USDP", "BIDR", "IDRT", "EURI", "DAI", "BTC", "ETH", "BNB", "EUR", "GBP",
    "TRY", "BRL", "AUD", "JPY", "PLN", "RON", "CZK", "ZAR", "UAH", "ARS", "MXN", "COP", "USD", "XRP", "TRX", "DOGE",
).sortedByDescending { it.length }

/** «BTCUSDT» → (BTC, USDT); unbekannte Quote = null. */
internal fun CsvImportBinance.splitPair(pair: String): Pair<String, String>? {
    val p = pair.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
    val quote = QUOTES.firstOrNull { p.endsWith(it) && p.length > it.length } ?: return null
    return p.removeSuffix(quote) to quote
}

/** «0.00100000BTC» → (0.001, BTC); ohne Einheit (früherer Aufbau) → (Zahl, null); unlesbar = null. */
private fun CsvImportBinance.withUnit(text: String): Pair<BigDecimal, String?>? {
    val t = text.trim()
    val cut = t.indexOfFirst { it.isLetter() }
    if (cut < 0) return decimal(t)?.let { it to null }
    val number = decimal(t.substring(0, cut)) ?: return null
    return number to t.substring(cut).uppercase()
}

/** Eine Ausführung des Handelsverlaufs (vor dem Zusammenfassen je Sekunde, Paar und Seite). */
private class Fill(
    val line: Int,
    val kind: CsvImportKind,
    val rawKind: String,
    val base: String,
    val quote: String,
    val amount: BigDecimal,
    val total: BigDecimal,
    val price: BigDecimal,
    val fee: BigDecimal?,
    val feeUnit: String?,
    val time: Long,
    val local: Long?,
    /** Sekunde wie in der Datei — mit Paar und Seite der Schlüssel des Zusammenfassens. */
    val second: String,
)

internal fun CsvImportBinance.trades(names: List<String>, data: List<CsvImportParser.Record>, zone: String): CsvImportResult {
    val legacy = "market" in names
    val iTime = names.indexOf("dateutc")
    val iPair = names.indexOf(if (legacy) "market" else "pair")
    val iSide = names.indexOf(if (legacy) "type" else "side")
    val iPrice = names.indexOf("price")
    val iAmount = names.indexOf(if (legacy) "amount" else "executed")
    val iTotal = names.indexOf(if (legacy) "total" else "amount")
    val iFee = names.indexOf("fee")
    val iFeeCoin = names.indexOf("feecoin").takeIf { it >= 0 }
    val skipped = ArrayList<CsvImportSkip>()
    // Ausführungen derselben Sekunde, desselben Paars und derselben Seite: ein Geschäft (Reihenfolge der Datei)
    val groups = LinkedHashMap<String, MutableList<Fill>>()
    for (rec in data) {
        fun cell(i: Int?) = i?.let { rec.cells.getOrNull(it) }.orEmpty()
        val side = CsvImportParser.normalize(cell(iSide))
        val kind = when (side) {
            "buy" -> CsvImportKind.BUY
            "sell" -> CsvImportKind.SELL
            else -> { skipped += CsvImportSkip(rec.line, CsvImportSkipReason.UNSUPPORTED_KIND); continue }
        }
        val timeText = cell(iTime)
        val time = CsvImportParser.parseTime(timeText, zone)
        if (time == null) { skipped += CsvImportSkip(rec.line, CsvImportSkipReason.BAD_DATE); continue }
        val pair = splitPair(cell(iPair))
        val executed = withUnit(cell(iAmount))
        val total = withUnit(cell(iTotal))
        val price = decimal(cell(iPrice))?.abs()
        val feeText = cell(iFee)
        val fee = if (feeText.isEmpty() || feeText == "-") null to null else withUnit(feeText)
        if (executed == null || total == null || price == null || fee == null) {
            skipped += CsvImportSkip(rec.line, CsvImportSkipReason.BAD_NUMBER)
            continue
        }
        // Einheit an der Zahl (heutiger Aufbau) vor dem zerlegten Paar (früherer Aufbau)
        val base = executed.second ?: pair?.first
        val quote = total.second ?: pair?.second
        val amount = executed.first.abs()
        if (base == null || quote == null) { skipped += CsvImportSkip(rec.line, CsvImportSkipReason.MISSING_FIELD); continue }
        if (CsvImport.isFiat(base)) { skipped += CsvImportSkip(rec.line, CsvImportSkipReason.NOT_CRYPTO); continue }
        if (amount.signum() == 0 || price.signum() == 0) { skipped += CsvImportSkip(rec.line, CsvImportSkipReason.BAD_NUMBER); continue }
        val feeAmount = fee.first?.abs()?.takeIf { it.signum() > 0 }
        val feeUnit = (fee.second ?: cell(iFeeCoin).uppercase().ifEmpty { null }).takeIf { feeAmount != null }
        if (feeAmount != null && feeUnit == null) { skipped += CsvImportSkip(rec.line, CsvImportSkipReason.MISSING_FIELD); continue }
        val second = timeText.trim()
        val fill = Fill(rec.line, kind, cell(iSide), base, quote, amount, total.first.abs(), price, feeAmount, feeUnit, time,
            CsvImportParser.localTime(timeText), second)
        groups.getOrPut("$second|$base|$quote|${kind.name}") { ArrayList() } += fill
    }
    val rows = groups.values.flatMap { order(it, skipped) }
    return CsvImport.finish(CsvImportFormat.BINANCE, rows, skipped, zone, localTimes = true)
}

/**
 * Ausführungen einer Order in derselben Sekunde → ein Kauf/Verkauf mit Summen (so fasst sie auch der
 * Transaktionsverlauf zusammen: gleicher Schlüssel, nie doppelt); Preis = Quote-Summe / Menge (bei einer Ausführung
 * der Preis der Datei). Gebühren in verschiedenen Einheiten: eine am Geschäft, die übrigen als eigene Gebühr-Zeilen
 * ([CsvImport.splitFees], gleiche Wahl wie im Transaktionsverlauf).
 */
private fun CsvImportBinance.order(fills: List<Fill>, skipped: MutableList<CsvImportSkip>): List<CsvImportRow> {
    val first = fills.first()
    val feeSums = LinkedHashMap<String, BigDecimal>()
    for (f in fills) {
        val unit = f.feeUnit ?: continue
        val fee = f.fee ?: continue
        feeSums[unit] = (feeSums[unit] ?: BigDecimal.ZERO) + fee
    }
    val buy = first.kind == CsvImportKind.BUY
    val split = CsvImport.splitFees(
        feeSums.map { it.key to it.value },
        given = if (buy) first.quote else first.base,
        received = if (buy) first.base else first.quote,
    )
    if (split == null) {
        fills.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.TWO_FEES) }
        return emptyList()
    }
    val amount = fills.fold(BigDecimal.ZERO) { sum, f -> sum + f.amount }
    val total = fills.fold(BigDecimal.ZERO) { sum, f -> sum + f.total }
    val fee = split.first?.second
    val price = if (fills.size == 1) first.price
    else CsvImport.derive(total, amount)
    val draft = CsvImportRow(
        line = first.line,
        kind = first.kind,
        rawKind = first.rawKind,
        coin = first.base,
        amount = amount,
        price = price,
        quote = first.quote,
        fee = fee,
        feeUnit = split.first?.first.takeIf { fee != null },
        timeMillis = first.time,
        externalId = null,
        key = CsvImport.key(CsvImportFormat.BINANCE, first.kind, first.base, amount, null, first.time, null, first.local),
        localTime = first.local,
    )
    // Gegen Stablecoin oder Krypto-Coin: Tausch mit dem Quote-Betrag der Datei
    val trade = CsvImportParser.stableSwap(CsvImportFormat.BINANCE, draft, total) ?: draft
    // Weitere Gebühren: Veräusserung zum Tageskurs (Schlüssel wie im Transaktionsverlauf)
    val extra = split.second.map { (unit, amount) ->
        CsvImportRow(
            line = first.line,
            kind = CsvImportKind.FEE,
            rawKind = "Fee",
            coin = unit,
            amount = amount,
            price = null,
            quote = null,
            fee = null,
            feeUnit = null,
            timeMillis = first.time,
            externalId = null,
            key = CsvImport.key(CsvImportFormat.BINANCE, CsvImportKind.FEE, unit, amount, null, first.time, null, first.local),
            localTime = first.local,
        )
    }
    return listOf(trade) + extra
}

/** Zeitraum, in dem ein Binance-Geschäft mit gleicher Art und gleichem Coin als möglicherweise dasselbe gilt. */
private const val NEAR_MILLIS = 5_000L

/** Arten, die Transaktions- und Handelsverlauf beide enthalten. */
private val TRADE_KINDS = setOf(CsvImportKind.BUY, CsvImportKind.SELL, CsvImportKind.SWAP)

/** «BINANCE|ART|COIN|L<ms>|…» (ohne «#n») → (Art, Coin, Kalenderzeit); andere Schlüssel = null. */
private fun CsvImportBinance.tradeKey(key: String): Triple<String, String, Long>? {
    val p = key.split('|')
    if (p.size < 5 || p[0] != CsvImportFormat.BINANCE.name || p[1] !in TRADE_KINDS.map { it.name }) return null
    val time = p[3].removePrefix("L").toLongOrNull() ?: return null
    return Triple(p[1], p[2], time)
}

/**
 * Neue Binance-Geschäfte ([rows], ohne genaue Doppelte), die einem schon gespeicherten Binance-Geschäft gleicher Art
 * und gleichen Coins bis [NEAR_MILLIS] nahe kommen, ohne genau gleich zu sein: aus dem anderen Export anders
 * zusammengefasst (Ausführungen über mehrere Sekunden) oder um eine Sekunde versetzt. Nicht importieren — ein zweites
 * Mal gezählt wäre schlimmer — sondern in die Prüfliste ([CsvImportSkipReason.POSSIBLE_DUPLICATE]).
 */
internal fun CsvImportBinance.possibleDuplicates(rows: List<CsvImportRow>, existingKeys: Set<String>): List<CsvImportRow> {
    val stored = existingKeys.mapNotNull { tradeKey(it) }
    if (stored.isEmpty()) return emptyList()
    return rows.filter { r ->
        val own = tradeKey(r.key) ?: return@filter false
        stored.any { (kind, coin, time) -> kind == own.first && coin == own.second && kotlin.math.abs(time - own.third) <= NEAR_MILLIS }
    }
}
