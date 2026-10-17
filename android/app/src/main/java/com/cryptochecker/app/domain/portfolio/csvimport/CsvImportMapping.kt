package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxEngines
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import java.math.BigDecimal

/**
 * Importierte Zeile als Portfolio-Transaktion (vor dem Speichern). Preis und USDT-Gebühr schon umgerechnet.
 * [importKey] = [CsvImportRow.key] (erkennt die Zeile beim nächsten Import als doppelt).
 */
data class CsvImportTx(
    val line: Int,
    val coin: String,
    val type: PortfolioTxType,
    val amount: BigDecimal,
    val priceUsdt: BigDecimal?,
    val time: Long,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val note: String?,
    val importKey: String,
    /**
     * Umbuchung: wohin ([CsvImportMapping.applyWithdrawalChoices]: passende beobachtete Adresse; verknüpfte Umbuchung
     * [CsvTransferMatch]: Börse der Einzahlung); null = keine Angabe.
     */
    val transferTo: String? = null,
    /** Verknüpfte Umbuchung ([CsvTransferMatch]): Börse der Auszahlung; null = keine Angabe. */
    val transferFrom: String? = null,
    /** Tausch ([PortfolioTxType.SWAP]): erhaltener Coin und Menge, Wert in USDT (null = unbekannt). */
    val toCoin: String? = null,
    val toAmount: BigDecimal? = null,
    val swapValueUsdt: BigDecimal? = null,
    /** Verknüpfte Umbuchung zweier neuer Zeilen ([CsvTransferMatch.apply]): Herkunft der Einzahlung; sonst null. */
    val linkedImportKey: String? = null,
)

/**
 * Vorschau eines Imports: neue Transaktionen, Doppelte (schon importiert), übersprungene Zeilen mit Grund,
 * Zeilen ohne Tageskurs ([missingFx]) bzw. ohne Preis in der Datei ([missingPrice]) — beide werden trotzdem
 * übernommen (ohne Preis bzw. ohne Gebühr), aber genannt.
 */
data class CsvImportPreview(
    val format: CsvImportFormat,
    val transactions: List<CsvImportTx>,
    val duplicates: Int,
    val skipped: List<CsvImportSkip>,
    val missingFx: List<Int>,
    val missingPrice: List<Int>,
    /** Zeitzone der Zeiten ohne Versatz ([CsvImportResult.zone]) und ob es solche gibt (Auswahl in der Vorschau). */
    val zone: String = CsvImportParser.DEFAULT_ZONE,
    val localTimes: Boolean = false,
    /** Reihenfolge Tag/Monat ([CsvImportResult.dateOrder]) und ob die Datei sie offen lässt (Auswahl in der Vorschau). */
    val dateOrder: CsvDateOrder = CsvDateOrder.DAY_FIRST,
    val dateOrderAmbiguous: Boolean = false,
    /** Mindestens eine neue Zeile ist ein Kauf/Verkauf gegen Stablecoin, gebucht als Tausch ([CsvImportRow.stableSwap]). */
    val stableSwaps: Boolean = false,
    /** Mehrdeutige Zahl und gelesenes Dezimalzeichen ([CsvImportResult.decimalSample]); Auswahl in der Vorschau. */
    val decimalSample: String? = null,
    val decimalMark: Char? = null,
    /** Erkannte Spalten ([CsvImportResult.columns]). */
    val columns: List<String> = emptyList(),
    /**
     * Zeilen, deren USD-/Fiat-Betrag mit USDT = 1 USD umgerechnet ist (kein USDT/USD-Tagesschluss DES Tags,
     * [CsvImportPriced.usdtApproximate]) — die Vorschau nennt sie wie [missingFx].
     */
    val usdtApproximate: List<Int> = emptyList(),
)

