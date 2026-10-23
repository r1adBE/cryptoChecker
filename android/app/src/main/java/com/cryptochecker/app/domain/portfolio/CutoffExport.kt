package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stichtag-Export (z. B. Wertschriftenverzeichnis per 31.12.): Bestand je Coin am
 * Ende eines Tags, bewertet zum Tagesschlusskurs in USDT und umgerechnet in die
 * Zielwährung. Reines Kotlin ohne Android und ohne org.json — testbar.
 *
 * CSV: UTF-8 mit BOM (für Excel), Zeilenende CRLF; Trenn- und Dezimalzeichen je Region
 * ([CutoffCsvFormat], Standard «;» + «.»).
 */
object CutoffExport {

    /** Stablecoins: die eine Liste aus [PortfolioStables] (Tagesschluss, sonst kein Wert; USDT immer 1). */
    val STABLES: Set<String> get() = PortfolioStables.COINS

    const val SEPARATOR = ";"
    private const val BOM = "\uFEFF"
    private const val EOL = "\r\n"

    fun isStable(coin: String): Boolean = PortfolioStables.isStable(coin)

    /** Letzte Millisekunde des Tags [date] in der Zeitzone [zone]. */
    fun endOfDayMillis(date: LocalDate, zone: ZoneId): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    /** Standardstichtag: 31. Dezember des Vorjahrs. */
    fun defaultDate(today: LocalDate): LocalDate = LocalDate.of(today.year - 1, 12, 31)

    fun fileName(date: LocalDate): String = "cryptochecker-stichtag-$date.csv"

    /**
     * Eigener Bestand je Coin aus allen Transaktionen mit Zeit ≤ [cutoffMillis] nach der Einstandsmethode [method]
     * ([PortfolioCalculator.holdingsAt] — dieselben Mengen wie Karte und Wertverlauf, auch FIFO je Wallet). Coins ohne
     * Bestand fallen weg. Alphabetisch sortiert. Adress-Guthaben: [addressRows]; mit Eröffnungsposten wie die Karte:
     * [AddressCutoff.holdingsAt].
     */
    fun holdingsAt(
        trades: List<PortfolioTrade>,
        cutoffMillis: Long,
        method: CostMethod = CostMethod.DEFAULT,
    ): List<CutoffHolding> =
        PortfolioCalculator.holdingsAt(trades, cutoffMillis, method)
            .map { (coin, amount) -> CutoffHolding(coin, amount) }
            .sortedBy { it.coin }

    /**
     * Bezugskurse der Identitätsprüfung aus den aktuellen Portfolio-Kursen [current]: nur gültige,
     * ohne ungeprüfte Kerzenkurse ([uncheckedCurrent], kein unabhängiger Bezug) und ohne Binance-Kurse mit
     * unbestätigtem Token ([unverifiedCurrent], «Kurs ungeprüft» — sonst bestätigte ein womöglich
     * fremder Token seine eigenen Tagesschlüsse). Fehlt ein Coin, wird sein Tagesschluss nicht übernommen («ungeprüft»).
     */
    fun referencePrices(
        current: Map<String, Double>,
        uncheckedCurrent: Set<String>,
        unverifiedCurrent: Set<String> = emptySet(),
    ): Map<String, Double> =
        current.filter { (coin, price) ->
            coin !in uncheckedCurrent && coin !in unverifiedCurrent && price > 0.0 && price.isFinite()
        }

    /** Ein Stand aktueller Kurse für [referenceOf]: Kurse samt ungeprüften und «Kurs ungeprüft» ([referencePrices]). */
    data class ReferenceLayer(
        val prices: Map<String, Double>,
        val unchecked: Set<String> = emptySet(),
        val unverified: Set<String> = emptySet(),
    )

    /**
     * Bezugskurs eines Coins aus mehreren Ständen der Reihe nach (Wertverlauf: Speicher, dann gemerkter Stand): der
     * erste geprüfte ([referencePrices]); null = keiner. Ein ungeprüfter Kurs bestätigt also auch dort keine Tagesschlüsse.
     */
    fun referenceOf(coin: String, layers: List<ReferenceLayer>): Double? =
        layers.firstNotNullOfOrNull { referencePrices(it.prices, it.unchecked, it.unverified)[coin] }

