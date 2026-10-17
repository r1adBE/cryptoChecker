package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Binance-Exporte → [CsvImportRow]. Gespiegelt in `CsvImportBinance.swift`. Aufbau nur aus den öffentlichen
 * Spaltenbeschreibungen bekannt (synthetische Testdateien in `testdata/csv/`), echte Beispiele folgen.
 *
 * Transaktionsverlauf (Wallet › Transaktionsverlauf › Exportieren): «User_ID, UTC_Time, Account, Operation, Coin, Change,
 * Remark» — jede Zeile ist eine Buchung eines Coins auf einem Konto (Change mit Vorzeichen):
 *  - Handel («Transaction Buy/Spend/Sold/Revenue», «Buy», «Sell», «Transaction Related», «Binance Convert», «Auto-Invest»,
 *    «Large OTC trading», «P2P Trading», «Buy Crypto») und Gebühr («Transaction Fee», «Fee»): Zeilen mit gleicher Zeit und
 *    gleichem Konto = ein Geschäft (Mengen je Coin zusammengezählt). Ein Coin ab, einer zu: gegen Fiat ([CsvImport.isFiat])
 *    Kauf bzw. Verkauf (Preis = Fiat-Betrag / Menge), sonst Tausch (Wert nur mit USDT-Seite, [CsvImport.stableSwapValue]).
 *    Nur ein Coin zu (Kartenkauf) bzw. nur ab: Kauf bzw. Verkauf ohne Preis. Nur Gebühr: Gebühr-Zeile (Veräusserung).
 *    Gebühren in zwei Coins → [CsvImportSkipReason.TWO_FEES]; mehrere Coins je Seite (Kleinbeträge in BNB, zwei Geschäfte
 *    in derselben Sekunde) → [CsvImportSkipReason.MISSING_FIELD] (Prüfliste, von Hand erfassen).
 *  - «Deposit» / «Withdraw» → Einzahlung / Auszahlung (Fiat → [CsvImportSkipReason.NOT_CRYPTO]); die Auszahlung zählt
 *    brutto (Change enthält die Netzwerkgebühr), die Differenz zur Einzahlung woanders wird beim Verknüpfen die Gebühr.
 *  - Zinsen, Staking-, Launchpool-, Airdrop-, Empfehlungs- und Cashback-Gutschriften → Belohnung (nur Zugänge).
 *  - Verschiebungen zwischen eigenen Konten (Spot ↔ Funding/Earn/Futures, Subscription/Redemption, «ETH 2.0 Staking») →
 *    [CsvImportSkipReason.INTERNAL_MOVE]; alles andere (Futures-Gewinne, Margin, Funding Fee …) → Prüfliste
 *    ([CsvImportSkipReason.UNSUPPORTED_KIND]).
 *
 * Handelsverlauf (Orders › Spot-Order › Trade History): heute «Date(UTC), Pair, Side, Price, Executed, Amount, Fee» mit
 * Einheit an der Zahl («0.00100000BTC»), früher «Date(UTC), Market, Type, Price, Amount, Total, Fee, Fee Coin» — je
 * Ausführung ein Kauf/Verkauf mit Preis; gegen Stablecoin oder Krypto-Coin ein Tausch ([CsvImportParser.stableSwap]).
 *
 * Nie doppelt aus beiden Exporten: Schlüssel ohne Preis und mit der Kalenderzeit (UTC ohne Versatz); beide Leser fassen
 * Ausführungen je Sekunde zusammen (Transaktionsverlauf je Konto, Handelsverlauf je Paar und Seite), so ergibt dasselbe
 * Geschäft denselben Schlüssel. Kauf und Verkauf desselben Coins in einer Sekunde kann der Transaktionsverlauf nicht
 * trennen → Prüfliste. Was trotzdem nur fast gleich ist (Ausführungen über mehrere Sekunden, versetzte Zeit), hält die
 * Vorschau als möglichen Doppelten zurück ([possibleDuplicates]).
 */
internal object CsvImportBinance {

    /** Spalten des Transaktionsverlaufs (normalisiert). */
    private val LEDGER = setOf("utctime", "operation", "coin", "change")

    /** Spalten des Handelsverlaufs, heutiger Aufbau. */
    private val TRADES = setOf("dateutc", "pair", "side", "price", "executed", "amount", "fee")