/** Wahl je Auszahlung der Vorschau ([CsvImportMapping.proposeWithdrawals]). */
enum class CsvWithdrawalChoice {
    /** «Auf eigene Wallet (Umbuchung)»: bleibt im Portfolio. */
    TRANSFER,
    /** «Verlässt das Portfolio (Auszahlung)». */
    WITHDRAWAL,
}

/**
 * Vorschlag für eine Auszahlung ([CsvImportTx.importKey]): [choice] null = keine Vorauswahl (muss gewählt werden);
 * [transferTo] = Ziel der Umbuchung (Bezeichnung bzw. gekürzte Adresse der passenden beobachteten Adresse).
 */
data class CsvWithdrawalProposal(val importKey: String, val choice: CsvWithdrawalChoice?, val transferTo: String?)

/**
 * Zuordnung importierter Zeilen zu den Buchungsarten des Portfolios (reines Kotlin; iOS `CsvImportMapping.swift`):
 *  - Kauf → Kauf, Verkauf → Verkauf, Belohnung → Belohnung, Einzahlung → Einzahlung,
 *    Auszahlung → Auszahlung (auch an eine eigene Wallet: einfach und vorsichtig; wer die Coins weiter
 *    im Portfolio führen will, ändert sie in eine Umbuchung), Gebühr-Zeile → Veräusserung zum Tageskurs (gespeichert als
 *    Verkauf zum Preis 0 = Kennzeichen, [com.cryptochecker.app.domain.portfolio.PortfolioLedger.isFeeDisposal]: Erlös =
 *    Menge × Tagesschluss ihres Tags, ohne Kurs unbekannt — nie 0; im Steuer-Export «Gebühr») mit der Art als Notiz,
 *    Unstaking/Redeem ([CsvImportKind.TRANSFER]) → Umbuchung ohne Preis mit der Art als Notiz.
 *    Option `withdrawalsAsTransfer` (Schalter im Import): Auszahlungen werden Umbuchungen (die Coins bleiben im
 *    Portfolio, z. B. an die eigene Wallet); wohin = Ziel aus der Datei, die Formate liefern keins → null.
 *    Gebühr-Zeilen bleiben Veräusserungen zum Tageskurs.
 *  - Tausch («Tausch», «Swap», «Convert»; beide Seiten in einer Zeile oder in zwei aufeinanderfolgenden Zeilen,
 *    siehe [CsvImportParser]) → Tausch mit erhaltenem Coin/Menge; Wert aus dem Fiat-Betrag der Datei (zum
 *    Tageskurs in USDT), sonst unbekannt (das Portfolio nimmt dann den Marktkurs bzw. «ohne Kaufpreis»).
 *    Kauf/Verkauf gegen einen Stablecoin kommt schon als Tausch ([CsvImportRow.stableSwap]); die Vorschau nennt es.
 *  - Preis: umgerechnet in USDT ([CsvImport.priceInUsdt]); fehlt der Preis oder der Tageskurs, ohne Preis.
 *  - Gebühr im Coin der Zeile → Gebühr in Coin; Tausch mit Gebühr im erhaltenen Coin (auch USDT) → Gebühr im
 *    erhaltenen Coin (feeCoin = toCoin: weniger erhalten, Erlös = Wert des Tauschs); in Fiat/USD → USDT-Gebühr (umgerechnet); in einem anderen
 *    Coin → Gebühr im dritten Coin (ohne Preis = unvollständig); Fiat-Gebühr ohne Tageskurs fällt weg
 *    (die Zeile steht dann unter [CsvImportPreview.missingFx]).
 */
object CsvImportMapping {

    /** Währungen, die als Geld gelten (nie als «dritter Coin»). */
    private val FIAT: Set<String> get() = CsvImport.FIAT

    /** Höchstlänge der Notiz (wie [com.cryptochecker.app.data.local.model.NOTE_MAX]). */
    private const val NOTE_MAX = 120

