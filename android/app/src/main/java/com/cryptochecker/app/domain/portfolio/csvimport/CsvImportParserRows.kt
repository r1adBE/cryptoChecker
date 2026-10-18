// Zeilen der CSV-Dateien (Teil von [CsvImportParser], gespiegelt in `CsvImportParser+Rows.swift`): Zeilen lesen und
// prüfen, Tausch gegen Stablecoin, Tausch-Hälften paaren. Erweiterungsfunktionen des Objekts.
package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser.Record
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser.Header
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser.Role

// ---------- Zeilen ----------

private class Skip(val reason: CsvImportSkipReason) : Exception()

internal fun CsvImportParser.build(
    format: CsvImportFormat,
    h: Header,
    data: List<Record>,
    zone: String,
    deviceZone: String,
    chosenOrder: CsvDateOrder?,
    chosenMark: Char?,
): CsvImportResult {
    when (format) {
        CsvImportFormat.KRAKEN -> return CsvImportKraken.build(h.names, data, zone)
        CsvImportFormat.BINANCE -> return CsvImportBinance.build(h.names, data, zone)
        CsvImportFormat.COINBASE -> return CsvImportCoinbase.build(h.names, data, zone)
        CsvImportFormat.BITPANDA -> return CsvImportBitpanda.build(h.names, data, zone)
        else -> Unit
    }
    val dateIndex = h.index[Role.DATE]
    // Tag/Monat je Datei: Swissquote immer Tag zuerst; sonst aus den Daten, nur mehrdeutige → Auswahl der Vorschau
    val detected = if (format == CsvImportFormat.SWISSQUOTE || dateIndex == null) CsvDateOrder.DAY_FIRST
    else detectDateOrder(data.map { it.cells.getOrNull(dateIndex).orEmpty() })
    val ambiguous = detected == null
    val order = detected ?: chosenOrder ?: CsvDateOrder.DAY_FIRST
    val monthFirst = order == CsvDateOrder.MONTH_FIRST
    val numericRoles = listOf(Role.AMOUNT, Role.AMOUNT_SATS, Role.PRICE, Role.FIAT_AMOUNT, Role.FEE)
    val numericCells = data.flatMap { r -> numericRoles.mapNotNull { role -> h.index[role]?.let { r.cells.getOrNull(it) } } }
    val detectedMark = decimalHint(numericCells)
    // Datei verrät kein Dezimalzeichen und hat «42,000»: Vorschau fragt; ohne Wahl wie bisher das Zeichen der ersten
    // mehrdeutigen Zahl = Dezimalzeichen (gilt dann für die ganze Datei, wie angezeigt)
    val decimalSample = if (detectedMark == null) ambiguousNumber(numericCells) else null
    val hint = detectedMark ?: decimalSample?.let { sample ->
        chosenMark?.takeIf { it == '.' || it == ',' } ?: defaultMark(sample)
    }
    val parsed = ArrayList<Pair<CsvImportRow, Int>>()
    val skipped = ArrayList<CsvImportSkip>()
    // Zeile → ursprünglicher Kauf/Verkauf (vor dem Tausch gegen Stablecoin), für die Schlüssel bis Build 46
    val origins = HashMap<Int, CsvImportRow>()
    for (rec in data) {
        try {
            parsed.add(row(format, h, rec, hint, zone, monthFirst, origins))
        } catch (s: Skip) {
            skipped.add(CsvImportSkip(rec.line, s.reason))
        }
    }
    // Gebühr ≥ erhaltene Menge: übersprungen mit Grund (wie das Blatt nicht speicherbar)
    val drafts = pairSwapLegs(format, parsed, skipped).filter { r ->
        val consumes = CsvImport.feeConsumesAmount(r)
        if (consumes) skipped += CsvImportSkip(r.line, CsvImportSkipReason.FEE_EXCEEDS_AMOUNT)
        !consumes
    }
    skipped.sortBy { it.line }
    val keys = CsvImport.withOccurrence(drafts.map { it.key })
    // Frühere Schlüssel: Zeit in UTC je Zeitzone der Auswahl (bis Build 45) und Kauf/Verkauf gegen Stablecoin noch
    // als Kauf/Verkauf (bis Build 46) — ältere Importe weiter als Duplikat erkennen
    val old = drafts.map { r -> origins[r.line]?.takeIf { r.kind == CsvImportKind.SWAP } ?: r }
    val oldKeys = CsvImport.withOccurrence(old.map { it.key })
    val legacyZones = listOf(zone, deviceZone, SWISS_ZONE, "UTC").distinct()
    val seen = HashMap<String, Int>()
    val rows = drafts.mapIndexed { i, r ->
        val o = old[i]
        val n = (seen[o.key] ?: 0) + 1
        seen[o.key] = n
        val local = r.localTime
        val zoned = if (local == null) emptyList() else legacyZones.map { z ->
            val k = CsvImport.key(format, o.kind, o.coin, o.amount, o.price, toUtc(local, z), o.externalId)
            if (n == 1) k else "$k#$n"
        }
        val legacy = (listOf(oldKeys[i]) + zoned).distinct().filter { it != keys[i] }
        r.copy(key = keys[i], legacyKeys = legacy)
    }
    // Zählt die Zeitzone? Nur wenn eine lesbare Zeit ohne Versatz vorkommt
    val localTimes = dateIndex != null && data.any { r ->
        val t = r.cells.getOrNull(dateIndex).orEmpty()
        !hasOffset(t) && parseTime(t, zone, monthFirst) != null
    }
    return CsvImportResult(
        format, rows, skipped, zone, localTimes, order, ambiguous,
        decimalSample = decimalSample,
        decimalMark = hint.takeIf { decimalSample != null },
    )
}