    /** Spalten des Handelsverlaufs, früherer Aufbau. */
    private val TRADES_LEGACY = setOf("dateutc", "market", "type", "price", "amount", "total", "fee", "feecoin")

    fun matches(names: Set<String>): Boolean =
        names.containsAll(LEDGER) || names.containsAll(TRADES) || names.containsAll(TRADES_LEGACY)

    fun build(names: List<String>, data: List<CsvImportParser.Record>, zone: String): CsvImportResult =
        if (names.toSet().containsAll(LEDGER)) ledger(names, data, zone) else trades(names, data, zone)

    // ---------- Transaktionsverlauf ----------

    /** Art einer Zeile des Transaktionsverlaufs. */
    private enum class Op { TRADE, FEE, DEPOSIT, WITHDRAWAL, REWARD, INTERNAL, UNKNOWN }

    private val FEE_OPS = setOf("fee", "transactionfee")
    private val TRADE_OPS = setOf(
        "buy", "sell", "transactionbuy", "transactionspend", "transactionsold", "transactionrevenue", "transactionrelated",
        "binanceconvert", "largeotctrading", "autoinvesttransaction", "p2ptrading", "buycrypto", "smallassetsexchangebnb",
    )
    private val DEPOSIT_OPS = setOf("deposit", "fiatdeposit")
    private val WITHDRAWAL_OPS = setOf("withdraw", "withdrawal", "fiatwithdraw", "fiatwithdrawal")

    /** Teilwörter einer Verschiebung zwischen eigenen Konten (vor den Belohnungen: «Staking Purchase» ist keine). */
    private val INTERNAL_PARTS = listOf("transferbetween", "transferfunds", "subscription", "redemption", "purchase", "eth20staking")

    /** Teilwörter einer Gutschrift (Zins, Staking, Launchpool, Airdrop, Empfehlung, Cashback, Mining). */
    private val REWARD_PARTS = listOf(
        "interest", "reward", "airdrop", "distribution", "cashback", "commission", "kickback", "launchpool", "mining",
        "bonus", "rebate",
    )

    private fun op(normalized: String): Op = when {
        normalized in FEE_OPS -> Op.FEE
        normalized in TRADE_OPS -> Op.TRADE
        normalized in DEPOSIT_OPS -> Op.DEPOSIT
        normalized in WITHDRAWAL_OPS -> Op.WITHDRAWAL
        INTERNAL_PARTS.any { it in normalized } -> Op.INTERNAL
        REWARD_PARTS.any { it in normalized } -> Op.REWARD
        else -> Op.UNKNOWN
    }

    private class Leg(
        val line: Int,
        val op: Op,
        val rawKind: String,
        val coin: String,
        val change: BigDecimal,
        val time: Long,
        val local: Long?,
    )

    private fun ledger(names: List<String>, data: List<CsvImportParser.Record>, zone: String): CsvImportResult {
        val iTime = names.indexOf("utctime")
        val iAccount = names.indexOf("account").takeIf { it >= 0 }
        val iOp = names.indexOf("operation")
        val iCoin = names.indexOf("coin")
        val iChange = names.indexOf("change")
        val rows = ArrayList<CsvImportRow>()
        val skipped = ArrayList<CsvImportSkip>()
        // Handel und Gebühr: je Zeit und Konto eine Gruppe (Reihenfolge der Datei)
        val groups = LinkedHashMap<String, MutableList<Leg>>()
        for (rec in data) {
            fun cell(i: Int?) = i?.let { rec.cells.getOrNull(it) }.orEmpty()
            val rawKind = cell(iOp)
            val coin = cell(iCoin).uppercase()
            val change = decimal(cell(iChange))
            val timeText = cell(iTime)
            val time = CsvImportParser.parseTime(timeText, zone)
            when {
                rawKind.isEmpty() || coin.isEmpty() -> skipped += CsvImportSkip(rec.line, CsvImportSkipReason.MISSING_FIELD)
                change == null -> skipped += CsvImportSkip(rec.line, CsvImportSkipReason.BAD_NUMBER)
                time == null -> skipped += CsvImportSkip(rec.line, CsvImportSkipReason.BAD_DATE)
                else -> {
                    val leg = Leg(rec.line, op(CsvImportParser.normalize(rawKind)), rawKind, coin, change, time, CsvImportParser.localTime(timeText))
                    when (leg.op) {
                        Op.TRADE, Op.FEE -> groups.getOrPut(timeText.trim() + "|" + cell(iAccount).trim()) { ArrayList() } += leg
                        Op.DEPOSIT, Op.WITHDRAWAL -> move(leg, rows, skipped)
                        Op.REWARD -> reward(leg, rows, skipped)
                        Op.INTERNAL -> skipped += CsvImportSkip(leg.line, CsvImportSkipReason.INTERNAL_MOVE)
                        Op.UNKNOWN -> skipped += CsvImportSkip(leg.line, CsvImportSkipReason.UNSUPPORTED_KIND)
                    }
                }
            }
        }
        groups.values.forEach { trade(it, rows, skipped) }
        return CsvImport.finish(CsvImportFormat.BINANCE, rows, skipped, zone, localTimes = true)
    }