    /**
     * Zeilen aus Bestand, Kursen ([prices]: Coin → Tagesschluss in USDT) und dem Devisenkurs
     * [fxRate] (null = unbekannt), alles exakt: Wert = Menge × Kurs, Zielwert = Wert × Devisenkurs
     * (je 34 gültige Stellen, wie iOS). Stablecoins nach [PortfolioStables.price]: USDT = 1, die
     * übrigen ihr Tagesschluss, fehlt er, «ohne Kurs» wie jeder Coin. Coins in [unchecked]
     * (ausser USDT): kein Kurs, Hinweis «ungeprüft» statt «ohne Kurs».
     */
    fun rows(
        holdings: List<CutoffHolding>,
        prices: Map<String, BigDecimal>,
        fxRate: BigDecimal?,
        unchecked: Set<String> = emptySet(),
    ): List<CutoffRow> {
        val rate = fxRate?.takeIf { it.signum() > 0 }
        return holdings.map { h ->
            val notChecked = h.coin in unchecked && PortfolioStables.needsQuote(h.coin)
            val price = if (notChecked) null else stablePrice(h.coin, prices[h.coin])
            val value = price?.let { h.amount.multiply(it, PortfolioDecimal.MC) }
            CutoffRow(
                coin = h.coin,
                amount = h.amount,
                priceUsdt = price,
                valueUsdt = value,
                rate = rate,
                valueTarget = if (value != null && rate != null) value.multiply(rate, PortfolioDecimal.MC) else null,
                noPrice = price == null && !notChecked,
                noFx = rate == null,
                unchecked = notChecked,
            )
        }
    }

    /**
     * Adress-Zeilen am Stichtag aus [AddressBalanceHistory.balancesAt] (nur Adressen, die im Portfolio zählen — nicht
     * «Nur anzeigen»): je Coin eine eigene Zeile «aus Adresse – ohne Kaufpreis» ([CutoffRow.fromAddress]), bewertet
     * wie die übrigen ([rows]). Stichtag heute: das zuletzt beobachtete (aktuelle) Guthaben; früher: das an dem Tag
     * beobachtete. Coins, deren Adresse erst später beobachtet wurde ([AddressBalancesAt.unobserved]), stehen mit
     * Hinweis und ohne fehlenden Teil da ([CutoffRow.addressUnobserved]) — nie rückwirkend geschätzt.
     */
    fun addressRows(
        at: AddressBalancesAt,
        prices: Map<String, BigDecimal>,
        fxRate: BigDecimal?,
        unchecked: Set<String> = emptySet(),
    ): List<CutoffRow> {
        val holdings = at.balances.map { (coin, amount) -> CutoffHolding(coin, amount) }.sortedBy { it.coin }
        val priced = rows(holdings, prices, fxRate, unchecked).map { r ->
            r.copy(fromAddress = true, addressUnobserved = r.coin in at.unobserved)
        }
        val rate = fxRate?.takeIf { it.signum() > 0 }
        val missing = at.unobserved.filter { it !in at.balances }.sorted().map { coin ->
            CutoffRow(
                coin = coin, amount = BigDecimal.ZERO, priceUsdt = null, valueUsdt = null, rate = rate, valueTarget = null,
                noPrice = false, noFx = rate == null, fromAddress = true, addressUnobserved = true,
            )
        }
        return priced + missing
    }

    /** Wie [PortfolioStables.price], exakt: USDT = 1, sonst nur ein positiver Kurs. */
    private fun stablePrice(coin: String, market: BigDecimal?): BigDecimal? =
        if (!PortfolioStables.needsQuote(coin)) BigDecimal.ONE else market?.takeIf { it.signum() > 0 }

    /**
     * Rechnet der Export in [currency] USDT in Fiat um (USDT zum Marktkurs: Devisenkurs × USDT/USD)? Für jede Währung
     * ausser USDT selbst — auch USD.
     */
    fun convertsUsdt(currency: String): Boolean = currency.trim().uppercase() != PortfolioStables.VALUATION