    fun type(kind: CsvImportKind, withdrawalsAsTransfer: Boolean = false): PortfolioTxType = when (kind) {
        CsvImportKind.BUY -> PortfolioTxType.BUY
        CsvImportKind.SELL -> PortfolioTxType.SELL
        CsvImportKind.REWARD -> PortfolioTxType.REWARD
        CsvImportKind.DEPOSIT -> PortfolioTxType.DEPOSIT
        CsvImportKind.WITHDRAWAL -> if (withdrawalsAsTransfer) PortfolioTxType.TRANSFER else PortfolioTxType.WITHDRAWAL
        // Gebühr: Veräusserung zum Tageskurs (Kennzeichen Preis 0 in [toTx], bewertet beim Rechnen)
        CsvImportKind.FEE -> PortfolioTxType.SELL
        CsvImportKind.SWAP -> PortfolioTxType.SWAP
        // Eigene Mittel zurück aus Staking/Earn: bleibt im Portfolio, kein Gewinn
        CsvImportKind.TRANSFER -> PortfolioTxType.TRANSFER
    }

    /** Eine umgerechnete Zeile → Transaktion ([withdrawalsAsTransfer]: Auszahlung → Umbuchung). */
    fun toTx(p: CsvImportPriced, withdrawalsAsTransfer: Boolean = false): CsvImportTx {
        val row = p.row
        val coin = PortfolioCalculator.normalizeCoin(row.coin)
        val unit = row.feeUnit?.let { PortfolioCalculator.normalizeCoin(it) }
        var fee: BigDecimal? = null
        var feeUnit: PortfolioFeeUnit? = null
        var feeCoin: String? = null
        var feeCoinAmount: BigDecimal? = null
        val rowFee = row.fee?.takeIf { it.signum() > 0 }
        // Tausch: erhaltener Coin (auch USDT/Stablecoin) — Gebühr darin mindert die erhaltene Menge, nicht den Erlös
        val swapTo = row.toCoin?.let { PortfolioCalculator.normalizeCoin(it) }
            ?.takeIf { row.kind == CsvImportKind.SWAP && it.isNotEmpty() && it != coin }
        if (rowFee != null && unit != null) {
            when {
                unit == coin -> { fee = rowFee; feeUnit = PortfolioFeeUnit.COIN }
                unit == swapTo -> { feeCoin = unit; feeCoinAmount = rowFee }
                p.feeUsdt != null -> { fee = p.feeUsdt.takeIf { it.signum() > 0 }; feeUnit = fee?.let { PortfolioFeeUnit.QUOTE } }
                unit !in FIAT && unit != row.quote && unit != "USDT" -> { feeCoin = unit; feeCoinAmount = rowFee }
                else -> Unit // Fiat-Gebühr ohne Tageskurs: weggelassen (missingFx)
            }
        }
        return CsvImportTx(
            line = row.line,
            coin = coin,
            type = type(row.kind, withdrawalsAsTransfer),
            amount = row.amount,
            // Gebühr-Zeile: Kennzeichen Preis 0 ([com.cryptochecker.app.domain.portfolio.PortfolioLedger.valueFeeDisposals])
            priceUsdt = if (row.kind == CsvImportKind.FEE) BigDecimal.ZERO
            else p.priceUsdt?.takeIf { it.signum() >= 0 && row.kind != CsvImportKind.TRANSFER },
            time = row.timeMillis,
            fee = fee,
            feeUnit = feeUnit,
            feeCoin = feeCoin,
            feeCoinAmount = feeCoinAmount,
            // Gebühr/Umbuchung: Art als Notiz; Belohnung nur als Zins (AT: Anschaffungskosten = Wert, [TaxEngines.interestReward])
            note = if (row.kind == CsvImportKind.FEE || row.kind == CsvImportKind.TRANSFER ||
                (row.kind == CsvImportKind.REWARD && TaxEngines.interestReward(row.rawKind))
            ) row.rawKind.trim().take(NOTE_MAX).trim().ifEmpty { null } else null,
            importKey = row.key,
            toCoin = row.toCoin?.let { PortfolioCalculator.normalizeCoin(it) }.takeIf { row.kind == CsvImportKind.SWAP },
            toAmount = row.toAmount.takeIf { row.kind == CsvImportKind.SWAP },
            swapValueUsdt = p.swapValueUsdt?.takeIf { row.kind == CsvImportKind.SWAP && it.signum() >= 0 },
        )
    }

