package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import java.math.BigDecimal

/**
 * Coinbase «Transaction report» (Profil › Berichte › Transaktionsbericht CSV) → [CsvImportRow]. Gespiegelt in
 * `CsvImportCoinbase.swift`. Aufbau nur aus der öffentlichen Beschreibung bekannt (synthetische Testdateien in
 * `testdata/csv/`), echte Beispiele folgen.
 *
 * Vor der Kopfzeile stehen ein Hinweistext, «Transactions» und eine Zeile «User, …» (die Kopfzeilensuche überspringt
 * sie). Spalten heute «ID, Timestamp, Transaction Type, Asset, Quantity Transacted, Price Currency, Price at Transaction,
 * Subtotal, Total (inclusive of fees and/or spread), Fees and/or Spread, Notes», früher ohne «ID» und mit «Spot Price
 * Currency»/«Spot Price at Transaction»/«Fees». Beträge tragen teils ein Währungszeichen («€40,000.00»), Mengen teils ein
 * Vorzeichen; Zahlen immer mit Punkt als Dezimalzeichen.
 *  - «Buy»/«Advanced Trade Buy» → Kauf, «Sell»/«Advanced Trade Sell» → Verkauf: Preis = Subtotal / Menge (ohne Gebühr und
 *    Spread, die stehen in «Fees and/or Spread» und werden die Gebühr in der Preiswährung); ohne Subtotal der Spot-Preis.
 *    Preiswährung ein Stablecoin (Advanced Trade gegen USDC) → Tausch ([CsvImportParser.stableSwap]).
 *  - «Convert» → Tausch; die erhaltene Seite steht nur in «Notes» («Converted 0.5 ETH to 0.03 BTC»), Wert = Subtotal.
 *  - «Send» → Auszahlung, «Receive» → Einzahlung (ohne Preis); Fiat-Ein-/Auszahlungen → [CsvImportSkipReason.NOT_CRYPTO].
 *  - «Rewards Income», «Staking Income», «Coinbase Earn», «Learning Reward», «Inflation Reward» … → Belohnung mit Wert.
 *  - Verschiebungen zu Coinbase Pro/Exchange und ins/aus dem Staking («Pro Deposit», «Retail Staking Transfer», «Retail
 *    Eth2 Deprecation») → [CsvImportSkipReason.INTERNAL_MOVE]; ETH2 zählt als ETH (wie bei Kraken).
 *  - Unbekannte Arten → Prüfliste ([CsvImportSkipReason.UNSUPPORTED_KIND]).
 */
object CsvImportCoinbase {

    private val REQUIRED = setOf("timestamp", "transactiontype", "asset", "quantitytransacted")

    fun matches(names: Set<String>): Boolean = names.containsAll(REQUIRED)

    private val BUY = setOf("buy", "advancedtradebuy", "recurringbuy")
    private val SELL = setOf("sell", "advancedtradesell")
    private val WITHDRAWAL = setOf("send", "withdrawal")
    private val DEPOSIT = setOf("receive", "deposit")
    private val INTERNAL = setOf(
        "prodeposit", "prowithdrawal", "exchangedeposit", "exchangewithdrawal", "retailstakingtransfer",
        "retailunstakingtransfer", "retaileth2deprecation",
    )

    /** Teilwörter einer Gutschrift («Rewards Income», «Coinbase Earn», «Subscription Rebate» …). */
    private val REWARD_PARTS = listOf("reward", "income", "earn", "rebate")

    /** Art aus der normalisierten Spalte «Transaction Type»; null = INTERNAL bzw. unbekannt (Grund in [skipReason]). */
    private fun kind(type: String): CsvImportKind? = when {
        type in BUY -> CsvImportKind.BUY
        type in SELL -> CsvImportKind.SELL
        type == "convert" -> CsvImportKind.SWAP
        type in WITHDRAWAL -> CsvImportKind.WITHDRAWAL
        type in DEPOSIT -> CsvImportKind.DEPOSIT
        type in INTERNAL -> null
        REWARD_PARTS.any { it in type } -> CsvImportKind.REWARD
        else -> null
    }

    private fun skipReason(type: String) =
        if (type in INTERNAL) CsvImportSkipReason.INTERNAL_MOVE else CsvImportSkipReason.UNSUPPORTED_KIND

    /** «Converted 0.5 ETH to 0.03 BTC» (Gross/Klein egal, Tausender mit Komma). */
    private val CONVERTED = Regex("converted\\s+([0-9.,]+)\\s+([a-z0-9]+)\\s+to\\s+([0-9.,]+)\\s+([a-z0-9]+)", RegexOption.IGNORE_CASE)

    private class Skip(val reason: CsvImportSkipReason) : Exception()