/** Ohne Wahl gilt das einzige Zeichen einer Zahl als Dezimalzeichen ([parseNumber] ohne Hinweis). */
private fun CsvImportParser.defaultMark(sample: String): Char = if (',' in sample) ',' else '.'

/**
 * Kauf/Verkauf gegen einen Stablecoin → Tausch (siehe Kopf); null = bleibt wie [r] (kein Stablecoin, ohne Preis,
 * Coin = Stablecoin der Preiswährung). [stableAmount] = Fiat-/Quote-Betrag der Datei (null = Preis × Menge).
 */
internal fun CsvImportParser.stableSwap(format: CsvImportFormat, r: CsvImportRow, stableAmount: BigDecimal?): CsvImportRow? {
    if (r.kind != CsvImportKind.BUY && r.kind != CsvImportKind.SELL) return null
    val price = r.price ?: return null
    // Stablecoin oder Krypto-Coin als Preiswährung (Paar «ETH/BTC»): Tausch, Wert nur mit USDT-Seite
    val stable = r.quote?.takeIf { (CsvImport.isStable(it) || CsvImport.isCryptoQuote(it)) && it != r.coin } ?: return null
    val sAmount = stableAmount ?: price.multiply(r.amount)
    val sell = r.kind == CsvImportKind.SELL
    val coin = if (sell) r.coin else stable
    val amount = if (sell) r.amount else sAmount
    val toCoin = if (sell) stable else r.coin
    val toAmount = if (sell) sAmount else r.amount
    val (value, valueQuote) = CsvImport.stableSwapValue(coin, amount, toCoin, toAmount)
    return r.copy(
        kind = CsvImportKind.SWAP,
        coin = coin,
        amount = amount,
        price = null,
        quote = valueQuote,
        key = CsvImport.key(format, CsvImportKind.SWAP, coin, amount, null, r.timeMillis, r.externalId, r.localTime),
        toCoin = toCoin,
        toAmount = toAmount,
        swapValue = value,
        stableSwap = CsvImport.isStable(stable),
    )
}

/**
 * Tausch-Seiten (Tausch-Zeile ohne erhaltene Seite) paarweise zu einer Zeile: zwei aufeinanderfolgende Seiten mit
 * gleicher Kennung (fehlt eine: gleicher Zeit) und verschiedenen Coins. Abgegeben = negative Menge, sonst die
 * Seite mit Verkaufs-/Ausgangswort in der Art; nicht eindeutig oder ohne Gegenstück → übersprungen.
 * [parsed] = Zeile mit Vorzeichen der Menge (−1/1).
 */