    /**
     * Vorschau: Doppelte gegen [existingKeys] abtrennen, die neuen umrechnen ([fx] und [usdtUsd] wie [CsvImport.priceInUsdt])
     * und zuordnen. Binance: fast gleiche Geschäfte wie schon importierte ([CsvImportBinance.possibleDuplicates]) kommen
     * in die Übersprungenen statt ein zweites Mal ins Portfolio.
     */
    fun preview(
        result: CsvImportResult,
        existingKeys: Set<String>,
        zone: String = result.fxZone,
        withdrawalsAsTransfer: Boolean = false,
        usdtUsd: Map<Long, BigDecimal>? = null,
        fx: (String, Long) -> BigDecimal?,
    ): CsvImportPreview {
        val split = CsvImport.splitDuplicates(result.rows, existingKeys)
        val held = if (result.format == CsvImportFormat.BINANCE) {
            CsvImportBinance.possibleDuplicates(split.fresh, existingKeys).mapTo(HashSet()) { it.key }
        } else emptySet()
        val fresh = split.fresh.filter { it.key !in held }
        val priced = CsvImport.priceInUsdt(fresh, zone, usdtUsd, fx)
        return CsvImportPreview(
            format = result.format,
            transactions = priced.map { toTx(it, withdrawalsAsTransfer) },
            duplicates = split.duplicates.size,
            skipped = (result.skipped + split.fresh.filter { it.key in held }.map { CsvImportSkip(it.line, CsvImportSkipReason.POSSIBLE_DUPLICATE) })
                .sortedBy { it.line },
            missingFx = priced.filter { it.needsFx }.map { it.row.line },
            missingPrice = priced.filter { it.missingPrice }.map { it.row.line },
            zone = result.zone,
            localTimes = result.localTimes,
            dateOrder = result.dateOrder,
            dateOrderAmbiguous = result.dateOrderAmbiguous,
            stableSwaps = fresh.any { it.stableSwap },
            decimalSample = result.decimalSample,
            decimalMark = result.decimalMark,
            columns = result.columns,
            usdtApproximate = priced.filter { it.usdtApproximate }.map { it.row.line },
        )
    }

    /**
     * Reihenfolge beim Speichern (die Id folgt ihr und entscheidet bei gleicher Zeit): Datei «neueste zuerst» (mehr
     * absteigende als aufsteigende Schritte der Zeit) → umgekehrt, also älteste zuerst; sonst wie in der Datei.
     */
    fun insertOrder(txs: List<CsvImportTx>): List<CsvImportTx> {
        var down = 0
        var up = 0
        for (i in 1 until txs.size) {
            val d = txs[i].time.compareTo(txs[i - 1].time)
            if (d < 0) down++ else if (d > 0) up++
        }
        return if (down > up) txs.asReversed().toList() else txs
    }

    /** Toleranz des Vorschlags «Umbuchung»: ± 1 % der beobachteten Änderung. */
    private val MATCH_TOLERANCE = BigDecimal("0.01")

