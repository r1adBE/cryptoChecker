package com.cryptochecker.app.domain.portfolio.csvimport

import java.math.BigDecimal

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
 *    Gebühren in mehreren Coins: eine am Geschäft, die übrigen als eigene Gebühr-Zeilen ([CsvImport.splitFees]). Kauf und
 *    Verkauf desselben Coins in der Sekunde mit genau zwei Coins (A → B, B → A) → zwei Geschäfte; mehrere Coins je Seite
 *    (Kleinbeträge in BNB, zwei Geschäfte in derselben Sekunde) → [CsvImportSkipReason.AMBIGUOUS_GROUP] (Prüfliste).
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
 * Geschäft denselben Schlüssel. Kauf und Verkauf desselben Coins in einer Sekunde trennt der Transaktionsverlauf nur
 * mit genau zwei Coins, sonst Prüfliste. Was trotzdem nur fast gleich ist (Ausführungen über mehrere Sekunden, versetzte Zeit), hält die
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

    /**
     * Verschiebungen zwischen eigenen Konten mit genauem Namen: «ETH 2.0 Staking» (ETH → BETH) und ihre Rücknahme. Genau,
     * nicht als Teilwort — sonst fiele «ETH 2.0 Staking Rewards» (Belohnung) als Verschiebung weg.
     */
    private val INTERNAL_OPS = setOf("eth20staking", "eth20stakingwithdrawals")

    /** Teilwörter einer Verschiebung zwischen eigenen Konten (vor den Belohnungen: «Staking Purchase» ist keine). */
    private val INTERNAL_PARTS = listOf("transferbetween", "transferfunds", "subscription", "redemption", "purchase")

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
        normalized in INTERNAL_OPS -> Op.INTERNAL
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

    /**
     * Ein Geschäft aus den Zeilen einer Gruppe (gleiche Zeit, gleiches Konto). Kauf UND Verkauf desselben Coins in der
     * Sekunde: eindeutig nur mit genau zwei Coins, die beide in beide Richtungen gehen (A → B und B → A; Gebühren nur in
     * A oder B, je am Geschäft, das diesen Coin erhält) → zwei Geschäfte; sonst wie mehrere Coins je Seite
     * [CsvImportSkipReason.AMBIGUOUS_GROUP] (nicht raten: Summen verschleiern, welcher Betrag zu welchem Geschäft gehört).
     */
    private fun trade(group: List<Leg>, rows: MutableList<CsvImportRow>, skipped: MutableList<CsvImportSkip>) {
        fun skipAll(reason: CsvImportSkipReason) = group.forEach { skipped += CsvImportSkip(it.line, reason) }
        val tradeLegs = group.filter { it.op == Op.TRADE }
        val feeLegs = group.filter { it.op == Op.FEE }
        val feeSums = LinkedHashMap<String, BigDecimal>()
        for (leg in feeLegs) feeSums[leg.coin] = (feeSums[leg.coin] ?: BigDecimal.ZERO).add(leg.change)
        val fees = feeSums.filterValues { it.signum() != 0 }.map { it.key to it.value.abs() }
        val byCoin = tradeLegs.groupBy { it.coin }
        val bothWays = byCoin.filterValues { legs -> legs.any { it.change.signum() > 0 } && legs.any { it.change.signum() < 0 } }
        if (bothWays.isNotEmpty()) {
            if (byCoin.size != 2 || bothWays.size != 2 || fees.any { it.first !in byCoin.keys }) {
                return skipAll(CsvImportSkipReason.AMBIGUOUS_GROUP)
            }
            val (a, b) = byCoin.keys.toList()
            for ((out, inn) in listOf(a to b, b to a)) {
                val outLegs = byCoin.getValue(out).filter { it.change.signum() < 0 }
                val inLegs = byCoin.getValue(inn).filter { it.change.signum() > 0 }
                val outSum = outLegs.fold(BigDecimal.ZERO) { s, l -> s + l.change }.abs()
                val inSum = inLegs.fold(BigDecimal.ZERO) { s, l -> s + l.change }
                val ownFeeLegs = feeLegs.filter { it.coin == inn }
                emit((outLegs + inLegs + ownFeeLegs).sortedBy { it.line }, out to outSum, inn to inSum,
                    fees.filter { it.first == inn }, rows, skipped)
            }
            return
        }
        val nets = LinkedHashMap<String, BigDecimal>()
        for (leg in tradeLegs) nets[leg.coin] = (nets[leg.coin] ?: BigDecimal.ZERO).add(leg.change)
        val given = nets.filterValues { it.signum() < 0 }
        val received = nets.filterValues { it.signum() > 0 }
        if (given.size > 1 || received.size > 1) return skipAll(CsvImportSkipReason.AMBIGUOUS_GROUP)
        emit(group, given.entries.firstOrNull()?.let { it.key to it.value.abs() }, received.entries.firstOrNull()?.toPair(),
            fees, rows, skipped)
    }

    /**
     * Zeilen eines Geschäfts aus [legs]: höchstens ein Coin ab ([given]) und einer zu ([received]), [fees] je Einheit.
     * Gebühren in mehreren Einheiten: eine am Geschäft, die übrigen als eigene Gebühr-Zeilen ([CsvImport.splitFees]).
     */
    private fun emit(
        legs: List<Leg>,
        given: Pair<String, BigDecimal>?,
        received: Pair<String, BigDecimal>?,
        fees: List<Pair<String, BigDecimal>>,
        rows: MutableList<CsvImportRow>,
        skipped: MutableList<CsvImportSkip>,
    ) {
        fun skipAll(reason: CsvImportSkipReason) = legs.forEach { skipped += CsvImportSkip(it.line, reason) }
        val first = legs.first()
        val line = legs.minOf { it.line }
        // Art wie in der Datei: Zeile des gekauften Coins beim Kauf, sonst des abgegebenen
        fun rawKind(coin: String) = legs.firstOrNull { it.op == Op.TRADE && it.coin == coin }?.rawKind ?: first.rawKind
        fun feeRow(unit: String, amount: BigDecimal, at: Int) {
            val kind = legs.firstOrNull { it.op == Op.FEE && it.coin == unit }?.rawKind ?: first.rawKind
            rows += row(at, CsvImportKind.FEE, kind, unit, amount, null, null, null, null, first)
        }
        if (given == null && received == null) {
            // Nur Gebühr: Veräusserung der Gebühr-Coins zum Tageskurs (je Einheit eine Zeile; Fiat ist kein Krypto)
            if (fees.isEmpty()) return skipAll(CsvImportSkipReason.BAD_NUMBER)
            if (fees.all { CsvImport.isFiat(it.first) }) return skipAll(CsvImportSkipReason.NOT_CRYPTO)
            if (fees.size == 1) return feeRow(fees[0].first, fees[0].second, line)
            for ((unit, amount) in fees) {
                val own = legs.filter { it.coin == unit }
                if (CsvImport.isFiat(unit)) own.forEach { skipped += CsvImportSkip(it.line, CsvImportSkipReason.NOT_CRYPTO) }
                else feeRow(unit, amount, own.minOfOrNull { it.line } ?: line)
            }
            return
        }
        val split = CsvImport.splitFees(fees, given?.first, received?.first) ?: return skipAll(CsvImportSkipReason.TWO_FEES)
        if (given != null && received != null && CsvImport.isFiat(given.first) && CsvImport.isFiat(received.first)) {
            return skipAll(CsvImportSkipReason.NOT_CRYPTO)
        }
        val one = given == null || received == null
        // Nur eine Seite (beide fehlend ist oben schon erledigt)
        val only = received ?: given ?: return skipAll(CsvImportSkipReason.BAD_NUMBER)
        if (one && CsvImport.isFiat(only.first)) return skipAll(CsvImportSkipReason.NOT_CRYPTO)
        val feeAmount = split.first?.second
        val feeUnit = split.first?.first
        when {
            one -> {
                // Nur eine Seite (Kartenkauf, P2P): Kauf bzw. Verkauf ohne Preis
                val (coin, amount) = only
                val kind = if (received != null) CsvImportKind.BUY else CsvImportKind.SELL
                rows += row(line, kind, rawKind(coin), coin, amount, null, null, feeAmount, feeUnit, first)
            }
            else -> {
                val (out, outAmount) = given
                val (inn, inAmount) = received
                when {
                    CsvImport.isFiat(out) -> {
                        val price = CsvImport.derive(outAmount, inAmount)
                        rows += row(line, CsvImportKind.BUY, rawKind(inn), inn, inAmount, price, out, feeAmount, feeUnit, first)
                    }
                    CsvImport.isFiat(inn) -> {
                        val price = CsvImport.derive(inAmount, outAmount)
                        rows += row(line, CsvImportKind.SELL, rawKind(out), out, outAmount, price, inn, feeAmount, feeUnit, first)
                    }
                    else -> {
                        val (value, valueQuote) = CsvImport.stableSwapValue(out, outAmount, inn, inAmount)
                        rows += row(line, CsvImportKind.SWAP, rawKind(out), out, outAmount, null, valueQuote, feeAmount, feeUnit, first)
                            .copy(
                                toCoin = inn,
                                toAmount = inAmount,
                                swapValue = value,
                                stableSwap = CsvImport.isStable(out) || CsvImport.isStable(inn),
                            )
                    }
                }
            }
        }
        // Weitere Gebühren (andere Einheit): eigene Gebühr-Zeilen statt das Geschäft zu überspringen
        for ((unit, amount) in split.second) feeRow(unit, amount, line)
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

    // Handelsverlauf (splitPair, trades, possibleDuplicates): CsvImportBinanceTrades.kt

    // ---------- Zahlen ----------

    private val EXPONENT = Regex("^-?[0-9]+(\\.[0-9]+)?[eE][-+]?[0-9]{1,3}$")

    /** Zahl mit Punkt als Dezimalzeichen; auch «1E-8» (Binance schreibt sehr kleine Beträge so). */
    internal fun decimal(text: String): BigDecimal? {
        val t = text.trim()
        if (EXPONENT.matches(t)) return runCatching { BigDecimal(t).stripTrailingZeros() }.getOrNull()
        return CsvImportParser.parseNumber(t, '.')
    }
}