    private fun move(leg: Leg, rows: MutableList<CsvImportRow>, skipped: MutableList<CsvImportSkip>) {
        val amount = leg.change.abs()
        when {
            CsvImport.isFiat(leg.coin) -> skipped += CsvImportSkip(leg.line, CsvImportSkipReason.NOT_CRYPTO)
            amount.signum() == 0 -> skipped += CsvImportSkip(leg.line, CsvImportSkipReason.BAD_NUMBER)
            else -> {
                val kind = if (leg.op == Op.DEPOSIT) CsvImportKind.DEPOSIT else CsvImportKind.WITHDRAWAL
                rows += row(leg.line, kind, leg.rawKind, leg.coin, amount, null, null, null, null, leg)
            }
        }
    }

    private fun reward(leg: Leg, rows: MutableList<CsvImportRow>, skipped: MutableList<CsvImportSkip>) {
        when {
            CsvImport.isFiat(leg.coin) -> skipped += CsvImportSkip(leg.line, CsvImportSkipReason.NOT_CRYPTO)
            // Abgang mit Belohnungswort (z. B. Rückbuchung) ist keine Gutschrift: Prüfliste
            leg.change.signum() <= 0 -> skipped += CsvImportSkip(leg.line, CsvImportSkipReason.UNSUPPORTED_KIND)
            else -> rows += row(leg.line, CsvImportKind.REWARD, leg.rawKind, leg.coin, leg.change, null, null, null, null, leg)
        }
    }