    /**
     * Vorschlag je Auszahlung der Vorschau ([PortfolioTxType.WITHDRAWAL], Reihenfolge wie [txs]): gibt es eine ZÄHLENDE
     * beobachtete Adresse dieses Coins, deren beobachtete Änderung (erste Beobachtung, Zunahme zwischen zwei
     * Beobachtungen, [AddressBalanceHistory.of]) der Menge entspricht (± 1 %; Menge oder Menge − Gebühr im Coin) →
     * Umbuchung mit dieser Adresse als Ziel (erste passende in der Reihenfolge der Adressen); sonst keine Vorauswahl.
     */
    fun proposeWithdrawals(txs: List<CsvImportTx>, addresses: List<WatchedAddress>): List<CsvWithdrawalProposal> {
        // Zunahmen je Coin mit Ziel (nur zählende Adressen: «Nur anzeigen» hat keinen Verlauf)
        val changes = ArrayList<Triple<String, BigDecimal, String>>()
        for (a in addresses.filter { it.countInPortfolio }) {
            val name = a.label ?: WatchedAddressRules.shortAddress(a.address)
            for (h in AddressBalanceHistory.of(listOf(a))) {
                var before = BigDecimal.ZERO
                for (o in h.observations.sortedBy { it.time }) {
                    val delta = o.balance - before
                    if (delta.signum() > 0) changes += Triple(PortfolioCalculator.normalizeCoin(h.coin), delta, name)
                    before = o.balance
                }
            }
        }
        return txs.filter { it.type == PortfolioTxType.WITHDRAWAL }.map { tx ->
            val coinFee = if (tx.feeUnit == PortfolioFeeUnit.COIN) tx.fee ?: BigDecimal.ZERO else BigDecimal.ZERO
            val amounts = listOf(tx.amount, tx.amount - coinFee).filter { it.signum() > 0 }
            val hit = changes.firstOrNull { (coin, delta, _) ->
                coin == tx.coin && amounts.any { (it - delta).abs() <= delta * MATCH_TOLERANCE }
            }
            CsvWithdrawalProposal(tx.importKey, hit?.let { CsvWithdrawalChoice.TRANSFER }, hit?.third)
        }
    }

    /** Jede Auszahlung der Vorschau hat eine Wahl ([choices] je [CsvImportTx.importKey]) — erst dann «Importieren». */
    fun allWithdrawalsDecided(txs: List<CsvImportTx>, choices: Map<String, CsvWithdrawalChoice>): Boolean =
        txs.all { it.type != PortfolioTxType.WITHDRAWAL || choices[it.importKey] != null }

    /**
     * Wahl übernehmen: Auszahlungen mit [CsvWithdrawalChoice.TRANSFER] werden Umbuchungen (Ziel aus [destinations], sonst
     * null), die übrigen bleiben Auszahlungen. Andere Arten unverändert.
     */
    fun applyWithdrawalChoices(
        txs: List<CsvImportTx>,
        choices: Map<String, CsvWithdrawalChoice>,
        destinations: Map<String, String?> = emptyMap(),
    ): List<CsvImportTx> = txs.map { tx ->
        if (tx.type == PortfolioTxType.WITHDRAWAL && choices[tx.importKey] == CsvWithdrawalChoice.TRANSFER) {
            tx.copy(type = PortfolioTxType.TRANSFER, transferTo = destinations[tx.importKey])
        } else tx
    }

    /**
     * Währungen, für die Tageskurse gebraucht werden (Preis- und Gebührenwährung, ohne USD/USDT), mit dem
     * frühesten und spätesten Tag (Tage seit 1970-01-01) — eine Abfrage je Währung.
     */
    fun fxNeeds(rows: List<CsvImportRow>, zone: String = CsvImportParser.DEFAULT_ZONE): Map<String, LongRange> {
        val out = LinkedHashMap<String, LongRange>()
        for (row in rows) {
            val day = CsvImport.epochDay(row.timeMillis, zone)
            val currencies = listOfNotNull(
                row.quote.takeIf { row.price != null || row.swapValue != null },
                row.feeUnit.takeIf { row.fee != null && (it == row.quote || it in FIAT) },
            )
            for (c in currencies) {
                if (c == "USD" || c == "USDT") continue
                val r = out[c]
                out[c] = if (r == null) day..day else minOf(r.first, day)..maxOf(r.last, day)
            }
        }
        return out
    }
}
