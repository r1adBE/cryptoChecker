package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.LegacyStorageMigration
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

/**
 * Neutrale Art einer importierten Zeile — vor der Zuordnung zu den Buchungsarten des Portfolios
 * (Zuordnung erst bei der Integration: BUY/SELL → Kauf/Verkauf, WITHDRAWAL → Auszahlung/Transfer,
 * DEPOSIT → Einzahlung, REWARD → Belohnung, FEE → Gebühr, SWAP → Tausch, TRANSFER → Umbuchung: eigene Mittel zurück
 * aus Staking/Earn, «Unstaking», «Redeem» — kein Gewinn, keine Belohnung). Gespiegelt in `CsvImportRow.swift`.
 */
enum class CsvImportKind { BUY, SELL, WITHDRAWAL, DEPOSIT, REWARD, FEE, SWAP, TRANSFER }

/**
 * Reihenfolge von Tag und Monat in Daten wie «03/04/2024» (nur Trenner «/» und «-»; «03.04.2024» ist immer Tag zuerst).
 * Erkannt je Datei ([CsvImportParser.detectDateOrder]); nur mehrdeutige Daten → Vorschau fragt.
 */
enum class CsvDateOrder { DAY_FIRST, MONTH_FIRST }

/**
 * Erkanntes Dateiformat; UNKNOWN = keine bekannte Kopfzeile gefunden. Der Name steht in Schlüsseln und Importläufen
 * (`BINANCE|…`) — neue Mitglieder nur anhängen bzw. vor GENERIC einfügen, nie umbenennen.
 * [exchangeName]: Name der Börse bzw. des Anbieters (Marke, nicht übersetzt) — Vorschau, Liste «Importe» und Herkunft
 * einer Umbuchung ([CsvTransferMatch]); null = Herkunft unbekannt (eigene CSV).
 */
enum class CsvImportFormat(val exchangeName: String?) {
    RELAI("Relai"),
    SWISSQUOTE("Swissquote"),
    KRAKEN("Kraken"),
    BINANCE("Binance"),
    COINBASE("Coinbase"),
    BITPANDA("Bitpanda"),
    GENERIC(null),
    UNKNOWN(null),
}

/**
 * Warum eine Zeile nicht übernommen wurde (für die Vorschau). [FEE_EXCEEDS_AMOUNT]: Gebühr im Coin ≥ erhaltene Menge;
 * [TWO_FEES]: Tausch mit Gebühr auf beiden Seiten in verschiedenen Einheiten (nicht still eine verwerfen)
 * ([CsvImport.feeConsumesAmount]) — wie das Erfassen-Blatt nicht speicherbar. [INTERNAL_MOVE]: Verschiebung zwischen
 * Konten derselben Börse (Spot ↔ Earn/Funding, Coinbase ↔ Coinbase Pro) — der Bestand bleibt gleich, nichts zu buchen.
 * [POSSIBLE_DUPLICATE]: fast gleich wie ein schon importiertes Geschäft (anderer Binance-Export,
 * [CsvImportBinance.possibleDuplicates]) — lieber prüfen lassen als doppelt zählen. [AMBIGUOUS_GROUP]: mehrere Geschäfte
 * in derselben Sekunde, die sich nicht eindeutig trennen lassen (Binance: mehrere Coins je Seite, z. B. Kleinbeträge in
 * BNB) — von Hand erfassen. Nur anhängen (Name steht in Fixtures).
 */
enum class CsvImportSkipReason {
    UNSUPPORTED_KIND, NOT_CRYPTO, BAD_NUMBER, BAD_DATE, MISSING_FIELD, FEE_EXCEEDS_AMOUNT, TWO_FEES, INTERNAL_MOVE, POSSIBLE_DUPLICATE,
    AMBIGUOUS_GROUP,
}

/** Übersprungene Zeile; [line] = Zeilennummer in der Datei (1 = erste Zeile). */
data class CsvImportSkip(val line: Int, val reason: CsvImportSkipReason)