    /** Ein Geschäft aus den Zeilen einer Gruppe (gleiche Zeit, gleiches Konto). */
    private fun trade(group: List<Leg>, rows: MutableList<CsvImportRow>, skipped: MutableList<CsvImportSkip>) {
        fun skipAll(reason: CsvImportSkipReason) = group.forEach { skipped += CsvImportSkip(it.line, reason) }
        val nets = LinkedHashMap<String, BigDecimal>()
        val fees = LinkedHashMap<String, BigDecimal>()
        for (leg in group) {
            val sums = if (leg.op == Op.FEE) fees else nets
            sums[leg.coin] = (sums[leg.coin] ?: BigDecimal.ZERO).add(leg.change)
        }
        // Ein Coin in derselben Sekunde gekauft UND verkauft: Summen verschleiern zwei Geschäfte, der Handelsverlauf hätte
        // zwei Zeilen — nicht raten (sonst doppelt oder falsch), sondern prüfen lassen
        val directions = group.filter { it.op == Op.TRADE }.groupBy { it.coin }.values.map { legs -> legs.map { it.change.signum() }.toSet() }
        if (directions.any { 1 in it && -1 in it }) return skipAll(CsvImportSkipReason.MISSING_FIELD)
        val given = nets.filterValues { it.signum() < 0 }
        val received = nets.filterValues { it.signum() > 0 }
        val feeEntry = fees.filterValues { it.signum() != 0 }
        if (feeEntry.size > 1) return skipAll(CsvImportSkipReason.TWO_FEES)
        val fee = feeEntry.entries.firstOrNull()
        val first = group.first()
        val line = group.minOf { it.line }
        // Art wie in der Datei: Zeile des gekauften Coins beim Kauf, sonst des abgegebenen
        fun rawKind(coin: String) = group.first { it.op == Op.TRADE && it.coin == coin }.rawKind
        when {
            given.isEmpty() && received.isEmpty() -> {
                // Nur Gebühr: Veräusserung der Gebühr-Coins zum Tageskurs
                val f = fee ?: return skipAll(CsvImportSkipReason.BAD_NUMBER)
                if (CsvImport.isFiat(f.key)) return skipAll(CsvImportSkipReason.NOT_CRYPTO)
                rows += row(line, CsvImportKind.FEE, first.rawKind, f.key, f.value.abs(), null, null, null, null, first)
            }
            given.size > 1 || received.size > 1 -> skipAll(CsvImportSkipReason.MISSING_FIELD)
            given.isEmpty() || received.isEmpty() -> {
                // Nur eine Seite (Kartenkauf, P2P): Kauf bzw. Verkauf ohne Preis
                val (coin, amount) = (received.entries.firstOrNull() ?: given.entries.first()).let { it.key to it.value.abs() }
                if (CsvImport.isFiat(coin)) return skipAll(CsvImportSkipReason.NOT_CRYPTO)
                val kind = if (received.isNotEmpty()) CsvImportKind.BUY else CsvImportKind.SELL
                rows += row(line, kind, rawKind(coin), coin, amount, null, null, fee?.value?.abs(), fee?.key, first)
            }
            else -> {
                val out = given.entries.first()
                val inn = received.entries.first()
                val outAmount = out.value.abs()
                val inAmount = inn.value
                val feeAmount = fee?.value?.abs()
                val feeUnit = fee?.key
                val outFiat = CsvImport.isFiat(out.key)
                val inFiat = CsvImport.isFiat(inn.key)
                when {
                    outFiat && inFiat -> skipAll(CsvImportSkipReason.NOT_CRYPTO)
                    outFiat -> {
                        val price = outAmount.divide(inAmount, CsvImport.DERIVED_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros()
                        rows += row(line, CsvImportKind.BUY, rawKind(inn.key), inn.key, inAmount, price, out.key, feeAmount, feeUnit, first)
                    }
                    inFiat -> {
                        val price = inAmount.divide(outAmount, CsvImport.DERIVED_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros()
                        rows += row(line, CsvImportKind.SELL, rawKind(out.key), out.key, outAmount, price, inn.key, feeAmount, feeUnit, first)
                    }
                    else -> {
                        val (value, valueQuote) = CsvImport.stableSwapValue(out.key, outAmount, inn.key, inAmount)
                        rows += row(line, CsvImportKind.SWAP, rawKind(out.key), out.key, outAmount, null, valueQuote, feeAmount, feeUnit, first)
                            .copy(
                                toCoin = inn.key,
                                toAmount = inAmount,
                                swapValue = value,
                                stableSwap = CsvImport.isStable(out.key) || CsvImport.isStable(inn.key),
                            )
                    }
                }
            }
        }
    }

    /** Zeile mit Schlüssel ohne Preis (siehe Kopf), Zeit von [leg]. */
    private fun row(
        line: Int,
        kind: CsvImportKind,
        rawKind: String,
        coin: String,
        amount: BigDecimal,
        price: BigDecimal?,
        quote: String?,
        fee: BigDecimal?,
        feeUnit: String?,
        leg: Leg,
    ): CsvImportRow = CsvImportRow(
        line = line,
        kind = kind,
        rawKind = rawKind,
        coin = coin,
        amount = amount,
        price = price,
        quote = quote,
        fee = fee?.takeIf { it.signum() > 0 },
        feeUnit = feeUnit.takeIf { fee != null && fee.signum() > 0 },
        timeMillis = leg.time,
        externalId = null,
        key = CsvImport.key(CsvImportFormat.BINANCE, kind, coin, amount, null, leg.time, null, leg.local),
        localTime = leg.local,
    )

    // ---------- Handelsverlauf ----------

    /** Quote-Währungen der Binance-Paare, längste zuerst («BTCFDUSD» endet auf FDUSD, nicht USD). */
    private val QUOTES = listOf(
        "FDUSD", "USDT", "USDC", "BUSD", "TUSD", "USDP", "BIDR", "IDRT", "EURI", "DAI", "BTC", "ETH", "BNB", "EUR", "GBP",
        "TRY", "BRL", "AUD", "JPY", "PLN", "RON", "CZK", "ZAR", "UAH", "ARS", "MXN", "COP", "USD", "XRP", "TRX", "DOGE",
    ).sortedByDescending { it.length }

    /** «BTCUSDT» → (BTC, USDT); unbekannte Quote = null. */
    internal fun splitPair(pair: String): Pair<String, String>? {
        val p = pair.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        val quote = QUOTES.firstOrNull { p.endsWith(it) && p.length > it.length } ?: return null
        return p.removeSuffix(quote) to quote
    }

    /** «0.00100000BTC» → (0.001, BTC); ohne Einheit (früherer Aufbau) → (Zahl, null); unlesbar = null. */
    private fun withUnit(text: String): Pair<BigDecimal, String?>? {
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

    private fun trades(names: List<String>, data: List<CsvImportParser.Record>, zone: String): CsvImportResult {
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
        val rows = groups.values.mapNotNull { order(it, skipped) }
        return CsvImport.finish(CsvImportFormat.BINANCE, rows, skipped, zone, localTimes = true)
    }

    /**
     * Ausführungen einer Order in derselben Sekunde → ein Kauf/Verkauf mit Summen (so fasst sie auch der
     * Transaktionsverlauf zusammen: gleicher Schlüssel, nie doppelt); Preis = Quote-Summe / Menge (bei einer Ausführung
     * der Preis der Datei). Gebühren in verschiedenen Einheiten → [CsvImportSkipReason.TWO_FEES].
     */
    private fun order(fills: List<Fill>, skipped: MutableList<CsvImportSkip>): CsvImportRow? {
        val first = fills.first()
        val feeUnits = fills.mapNotNull { it.feeUnit }.toSet()
        if (feeUnits.size > 1) {
            fills.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.TWO_FEES) }
            return null
        }
        val amount = fills.fold(BigDecimal.ZERO) { sum, f -> sum + f.amount }
        val total = fills.fold(BigDecimal.ZERO) { sum, f -> sum + f.total }
        val fee = fills.mapNotNull { it.fee }.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { sum, f -> sum + f }
        val price = if (fills.size == 1) first.price
        else total.divide(amount, CsvImport.DERIVED_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros()
        val draft = CsvImportRow(
            line = first.line,
            kind = first.kind,
            rawKind = first.rawKind,
            coin = first.base,
            amount = amount,
            price = price,
            quote = first.quote,
            fee = fee,
            feeUnit = feeUnits.firstOrNull().takeIf { fee != null },
            timeMillis = first.time,
            externalId = null,
            key = CsvImport.key(CsvImportFormat.BINANCE, first.kind, first.base, amount, null, first.time, null, first.local),
            localTime = first.local,
        )
        // Gegen Stablecoin oder Krypto-Coin: Tausch mit dem Quote-Betrag der Datei
        return CsvImportParser.stableSwap(CsvImportFormat.BINANCE, draft, total) ?: draft
    }

    /** Zeitraum, in dem ein Binance-Geschäft mit gleicher Art und gleichem Coin als möglicherweise dasselbe gilt. */
    private const val NEAR_MILLIS = 5_000L

    /** Arten, die Transaktions- und Handelsverlauf beide enthalten. */
    private val TRADE_KINDS = setOf(CsvImportKind.BUY, CsvImportKind.SELL, CsvImportKind.SWAP)

    /** «BINANCE|ART|COIN|L<ms>|…» (ohne «#n») → (Art, Coin, Kalenderzeit); andere Schlüssel = null. */
    private fun tradeKey(key: String): Triple<String, String, Long>? {
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
    fun possibleDuplicates(rows: List<CsvImportRow>, existingKeys: Set<String>): List<CsvImportRow> {
        val stored = existingKeys.mapNotNull { tradeKey(it) }
        if (stored.isEmpty()) return emptyList()
        return rows.filter { r ->
            val own = tradeKey(r.key) ?: return@filter false
            stored.any { (kind, coin, time) -> kind == own.first && coin == own.second && kotlin.math.abs(time - own.third) <= NEAR_MILLIS }
        }
    }

    // ---------- Zahlen ----------

    private val EXPONENT = Regex("^-?[0-9]+(\\.[0-9]+)?[eE][-+]?[0-9]{1,3}$")

    /** Zahl mit Punkt als Dezimalzeichen; auch «1E-8» (Binance schreibt sehr kleine Beträge so). */
    private fun decimal(text: String): BigDecimal? {
        val t = text.trim()
        if (EXPONENT.matches(t)) return runCatching { BigDecimal(t).stripTrailingZeros() }.getOrNull()
        return CsvImportParser.parseNumber(t, '.')
    }
}