    /**
     * Ganze CSV-Datei (mit BOM). [fxDate] = tatsächliches Datum des Devisenkurses
     * (null, z. B. bei USD oder ohne Kurs → «—»).
     */
    fun csv(
        date: LocalDate,
        currency: String,
        fxDate: String?,
        rows: List<CutoffRow>,
        texts: CutoffCsvTexts,
        format: CutoffCsvFormat = CutoffCsvFormat.SEMICOLON_POINT,
        /** Kopfzeilen davor (Arbeitsunterlage: Vorlage, Methode, Steuerjahr, Hinweis), je ein Feld; leer = keine. */
        header: List<String> = emptyList(),
        /** Kurs ohne USDT/USD am Stichtag (USDT = 1 als Näherung): Zeile [CutoffCsvTexts.usdtAssumed] nach dem Kommentar. */
        usdtApproximate: Boolean = false,
        /**
         * Beobachtete Adressen nicht lesbar: ihre Guthaben fehlen, ohne dass bekannt ist welche Coins — Zeile
         * [CutoffCsvTexts.addressesUnreadable] nach dem Kommentar und Total «unvollständig» (wie bei nicht beobachteten
         * Adressen), statt still eine zu tiefe Summe.
         */
        addressesUnreadable: Boolean = false,
        /**
         * Ein Eröffnungsposten liegt zwischen der benutzten Beobachtung einer Adresse und dem Stichtag
         * ([AddressCutoffView.lotsAfterObservation]): Zeile [CutoffCsvTexts.addressLots]. Sonst rechnet die Datei wie die
         * Karte ([AddressCutoff]) — dort ist kein Hinweis nötig. Keine Rechenänderung.
         */
        addressLots: Boolean = false,
    ): String {
        // Zahlen ohne Tausendertrennung: Dezimalpunkt ggf. durch Komma ersetzen
        fun n(text: String): CsvCell = CsvCell(if (format.decimal == ',') text.replace('.', ',') else text, numeric = true)
        fun t(text: String): CsvCell = CsvCell(text)
        fun line(fields: List<String>): String = joinCells(fields.map { t(it) }, format.separator) + EOL
        fun cells(fields: List<CsvCell>): String = joinCells(fields, format.separator) + EOL
        val sb = StringBuilder(BOM)
        header.forEach { sb.append(line(listOf(it))) }
        sb.append(line(listOf(texts.commentFormat.format(date.toString(), currency, fxDate ?: "—"))))
        // USDT zum Marktkurs, aber am Stichtag ohne USDT/USD-Kurs: Näherung 1 nie still
        if (usdtApproximate && texts.usdtAssumed.isNotEmpty()) sb.append(line(listOf(texts.usdtAssumed)))
        // Adressen nicht lesbar: fehlende Guthaben nie still
        if (addressesUnreadable && texts.addressesUnreadable.isNotEmpty()) sb.append(line(listOf(texts.addressesUnreadable)))
        // Posten nach der benutzten Beobachtung einer Adresse: können fehlen — nur bei Bedarf
        if (addressLots && texts.addressLots.isNotEmpty()) sb.append(line(listOf(texts.addressLots)))
        // In CHF per 31.12.: Hinweis auf die massgebende ESTV-Kursliste des Jahrs
        EstvKursliste.noteLine(texts.estvNoteFormat, date, currency, texts.estvLanguage)?.let { sb.append(line(listOf(it))) }
        sb.append(
            line(
                listOf(
                    texts.coin, texts.amount, texts.priceUsdt, texts.valueUsdt,
                    texts.rate, texts.valueTarget, texts.note,
                )
            )
        )
        for (r in rows) {
            val notes = listOfNotNull(
                texts.fromAddress.takeIf { r.fromAddress && it.isNotEmpty() },
                texts.addressUnobserved.takeIf { r.addressUnobserved && it.isNotEmpty() },
                texts.noPrice.takeIf { r.noPrice },
                texts.unchecked.takeIf { r.unchecked },
                texts.noFx.takeIf { r.noFx },
            ).joinToString(", ")
            sb.append(
                cells(
                    listOf(
                        t(r.coin),
                        // Adresse nicht beobachtet und kein beobachteter Teil: keine Menge
                        n(if (r.addressUnobserved && r.amount.signum() == 0) "" else amount(r.amount)),
                        n(r.priceUsdt?.let { price(it) }.orEmpty()),
                        n(r.valueUsdt?.let { money(it) }.orEmpty()),
                        n(r.rate?.let { rate(it) }.orEmpty()),
                        n(r.valueTarget?.let { money(it) }.orEmpty()),
                        t(notes),
                    )
                )
            )
        }

        // Total: Summe der bekannten Werte; fehlt irgendwo ein Kurs, steht «unvollständig» dabei. Summiert werden die
        // GERUNDETEN Zeilenwerte ([roundedMoney]), wie sie in der Datei stehen — wer die Spalte nachrechnet, kommt auf
        // dasselbe Total (exakt summiert gäbe 0.34 + 0.34 + 0.34 sonst 1.01 statt 1.02)
        val totalUsdt = rows.fold(BigDecimal.ZERO) { sum, r -> r.valueUsdt?.let { sum + roundedMoney(it) } ?: sum }
        val rate = rows.firstOrNull()?.rate
        val anyValue = rows.any { it.valueUsdt != null }
        val totalTarget = if (rows.any { it.valueTarget != null }) {
            rows.fold(BigDecimal.ZERO) { sum, r -> r.valueTarget?.let { sum + roundedMoney(it) } ?: sum }
        } else null
        val totalNotes = listOfNotNull(
            texts.incomplete.takeIf { addressesUnreadable || rows.any { it.noPrice || it.unchecked || it.addressUnobserved } },
            texts.noFx.takeIf { rows.isNotEmpty() && rows.all { it.noFx } },
        ).joinToString(", ")
        sb.append(
            cells(
                listOf(
                    t(texts.total),
                    n(""),
                    n(""),
                    n(if (anyValue || rows.isEmpty()) money(totalUsdt) else ""),
                    n(rate?.let { rate(it) }.orEmpty()),
                    n(totalTarget?.let { money(it) }.orEmpty()),
                    t(totalNotes),
                )
            )
        )
        return sb.toString()
    }