private fun CsvImportParser.pairSwapLegs(
    format: CsvImportFormat,
    parsed: List<Pair<CsvImportRow, Int>>,
    skipped: MutableList<CsvImportSkip>,
): List<CsvImportRow> {
    fun isLeg(r: CsvImportRow) = r.kind == CsvImportKind.SWAP && r.toCoin == null
    fun outWord(r: CsvImportRow) = normalize(r.rawKind).let { t ->
        listOf("sell", "verkauf", "vente", "out", "from", "ausgang", "debit", "belastung", "abgang").any { t.contains(it) }
    }
    val out = ArrayList<CsvImportRow>()
    var i = 0
    while (i < parsed.size) {
        val (a, signA) = parsed[i]
        if (!isLeg(a)) { out += a; i++; continue }
        val next = parsed.getOrNull(i + 1)
        val b = next?.first
        val matches = b != null && isLeg(b) && a.coin != b.coin &&
            (if (a.externalId != null && b.externalId != null) a.externalId == b.externalId else a.timeMillis == b.timeMillis)
        if (!matches) {
            skipped += CsvImportSkip(a.line, CsvImportSkipReason.MISSING_FIELD)
            i++
            continue
        }
        val signB = next.second
        val aGiven = when {
            signA < 0 && signB > 0 -> true
            signB < 0 && signA > 0 -> false
            outWord(a) && !outWord(b) -> true
            outWord(b) && !outWord(a) -> false
            else -> null
        }
        if (aGiven == null) {
            skipped += CsvImportSkip(a.line, CsvImportSkipReason.MISSING_FIELD)
            skipped += CsvImportSkip(b.line, CsvImportSkipReason.MISSING_FIELD)
            i += 2
            continue
        }
        val given = if (aGiven) a else b
        val received = if (aGiven) b else a
        // Wert: abgegebene Seite zuerst; Gebühr: abgegebene Seite zuerst
        val valueRow = if (given.swapValue != null) given else received
        // Beide Seiten mit Gebühr: gleiche Einheit → zusammenzählen; verschiedene → nicht still eine verwerfen,
        // beide Zeilen überspringen (Tausch von Hand erfassen)
        val fees = CsvImport.swapFee(given.fee, given.feeUnit, received.fee, received.feeUnit)
        if (fees == null) {
            skipped += CsvImportSkip(a.line, CsvImportSkipReason.TWO_FEES)
            skipped += CsvImportSkip(b.line, CsvImportSkipReason.TWO_FEES)
            i += 2
            continue
        }
        val externalId = given.externalId ?: received.externalId
        out += CsvImportRow(
            line = minOf(a.line, b.line),
            kind = CsvImportKind.SWAP,
            rawKind = given.rawKind,
            coin = given.coin,
            amount = given.amount,
            price = null,
            quote = valueRow.quote.takeIf { valueRow.swapValue != null },
            fee = fees.first,
            feeUnit = fees.second,
            timeMillis = given.timeMillis,
            externalId = externalId,
            key = CsvImport.key(
                format, CsvImportKind.SWAP, given.coin, given.amount, null, given.timeMillis, externalId, given.localTime,
            ),
            toCoin = received.coin,
            toAmount = received.amount,
            swapValue = valueRow.swapValue,
            localTime = given.localTime,
        )
        i += 2
    }
    return out
}