/**
 * Eine importierte Buchung.
 * @param line Zeilennummer in der Datei (1-basiert, Kopfzeile mitgezählt)
 * @param rawKind Art wie in der Datei («Buy», «Savings plan», «Kauf»)
 * @param coin Kürzel gross («BTC»)
 * @param amount Menge in Coin-Einheiten, exakt, immer > 0 (Sats bereits in BTC umgerechnet)
 * @param price Preis je Coin in [quote]; null = unbekannt (z. B. Auszahlung)
 * @param quote Währung des Preises («CHF», «EUR», «USD»)
 * @param fee Gebühr > 0 oder null; [feeUnit] = Währung oder Coin der Gebühr
 * @param timeMillis UTC; Ortszeit der Datei (ohne Versatz) in [CsvImportResult.zone]
 * @param externalId Kennung aus der Datei (Relai «Operation ID», Swissquote «Order #»)
 * @param key Schlüssel gegen doppelten Import (siehe [CsvImport.key])
 * @param toCoin Tausch ([CsvImportKind.SWAP]): erhaltener Coin (gross); [coin]/[amount] = abgegeben
 * @param toAmount Tausch: erhaltene Menge (> 0)
 * @param swapValue Tausch: Wert in [quote] (Fiat/USD aus der Datei); null = unbekannt
 * @param localTime Kalenderzeit der Datei (als wäre sie UTC), wenn sie ohne Versatz steht — Schlüssel unabhängig
 *   von der gewählten Zeitzone ([CsvImport.key]); null = Zeit mit Versatz/«Z»
 * @param legacyKeys frühere Schlüssel derselben Zeile (Zeit je Zeitzone in UTC, bis Build 45; Kauf/Verkauf gegen
 *   Stablecoin bis Build 46) — erkennt ältere Importe
 * @param stableSwap Kauf/Verkauf gegen einen Stablecoin, als Tausch gebucht ([CsvImport.STABLES]) — Hinweis in der Vorschau
 */
data class CsvImportRow(
    val line: Int,
    val kind: CsvImportKind,
    val rawKind: String,
    val coin: String,
    val amount: BigDecimal,
    val price: BigDecimal?,
    val quote: String?,
    val fee: BigDecimal?,
    val feeUnit: String?,
    val timeMillis: Long,
    val externalId: String?,
    val key: String,
    val toCoin: String? = null,
    val toAmount: BigDecimal? = null,
    val swapValue: BigDecimal? = null,
    val localTime: Long? = null,
    val legacyKeys: List<String> = emptyList(),
    val stableSwap: Boolean = false,
)

data class CsvImportResult(
    val format: CsvImportFormat,
    val rows: List<CsvImportRow>,
    val skipped: List<CsvImportSkip>,
    /** Zeitzone der Zeiten ohne Versatz/«Z» ([CsvImportParser.zoneFor]). */
    val zone: String = CsvImportParser.DEFAULT_ZONE,
    /** Mindestens eine gelesene Zeit ohne Versatz — nur dann zählt [zone] (Vorschau zeigt sie mit Auswahl). */
    val localTimes: Boolean = false,
    /** Gelesene Reihenfolge Tag/Monat ([CsvDateOrder]). */
    val dateOrder: CsvDateOrder = CsvDateOrder.DAY_FIRST,
    /** Nur mehrdeutige Daten («03/04/2024»): Vorschau bietet «Tag/Monat» / «Monat/Tag» an ([dateOrder] = Auswahl). */
    val dateOrderAmbiguous: Boolean = false,
    /**
     * Datei verrät kein Dezimalzeichen, enthält aber eine mehrdeutige Zahl («42,000»): diese (wie in der Datei); die
     * Vorschau fragt «42000 oder 42?» ([decimalMark] = gelesenes Dezimalzeichen). null = eindeutig.
     */
    val decimalSample: String? = null,
    /** Dezimalzeichen bei [decimalSample] ('.' oder ','); sonst null. */
    val decimalMark: Char? = null,
    /** Erkannte Spalten der Kopfzeile (Name wie in der Datei) — Vorschau. */
    val columns: List<String> = emptyList(),
    /**
     * Zeitzone der Tageskurse (Devisen) = Zeitzone des Geräts, wie der Steuer-Export (`TaxExport.localDate`) — sonst
     * fiele ein Geschäft nahe Mitternacht beim Import auf einen anderen Kurstag als im Export.
     */
    val fxZone: String = CsvImportParser.DEFAULT_ZONE,
) {
    /** Vorschau fragt nach dem Dezimalzeichen ([decimalSample]). */
    val decimalAmbiguous: Boolean get() = decimalSample != null
}

/** Aufteilung für die Vorschau: neu vs. bereits importiert. */
data class CsvImportSplit(val fresh: List<CsvImportRow>, val duplicates: List<CsvImportRow>)

/**
 * Zeile mit Preis in USDT (Einheit des Portfolios).
 * @param priceUsdt Preis je Coin in USDT; null ohne Preis oder ohne Tageskurs
 * @param feeUsdt Gebühr in USDT, wenn sie in der Preiswährung oder USD/USDT lautet; sonst null
 *   (Gebühr in Coin bleibt in [CsvImportRow.fee]/[CsvImportRow.feeUnit])
 * @param needsFx Tageskurs für [CsvImportRow.quote] (oder Gebührenwährung) fehlt
 * @param missingPrice Kauf/Verkauf ohne Preis in der Datei
 * @param swapValueUsdt Tausch: Wert in USDT; null = unbekannt (kein Wert in der Datei oder kein Tageskurs)
 */