    fun build(names: List<String>, data: List<CsvImportParser.Record>, zone: String): CsvImportResult {
        fun col(vararg n: String) = n.map { names.indexOf(it) }.firstOrNull { it >= 0 }
        val iId = col("id")
        val iTime = col("timestamp")
        val iType = col("transactiontype")
        val iAsset = col("asset")
        val iQuantity = col("quantitytransacted")
        val iCurrency = col("pricecurrency", "spotpricecurrency")
        val iPrice = col("priceattransaction", "spotpriceattransaction")
        val iSubtotal = col("subtotal")
        val iFee = col("feesandorspread", "fees")
        val iNotes = col("notes")
        val rows = ArrayList<CsvImportRow>()
        val skipped = ArrayList<CsvImportSkip>()
        var localTimes = false
        for (rec in data) {
            fun cell(i: Int?) = i?.let { rec.cells.getOrNull(it) }.orEmpty()
            try {
                val type = CsvImportParser.normalize(cell(iType))
                val kind = kind(type) ?: throw Skip(skipReason(type))
                val coin = asset(cell(iAsset))
                if (coin.isEmpty()) throw Skip(CsvImportSkipReason.MISSING_FIELD)
                if (CsvImport.isFiat(coin)) throw Skip(CsvImportSkipReason.NOT_CRYPTO)
                val timeText = cell(iTime)
                val time = CsvImportParser.parseTime(timeText, zone) ?: throw Skip(CsvImportSkipReason.BAD_DATE)
                val local = CsvImportParser.localTime(timeText)
                if (local != null) localTimes = true
                if (cell(iQuantity).isEmpty()) throw Skip(CsvImportSkipReason.MISSING_FIELD)
                val amount = (money(cell(iQuantity)) ?: throw Skip(CsvImportSkipReason.BAD_NUMBER)).abs()
                if (amount.signum() == 0) throw Skip(CsvImportSkipReason.BAD_NUMBER)
                val quote = cell(iCurrency).uppercase().ifEmpty { null }
                val subtotal = money(cell(iSubtotal))?.abs()?.takeIf { it.signum() > 0 }
                val spot = money(cell(iPrice))?.abs()?.takeIf { it.signum() > 0 }
                // Gebühr und Spread in der Preiswährung; ohne Währung nicht zuordenbar — überspringen statt still weglassen
                val fee = money(cell(iFee))?.abs()?.takeIf { it.signum() > 0 }
                if (fee != null && quote == null) throw Skip(CsvImportSkipReason.MISSING_FIELD)
                val externalId = cell(iId).ifEmpty { null }
                val priced = kind == CsvImportKind.BUY || kind == CsvImportKind.SELL || kind == CsvImportKind.REWARD
                val price = if (!priced) null
                else subtotal?.let { CsvImport.derive(it, amount) } ?: spot
                if (price != null && quote == null) throw Skip(CsvImportSkipReason.MISSING_FIELD)
                var draft = CsvImportRow(
                    line = rec.line,
                    kind = kind,
                    rawKind = cell(iType),
                    coin = coin,
                    amount = amount,
                    price = price,
                    // Preiswährung nur, wo sie zählt (Preis oder Gebühr); bei Send/Receive ohne Gebühr bedeutungslos
                    quote = quote.takeIf { price != null || fee != null },
                    fee = fee,
                    feeUnit = quote.takeIf { fee != null },
                    timeMillis = time,
                    externalId = externalId,
                    key = CsvImport.key(CsvImportFormat.COINBASE, kind, coin, amount, price, time, externalId, local),
                    localTime = local,
                )
                if (kind == CsvImportKind.SWAP) {
                    val m = CONVERTED.find(cell(iNotes)) ?: throw Skip(CsvImportSkipReason.MISSING_FIELD)
                    val toCoin = asset(m.groupValues[4])
                    val toAmount = CsvImportParser.parseNumber(m.groupValues[3], '.')?.abs()?.takeIf { it.signum() > 0 }
                        ?: throw Skip(CsvImportSkipReason.BAD_NUMBER)
                    if (toCoin == coin) throw Skip(CsvImportSkipReason.MISSING_FIELD)
                    val value = (subtotal ?: spot?.multiply(amount, PortfolioDecimal.MC))?.takeIf { quote != null && quote in CsvImport.MONEY }
                    draft = draft.copy(
                        quote = quote.takeIf { value != null || fee != null },
                        toCoin = toCoin,
                        toAmount = toAmount,
                        swapValue = value,
                    )
                }
                rows += CsvImportParser.stableSwap(CsvImportFormat.COINBASE, draft, subtotal) ?: draft
            } catch (s: Skip) {
                skipped += CsvImportSkip(rec.line, s.reason)
            }
        }
        return CsvImport.finish(CsvImportFormat.COINBASE, rows, skipped, zone, localTimes)
    }

    /** Coinbase-Kürzel vereinheitlichen: ETH2 (gestaktes ETH bis 2023) zählt als ETH. */
    private fun asset(raw: String): String = raw.trim().uppercase().let { if (it == "ETH2") "ETH" else it }

    /**
     * Betrag ohne Währungszeichen («€40,000.00», «-$12.50», «CHF 3.00», «EUR 3.00»); Punkt = Dezimalzeichen; leer/unlesbar
     * = null. Wissenschaftliche Schreibweise bleibt ([exponentAt]), wie Excel grosse und kleine Mengen speichert
     * («1.23457E+11», «1.5E-05»): [CsvImportParser.parseNumber] liest sie wie in jedem anderen Format. Ziffern nur 0–9
     * (andere Schriften fallen weg wie Währungszeichen) — wie iOS.
     */
    private fun money(text: String): BigDecimal? {
        val t = text.filterIndexed { i, c ->
            c in '0'..'9' || c == '.' || c == ',' || c == '-' || c == '−' || c == '+' || ((c == 'e' || c == 'E') && exponentAt(text, i))
        }
        return if (t.isEmpty()) null else CsvImportParser.parseNumber(t, '.')
    }

    /**
     * «E»/«e» an [i] ist ein Exponent: direkt nach einer Ziffer und vor einer Ziffer (bzw. Vorzeichen und Ziffer) — ein
     * Währungskürzel («EUR 3.00», «3.00 EUR») nie.
     */
    private fun exponentAt(text: String, i: Int): Boolean {
        fun digit(j: Int) = text.getOrNull(j)?.let { it in '0'..'9' } == true
        val signed = text.getOrNull(i + 1).let { it == '+' || it == '-' || it == '−' }
        return digit(i - 1) && digit(if (signed) i + 2 else i + 1)
    }
}