/** Eine Zeile samt Vorzeichen der Menge in der Datei (−1 negativ, sonst 1; für Tausch-Seiten). */
private fun CsvImportParser.row(
    format: CsvImportFormat,
    h: Header,
    rec: Record,
    hint: Char?,
    zone: String,
    monthFirst: Boolean,
    origins: MutableMap<Int, CsvImportRow>,
): Pair<CsvImportRow, Int> {
    fun cell(role: Role): String = h.index[role]?.let { rec.cells.getOrNull(it) }.orEmpty()
    fun number(role: Role): BigDecimal? {
        val t = cell(role)
        // «-» / «–» = leer (manche Exporte für fehlende Gebühr)
        if (t.isEmpty() || t == "-" || t == "–") return null
        return parseNumber(t, hint) ?: throw Skip(CsvImportSkipReason.BAD_NUMBER)
    }

    val rawKind = cell(Role.KIND)
    val kind = classify(rawKind) ?: throw Skip(CsvImportSkipReason.UNSUPPORTED_KIND)

    // Coin: Relai nur Bitcoin; Swissquote nur Zeilen ohne ISIN (Aktien/ETF haben eine)
    val coin = when (format) {
        CsvImportFormat.RELAI -> cell(Role.COIN).ifEmpty { "BTC" }
        CsvImportFormat.SWISSQUOTE -> {
            if (cell(Role.ISIN).isNotEmpty() || cell(Role.COIN).isEmpty()) throw Skip(CsvImportSkipReason.NOT_CRYPTO)
            cell(Role.COIN)
        }
        else -> cell(Role.COIN)
    }.uppercase()
    if (coin.isEmpty()) throw Skip(CsvImportSkipReason.MISSING_FIELD)
    // Nur Geld («EUR»-Einzahlung u. ä.): kein Krypto
    if (CsvImport.isFiat(coin)) throw Skip(CsvImportSkipReason.NOT_CRYPTO)

    val time = parseTime(cell(Role.DATE), zone, monthFirst) ?: throw Skip(CsvImportSkipReason.BAD_DATE)
    // Ohne Versatz: Kalenderzeit für den Schlüssel (unabhängig von der Zeitzone)
    val local = localTime(cell(Role.DATE), monthFirst)

    val signed = number(Role.AMOUNT) ?: number(Role.AMOUNT_SATS)?.let { it.divide(SATS_PER_BTC) }
        ?: throw Skip(CsvImportSkipReason.MISSING_FIELD)
    val amount = signed.abs()
    if (amount.signum() == 0) throw Skip(CsvImportSkipReason.BAD_NUMBER)

    val pair = cell(Role.PAIR)
    val pairQuote = pair.split('/', '-').takeIf { it.size == 2 }?.get(1)?.trim().orEmpty()
    val quote = cell(Role.NET_CURRENCY).ifEmpty { cell(Role.QUOTE) }.ifEmpty { pairQuote }.uppercase().ifEmpty { null }

    var price = number(Role.PRICE)?.abs()?.takeIf { it.signum() > 0 }
    if (price == null && (kind == CsvImportKind.BUY || kind == CsvImportKind.SELL)) {
        price = number(Role.FIAT_AMOUNT)?.abs()?.takeIf { it.signum() > 0 }
            ?.let { CsvImport.derive(it, amount) }
    }
    // Tausch: kein Preis, sondern Wert (Fiat-Betrag, sonst Preis × Menge) — nur in Geld, sonst unbekannt
    var swapValue: BigDecimal? = null
    var toCoin: String? = null
    var toAmount: BigDecimal? = null
    if (kind == CsvImportKind.SWAP) {
        swapValue = (number(Role.FIAT_AMOUNT)?.abs()?.takeIf { it.signum() > 0 } ?: price?.multiply(amount))
            ?.takeIf { quote != null && quote in CsvImport.MONEY }
        price = null
        toCoin = cell(Role.TO_COIN).uppercase().ifEmpty { null }
        toAmount = number(Role.TO_AMOUNT)?.abs()?.takeIf { it.signum() > 0 }
        if (toCoin == null || toAmount == null || toCoin == coin) { toCoin = null; toAmount = null }
    }
    if (price != null && quote == null) throw Skip(CsvImportSkipReason.MISSING_FIELD)

    var fee = number(Role.FEE)?.abs()?.takeIf { it.signum() > 0 }
    var feeUnit = cell(Role.FEE_UNIT).uppercase().ifEmpty { null }
    if (fee != null) {
        if (feeUnit in setOf("SATS", "SAT", "SATOSHI", "SATOSHIS")) {
            fee = fee.divide(SATS_PER_BTC)
            feeUnit = "BTC"
        }
        if (feeUnit == null) feeUnit = quote ?: throw Skip(CsvImportSkipReason.MISSING_FIELD)
    } else {
        feeUnit = null
    }

    // Swissquote: «00000000» = ohne Auftrag (Gebühren, Zahlungen)
    val externalId = cell(Role.ID).takeIf { id -> id.isNotEmpty() && id.any { it != '0' } }

    val draft = CsvImportRow(
        line = rec.line,
        kind = kind,
        rawKind = rawKind,
        coin = coin,
        amount = amount,
        price = price,
        quote = quote,
        fee = fee,
        feeUnit = feeUnit,
        timeMillis = time,
        externalId = externalId,
        key = CsvImport.key(format, kind, coin, amount, price, time, externalId, local),
        toCoin = toCoin,
        toAmount = toAmount,
        swapValue = swapValue,
        localTime = local,
    )
    // Gegen Stablecoin bzw. Krypto-Coin: Tausch; der ursprüngliche Kauf/Verkauf bleibt für die früheren Schlüssel
    val stableAmount = if ((kind == CsvImportKind.BUY || kind == CsvImportKind.SELL) &&
        (CsvImport.isStable(quote) || CsvImport.isCryptoQuote(quote))
    ) {
        number(Role.FIAT_AMOUNT)?.abs()?.takeIf { it.signum() > 0 }
    } else null
    val swapped = stableSwap(format, draft, stableAmount)
    if (swapped != null) origins[rec.line] = draft
    return (swapped ?: draft) to (if (signed.signum() < 0) -1 else 1)
}