data class CsvImportPriced(
    val row: CsvImportRow,
    val priceUsdt: BigDecimal?,
    val feeUsdt: BigDecimal?,
    val needsFx: Boolean,
    val missingPrice: Boolean,
    val swapValueUsdt: BigDecimal? = null,
)

/** Schlüssel, Duplikate und Umrechnung in USDT (reines Kotlin; iOS `CsvImport` in `CsvImportRow.swift`). */
object CsvImport {

    /** Nachkommastellen für abgeleitete Preise (Teilungen), halbe auf gerade. */
    const val DERIVED_SCALE = 12

    /** Währungen, die 1:1 als USDT gelten. */
    private val USD_LIKE = setOf("USD", "USDT")

    /** Fiat (mit USD): nur Geschäfte gegen diese Währungen sind Kauf/Verkauf; Ein-/Auszahlungen davon sind kein Krypto. */
    internal val FIAT = setOf("CHF", "EUR", "USD", "GBP", "JPY", "CAD", "AUD", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF")

    /**
     * Wertwährungen eines Tauschs: Fiat und USDT (Bewertungswährung, gilt 1:1). Nur ein Wert in diesen Währungen
     * gilt als Wert eines Tauschs; sonst unbekannt (Tagesschluss beim Rechnen).
     */
    internal val MONEY = FIAT + "USDT"

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
    internal val FX_FIAT = FIAT + setOf(
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
     * andere Zeitzone beim zweiten Import den Schlüssel nicht (frühere Schlüssel: [CsvImportRow.legacyKeys]).
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
     * Zeilen, deren Schlüssel (oder ein früherer, [CsvImportRow.legacyKeys]) schon gespeichert ist, als Duplikate abtrennen.
     * [existingKeys] = alle gespeicherten Herkunftsschlüssel samt `linkedImportKey` verknüpfter Umbuchungen: jede ihrer
     * Seiten gilt als importiert. Vor Build 58 verknüpfte Umbuchungen tragen lange Teile gekürzt
     * ([LegacyStorageMigration.shortKey]) — auch darüber erkannt.
     */
    fun splitDuplicates(rows: List<CsvImportRow>, existingKeys: Set<String>): CsvImportSplit {
        fun isKnown(key: String) = key in existingKeys || LegacyStorageMigration.shortKey(key) in existingKeys
        val (dup, fresh) = rows.partition { r -> isKnown(r.key) || r.legacyKeys.any { isKnown(it) } }
        return CsvImportSplit(fresh = fresh, duplicates = dup)
    }

    /**
     * Gemeinsamer Abschluss der Börsen-Leser (Kraken, Binance, Coinbase, Bitpanda): Zeilen nach Zeit (dann Zeile) — die
     * Leser bilden Geschäfte aus Gruppen und geben sie nicht in Dateireihenfolge aus —, gleiche Schlüssel «#2» …
     * ([withOccurrence]), Gebühr ≥ erhaltene Menge übersprungen ([feeConsumesAmount]), Übersprungene nach Zeile.
     * [localTimes]: die Datei schreibt Zeiten ohne Versatz (dann zählt die Zeitzone, sofern Zeilen übrig bleiben).
     */
    internal fun finish(
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
     * Preise in USDT umrechnen.
     * @param fx (Währung, Tag) → Einheiten dieser Währung je 1 USD (wie `PortfolioHistoryFx`); null = kein Kurs.
     *   USD/USDT brauchen keinen Kurs. Die Integration reicht hier die Tageskurse samt Rückgriff auf den
     *   letzten Geschäftstag davor (`PortfolioHistoryFx.rateOn`) durch.
     */
    fun priceInUsdt(
        rows: List<CsvImportRow>,
        zone: String = CsvImportParser.DEFAULT_ZONE,
        fx: (String, Long) -> BigDecimal?,
    ): List<CsvImportPriced> = rows.map { row ->
        val day = epochDay(row.timeMillis, zone)
        fun rate(currency: String): BigDecimal? =
            if (currency in USD_LIKE) BigDecimal.ONE else fx(currency, day)?.takeIf { it.signum() > 0 }
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
        val feeUnit = row.feeUnit
        val feeUsdt = if (row.fee != null && feeUnit != null && (feeUnit == row.quote || feeUnit in USD_LIKE)) {
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
        )
    }

    private fun toUsdt(value: BigDecimal, rate: BigDecimal): BigDecimal =
        if (rate.compareTo(BigDecimal.ONE) == 0) value
        else value.divide(rate, DERIVED_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros()
}