    /** Zeile aus Zellen ohne Zeilenende: Text über [escape], Zahlen nur über [escapeNumber]. */
    internal fun joinCells(cells: List<CsvCell>, separator: Char): String =
        cells.joinToString(separator.toString()) { if (it.numeric) escapeNumber(it.text, separator) else escape(it.text, separator) }

    /** Erste Zeichen, mit denen Excel/LibreOffice/Sheets eine Zelle als Formel lesen (OWASP «CSV Injection»). */
    private val FORMULA_START = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * TEXT-Feld: beginnt es mit = + - @ (oder Tab/CR), auch nach führenden Leerzeichen (Tabellen übergehen sie und lesen
     * « =1+1» trotzdem als Formel), wird ein Apostroph vorangestellt (keine Formel beim Öffnen); dann in Anführungszeichen,
     * wenn es das Trennzeichen, «"» oder einen Zeilenumbruch enthält. Zahlen gehen über [escapeNumber] (eine negative Zahl
     * «-12.50» bleibt eine Zahl).
     */
    internal fun escape(field: String, separator: Char = ';'): String {
        val first = field.firstOrNull { it != ' ' && it != '\u00A0' }
        val safe = if (first != null && first in FORMULA_START) "'$field" else field
        return quote(safe, separator)
    }

    /** ZAHL-Feld (vom Zahlenformat): nur in Anführungszeichen, wenn nötig — nie mit Apostroph. */
    internal fun escapeNumber(field: String, separator: Char = ';'): String = quote(field, separator)

    private fun quote(field: String, separator: Char): String =
        if (field.any { it == separator || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else field

    // ---------------- Zahlen (Dezimalpunkt, ohne Tausendertrennung; Komma erst in [csv]) ----------------
    // Gerundet wird nur hier, kaufmännisch (HALF_UP) ab dem exakten Wert, gleich wie iOS
    // (`PortfolioCutoffExport`): 1.005 → «1.01». Double-Fassungen rechnen mit der kürzesten
    // Dezimaldarstellung ([PortfolioDecimal.of]); nicht endlich → leeres Feld.

    /** Menge (Double): bis 10 Nachkommastellen, unter 1 bis 10 gültige Stellen (3e-11 → «0.00000000003»), ohne Nullen am Ende. */
    internal fun amount(v: Double): String = PortfolioDecimal.of(v)?.let { price(it) }.orEmpty()

    /** Exakte Menge: alle Nachkommastellen (höchstens 18), ohne Exponent und Nullen am Ende («0.00000001»). */
    internal fun amount(v: BigDecimal): String = PortfolioDecimal.text(v)

    /** Kurs: bis 10 Nachkommastellen, unter 1 bis 10 gültige Stellen — auch Kleinstkurse (3e-11) bleiben lesbar statt «0». */
    internal fun price(v: Double): String = PortfolioDecimal.of(v)?.let { price(it) }.orEmpty()

    internal fun price(v: BigDecimal): String {
        val s = v.stripTrailingZeros()
        val integerDigits = if (s.signum() == 0) 0 else s.precision() - s.scale()
        return plain(v, maxOf(10, 10 - integerDigits))
    }

    /** Devisenkurs: bis 6 Nachkommastellen. */
    internal fun rate(v: Double): String = PortfolioDecimal.of(v)?.let { rate(it) }.orEmpty()

    internal fun rate(v: BigDecimal): String = plain(v, 6)

    /** Geldbetrag: genau 2 Nachkommastellen; «-0.00» → «0.00». */
    internal fun money(v: Double): String = PortfolioDecimal.of(v)?.let { money(it) }.orEmpty()

    internal fun money(v: BigDecimal): String {
        val d = roundedMoney(v)
        return (if (d.signum() == 0) d.abs() else d).toPlainString()
    }

    /** Geldbetrag so gerundet, wie [money] ihn schreibt (2 Stellen, HALF_UP) — für Summen über gerundete Zeilen. */
    internal fun roundedMoney(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

    /** Auf [scale] Nachkommastellen (HALF_UP), ohne Nullen am Ende; «-0» → «0». */
    private fun plain(v: BigDecimal, scale: Int): String {
        val d = v.setScale(scale, RoundingMode.HALF_UP)
        return if (d.signum() == 0) "0" else d.stripTrailingZeros().toPlainString()
    }
}
