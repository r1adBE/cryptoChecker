// Schlüssel, Duplikate und Umrechnung der CSV-Zeilen in USDT; die Datentypen stehen in `CsvImportRow.kt`.
package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** Schlüssel, Duplikate und Umrechnung in USDT (reines Kotlin; iOS `CsvImport` in `CsvImportRow.swift`). */
object CsvImport {

    /**
     * Abgeleiteter Preis bzw. Umrechnung `a / b` mit 34 gültigen Stellen ([PortfolioDecimal.MC], halbe auf gerade),
     * ohne Nullen am Ende — keine feste Zahl Nachkommastellen: 12 rundeten Preise winziger Coins
     * (z. B. 10¹² BABYDOGE für CHF 1234.56 → 1.23456E-9) auf wenige Stellen. iOS: `CsvImport.divide`.
     */
    fun derive(a: BigDecimal, b: BigDecimal): BigDecimal = a.divide(b, PortfolioDecimal.MC).stripTrailingZeros()

    /** Rechenwährung (USDT gilt 1:1). USD ist Fiat: in USDT über den USDT/USD-Tagesschluss ([priceInUsdt]). */
    private val USD_LIKE = setOf("USD", "USDT")

    /** Fiat (mit USD): nur Geschäfte gegen diese Währungen sind Kauf/Verkauf; Ein-/Auszahlungen davon sind kein Krypto. */
    val FIAT = setOf("CHF", "EUR", "USD", "GBP", "JPY", "CAD", "AUD", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF")

    /**
     * Wertwährungen eines Tauschs: Fiat und USDT (Bewertungswährung, gilt 1:1). Nur ein Wert in diesen Währungen
     * gilt als Wert eines Tauschs; sonst unbekannt (Tagesschluss beim Rechnen).
     */
    val MONEY = FIAT + "USDT"

    /**
     * Stablecoins (Liste des Portfolios, [PortfolioStables.COINS], ohne «USD» = Fiat). Kauf/Verkauf dagegen ist
     * Krypto gegen Krypto → Tausch (FR/AT: steuerneutral), der Stablecoin wird ein Bestand.
     */
    val STABLES: Set<String> get() = PortfolioStables.COINS - "USD"

    fun isStable(code: String?): Boolean = code != null && code.trim().uppercase() in STABLES

    /** Fiat-Währung ([FX_FIAT]) — als Coin einer Zeile kein Krypto. */
    fun isFiat(code: String?): Boolean = code != null && code.trim().uppercase() in FX_FIAT

    /**
     * Fiat mit Tageskurs: nie Krypto als Preiswährung, als Coin übersprungen ([isFiat]). Gleich wie
     * `FxRateSource.CURRENCIES` (Test `CsvImportParityTest.fiatListMatchesFxCurrencies`).
     */
    val FX_FIAT = FIAT + setOf(
        "RON", "BGN", "ISK", "TRY", "CNY", "HKD", "SGD", "KRW", "INR", "IDR", "THB",
        "MYR", "PHP", "NZD", "ZAR", "BRL", "MXN", "ILS",
    )

    /**
     * Preiswährung ist ein Krypto-Coin (Paar «ETH/BTC»): weder Geld ([MONEY]), Fiat mit Tageskurs noch Stablecoin
     * (der hat [isStable]). Kauf/Verkauf dagegen ist ein Tausch ([CsvImportParser.stableSwap]).
     */
    fun isCryptoQuote(code: String?): Boolean {
        val c = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return false
        return c !in MONEY && c !in FX_FIAT && c !in STABLES && c.all { it in 'A'..'Z' || it in '0'..'9' }
    }

    /**
     * Wert eines Tauschs mit Stablecoin-Seite: USDT-Seite = Wert in USDT (1:1 wie im Portfolio); andere Stablecoins
     * (USDC …) nicht (gilt wie im Portfolio ihr Tagesschluss) → (null, null).
     * @return (Wert, Währung)
     */
    fun stableSwapValue(coin: String, amount: BigDecimal, toCoin: String, toAmount: BigDecimal): Pair<BigDecimal?, String?> = when {
        coin == PortfolioStables.VALUATION -> amount to PortfolioStables.VALUATION
        toCoin == PortfolioStables.VALUATION -> toAmount to PortfolioStables.VALUATION
        else -> null to null
    }

    /** Exakter Text ohne Exponent und Nullen am Ende («0.00284516», «2.9», «0»). */
    fun text(value: BigDecimal): String {
        if (value.signum() == 0) return "0"
        return value.stripTrailingZeros().toPlainString()
    }

    /**
     * Schlüssel gegen doppelten Import:
     *  - mit Kennung: «<Format>|<Kennung>» (Relai «Operation ID» je Zeile eindeutig)
     *  - Swissquote: «SWISSQUOTE|<Order #>|<Zeit>|<Menge>» (Teilausführungen teilen die Auftragsnummer)
     *  - ohne Kennung: «<Format>|<Art>|<Coin>|<Zeit>|<Menge>|<Preis oder ->»
     * <Zeit>: UTC-ms; steht die Zeit ohne Versatz in der Datei ([localTime]), «L» + Kalenderzeit — so ändert eine
     * andere Zeitzone beim zweiten Import den Schlüssel nicht.
     * Gleiche Schlüssel innerhalb einer Datei bekommen «#2», «#3» … ([withOccurrence]).
     */
    fun key(
        format: CsvImportFormat,
        kind: CsvImportKind,
        coin: String,
        amount: BigDecimal,
        price: BigDecimal?,
        timeMillis: Long,
        externalId: String?,
        localTime: Long? = null,
    ): String {
        val time = localTime?.let { "L$it" } ?: timeMillis.toString()
        if (externalId != null) {
            return if (format == CsvImportFormat.SWISSQUOTE) "SWISSQUOTE|$externalId|$time|${text(amount)}"
            else "${format.name}|$externalId"
        }
        return "${format.name}|${kind.name}|$coin|$time|${text(amount)}|${price?.let { text(it) } ?: "-"}"
    }

    /** Wiederholte Schlüssel eindeutig machen (zweites Vorkommen «#2» usw.), Reihenfolge bleibt. */
    fun withOccurrence(keys: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return keys.map { k ->
            val n = (seen[k] ?: 0) + 1
            seen[k] = n
            if (n == 1) k else "$k#$n"
        }
    }

    /**
     * Gebühr eines Tauschs aus beiden Seiten: eine Seite → diese; beide in derselben Einheit (Gross/Klein egal,
     * beide ohne Einheit zählt als gleich) → Summe; beide in verschiedenen Einheiten → null (Zeilen überspringen).
     * @return (Gebühr, Einheit), Gebühr null = keine Gebühr
     */
    fun swapFee(givenFee: BigDecimal?, givenUnit: String?, receivedFee: BigDecimal?, receivedUnit: String?): Pair<BigDecimal?, String?>? {
        if (givenFee == null) return receivedFee to receivedUnit
        if (receivedFee == null) return givenFee to givenUnit
        val gu = givenUnit?.trim()?.uppercase().orEmpty()
        val ru = receivedUnit?.trim()?.uppercase().orEmpty()
        if (gu != ru) return null
        return givenFee.add(receivedFee) to givenUnit
    }

    /**
     * Gebühren eines Geschäfts in mehreren Einheiten (Binance, Kraken; [fees] = (Einheit, Menge > 0), je Einheit
     * höchstens einmal): eine bleibt am Geschäft, jede weitere wird eine eigene Gebühr-Zeile ([CsvImportKind.FEE],
     * Veräusserung zum Tageskurs wie eine Gebühr im dritten Coin) — statt das ganze Geschäft zu überspringen. Am Geschäft
     * bleibt, unabhängig von der Reihenfolge (beide Binance-Exporte ergeben dieselben Zeilen): die Fiat-Gebühr, sonst die
     * im abgegebenen [given], dann im erhaltenen Coin [received], sonst die alphabetisch erste. Zwei Fiat-Gebühren lassen
     * sich nicht als Gebühr-Zeile buchen → null ([CsvImportSkipReason.TWO_FEES]).
     * @return (Gebühr am Geschäft oder null, weitere Gebühren in Reihenfolge von [fees])
     */
    fun splitFees(
        fees: List<Pair<String, BigDecimal>>,
        given: String?,
        received: String?,
    ): Pair<Pair<String, BigDecimal>?, List<Pair<String, BigDecimal>>>? {
        if (fees.size <= 1) return fees.firstOrNull() to emptyList()
        if (fees.count { isFiat(it.first) } > 1) return null
        val primary = fees.firstOrNull { isFiat(it.first) }
            ?: fees.firstOrNull { it.first == given }
            ?: fees.firstOrNull { it.first == received }
            ?: fees.sortedBy { it.first }.first()
        return primary to fees.filter { it.first != primary.first }
    }

    /**
     * Gebühr verbraucht die ganze erhaltene Menge (wie [com.cryptochecker.app.domain.portfolio.PortfolioTxForm.feeConsumesReceived]):
     * Kauf/Einzahlung/Belohnung mit Gebühr im Coin der Zeile ≥ Menge; Tausch mit Gebühr im erhaltenen Coin ≥ erhaltener Menge.
     */
    fun feeConsumesAmount(row: CsvImportRow): Boolean {
        val fee = row.fee?.takeIf { it.signum() > 0 } ?: return false
        val unit = row.feeUnit?.trim()?.uppercase() ?: return false
        return when (row.kind) {
            CsvImportKind.BUY, CsvImportKind.DEPOSIT, CsvImportKind.REWARD -> unit == row.coin.trim().uppercase() && fee >= row.amount
            CsvImportKind.SWAP -> {
                val to = row.toCoin?.trim()?.uppercase()
                val toAmount = row.toAmount
                to != null && toAmount != null && unit == to && fee >= toAmount
            }
            else -> false
        }
    }

    /**
     * Zeilen, deren Schlüssel schon gespeichert ist, als Duplikate abtrennen. [existingKeys] = alle gespeicherten
     * Herkunftsschlüssel samt `linkedImportKey` verknüpfter Umbuchungen: jede ihrer Seiten gilt als importiert.
     */
    fun splitDuplicates(rows: List<CsvImportRow>, existingKeys: Set<String>): CsvImportSplit {
        val (dup, fresh) = rows.partition { r -> r.key in existingKeys }
        return CsvImportSplit(fresh = fresh, duplicates = dup)
    }

    /**
     * Gemeinsamer Abschluss der Börsen-Leser (Kraken, Binance, Coinbase, Bitpanda): Zeilen nach Zeit (dann Zeile) — die
     * Leser bilden Geschäfte aus Gruppen und geben sie nicht in Dateireihenfolge aus —, gleiche Schlüssel «#2» …
     * ([withOccurrence]), Gebühr ≥ erhaltene Menge übersprungen ([feeConsumesAmount]), Übersprungene nach Zeile.
     * [localTimes]: die Datei schreibt Zeiten ohne Versatz (dann zählt die Zeitzone, sofern Zeilen übrig bleiben).
     */
    fun finish(
        format: CsvImportFormat,
        rows: List<CsvImportRow>,
        skipped: List<CsvImportSkip>,
        zone: String,
        localTimes: Boolean,
    ): CsvImportResult {
        val sorted = rows.sortedWith(compareBy({ it.timeMillis }, { it.line }))
        val keys = withOccurrence(sorted.map { it.key })
        val out = skipped.toMutableList()
        val drafts = sorted.mapIndexed { i, r -> r.copy(key = keys[i]) }.filter { r ->
            val consumes = feeConsumesAmount(r)
            if (consumes) out += CsvImportSkip(r.line, CsvImportSkipReason.FEE_EXCEEDS_AMOUNT)
            !consumes
        }
        out.sortBy { it.line }
        return CsvImportResult(format, drafts, out, zone, localTimes = localTimes && drafts.isNotEmpty())
    }

    /** Kalendertag (Tage seit 1970-01-01) des Zeitpunkts in [zone] — für die Tageskurse. */
    fun epochDay(timeMillis: Long, zone: String = CsvImportParser.DEFAULT_ZONE): Long {
        val offset = ZoneId.of(zone).rules.getOffset(Instant.ofEpochMilli(timeMillis)).totalSeconds
        return Math.floorDiv(Math.floorDiv(timeMillis, 1000L) + offset, 86_400L)
    }

    /**
     * USDT/USD-Tagesschluss am Tag [day] aus [usdtUsd] (Tag → Schluss), nur plausibel ([PortfolioStables.usdtUsd]);
     * null = keiner (kein Schluss eines anderen Tags) — wie `TaxExport.usdtUsdOn`.
     */
    fun usdtUsdOn(usdtUsd: Map<Long, BigDecimal>?, day: Long): BigDecimal? =
        usdtUsd?.get(day)?.takeIf { it.signum() > 0 && !PortfolioStables.usdtUsd(it.toDouble()).approximate }

    /**
     * Preise in USDT umrechnen.
     * @param fx (Währung, Tag) → Einheiten dieser Währung je 1 USD (wie `PortfolioHistoryFx`); null = kein Kurs.
     *   USD/USDT brauchen keinen Kurs. Die Integration reicht hier die Tageskurse samt Rückgriff auf den
     *   letzten Geschäftstag davor (`PortfolioHistoryFx.rateOn`) durch.
     * @param usdtUsd USDT/USD-Tagesschlüsse (Tag → Schluss; wie der Steuer-Export
     *   `TaxExport.rateOn`): USD bzw. Fiat → USDT = Betrag ÷ (Devisenkurs × USDT/USD DES Tags). So ergibt ein Kauf über
     *   10 000 USD im Export wieder 10 000 USD Einstand (Export: USDT × Devisenkurs × USDT/USD desselben Tags). Fehlt
     *   der Schluss des Tags: × 1, Zeile als Näherung markiert ([CsvImportPriced.usdtApproximate]). null = USD gilt
     *   1:1 als USDT (ohne Markierung).
     */
    fun priceInUsdt(
        rows: List<CsvImportRow>,
        zone: String = CsvImportParser.DEFAULT_ZONE,
        usdtUsd: Map<Long, BigDecimal>? = null,
        fx: (String, Long) -> BigDecimal?,
    ): List<CsvImportPriced> = rows.map { row ->
        val day = epochDay(row.timeMillis, zone)
        val close = usdtUsdOn(usdtUsd, day)
        var usdtApproximate = false
        // Einheiten der Währung je 1 USDT; USDT selbst 1
        fun rate(currency: String): BigDecimal? {
            if (currency == PortfolioStables.VALUATION) return BigDecimal.ONE
            val fxRate = if (currency in USD_LIKE) BigDecimal.ONE else fx(currency, day)?.takeIf { it.signum() > 0 } ?: return null
            if (usdtUsd == null) return fxRate
            if (close == null) { usdtApproximate = true; return fxRate }
            return fxRate.multiply(close, PortfolioDecimal.MC)
        }
        var needsFx = false
        val priceUsdt = row.price?.let { p ->
            val r = row.quote?.let { rate(it) }
            if (r == null) { needsFx = true; null } else toUsdt(p, r)
        }
        // Tausch: Wert aus der Datei (Fiat) zum Tageskurs
        val swapValueUsdt = row.swapValue?.let { v ->
            val r = row.quote?.let { rate(it) }
            if (r == null) { needsFx = true; null } else toUsdt(v, r)
        }
        // Gebühr in der Preiswährung, in USD/USDT oder in einer anderen Fiat-Währung (Kauf in USD, Gebühr in EUR; Tausch
        // ohne Fiat-Wert mit EUR-Gebühr): zum Tageskurs ihrer Währung — ohne Kurs nie still weggelassen, sondern needsFx
        // wie Preis und Wert (die Vorschau nennt die Zeile unter «ohne Tageskurs»)
        val feeUnit = row.feeUnit
        val feeUsdt = if (row.fee != null && feeUnit != null && (feeUnit == row.quote || feeUnit in USD_LIKE || isFiat(feeUnit))) {
            val r = rate(feeUnit)
            if (r == null) { needsFx = true; null } else toUsdt(row.fee, r)
        } else null
        CsvImportPriced(
            row = row,
            priceUsdt = priceUsdt,
            feeUsdt = feeUsdt,
            needsFx = needsFx,
            missingPrice = row.price == null && (row.kind == CsvImportKind.BUY || row.kind == CsvImportKind.SELL),
            swapValueUsdt = swapValueUsdt,
            usdtApproximate = usdtApproximate,
        )
    }

    private fun toUsdt(value: BigDecimal, rate: BigDecimal): BigDecimal =
        if (rate.compareTo(BigDecimal.ONE) == 0) value else derive(value, rate)
}
