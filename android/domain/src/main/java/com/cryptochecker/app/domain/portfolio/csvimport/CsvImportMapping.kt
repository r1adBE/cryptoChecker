package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxEngines
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.util.TextLimit
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
    /** Gebühr-Zeile (CSV «Fee»): Veräusserung zum Tageskurs, gespeichert als Verkauf mit Merker. */
    val feeRow: Boolean = false,
    /** Belohnung, die die Börse als Zins (Lending) ausweist ([TaxEngines.interestReward] auf der Art der Datei). */
    val interest: Boolean = false,
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
    /** Datei über den Grenzen des Zerlegens ([CsvImportResult.tooLarge]): keine Zeilen, Meldung «zu gross». */
    val tooLarge: Boolean = false,
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

    /** Höchstlänge der Notiz (wie [com.cryptochecker.app.data.local.model.NOTE_MAX]). */
    private const val NOTE_MAX = 120

    /** Zeilen je Block beim Umrechnen der Vorschau ([preview]): dazwischen Fortschritt und Abbruch. */
    const val PREVIEW_BLOCK = 100

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
                // Jede Fiat-Währung mit Tageskurs ([CsvImport.isFiat]) ist Geld, nie ein «dritter Coin»
                !CsvImport.isFiat(unit) && unit != row.quote && unit != "USDT" -> { feeCoin = unit; feeCoinAmount = rowFee }
                else -> Unit // Fiat-Gebühr ohne Tageskurs: weggelassen, die Zeile steht unter missingFx ([CsvImport.priceInUsdt])
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
            ) TextLimit.takeGraphemes(row.rawKind.trim(), NOTE_MAX).trim().ifEmpty { null } else null,
            importKey = row.key,
            toCoin = row.toCoin?.let { PortfolioCalculator.normalizeCoin(it) }.takeIf { row.kind == CsvImportKind.SWAP },
            toAmount = row.toAmount.takeIf { row.kind == CsvImportKind.SWAP },
            swapValueUsdt = p.swapValueUsdt?.takeIf { row.kind == CsvImportKind.SWAP && it.signum() >= 0 },
            // Eigene Merker: Gebühr-Zeile und Zins — die Notiz bleibt nur zur Anzeige
            feeRow = row.kind == CsvImportKind.FEE,
            interest = row.kind == CsvImportKind.REWARD && TaxEngines.interestReward(row.rawKind),
        )
    }

    /**
     * Vorschau: Doppelte gegen [existingKeys] abtrennen, die neuen umrechnen ([fx] und [usdtUsd] wie [CsvImport.priceInUsdt])
     * und zuordnen. Binance: fast gleiche Geschäfte wie schon importierte ([CsvImportBinance.possibleDuplicates]) kommen
     * in die Übersprungenen statt ein zweites Mal ins Portfolio, ebenso Zeilen, die das Speichern ablehnen würde
     * ([unsavableReason]; nach Zeile sortiert, bei gleicher Zeile Datei-Gründe, dann diese — sie fehlen auch in
     * [CsvImportPreview.missingFx], [CsvImportPreview.missingPrice] und [CsvImportPreview.usdtApproximate]). Umgerechnet
     * wird in Blöcken zu [PREVIEW_BLOCK] Zeilen (jede Zeile für sich, das Ergebnis ist dasselbe); nach jedem Block
     * [onRow] (fertig, gesamt) — für den Fortschritt und um abzubrechen (eine Ausnahme dort bricht die Vorschau ab).
     */
    fun preview(
        result: CsvImportResult,
        existingKeys: Set<String>,
        zone: String = result.fxZone,
        withdrawalsAsTransfer: Boolean = false,
        usdtUsd: Map<Long, BigDecimal>? = null,
        onRow: ((done: Int, total: Int) -> Unit)? = null,
        fx: (String, Long) -> BigDecimal?,
    ): CsvImportPreview {
        val split = CsvImport.splitDuplicates(result.rows, existingKeys)
        val held = if (result.format == CsvImportFormat.BINANCE) {
            CsvImportBinance.possibleDuplicates(split.fresh, existingKeys).mapTo(HashSet()) { it.key }
        } else emptySet()
        val fresh = split.fresh.filter { it.key !in held }
        val priced = ArrayList<CsvImportPriced>(fresh.size)
        for (block in fresh.chunked(PREVIEW_BLOCK)) {
            priced += CsvImport.priceInUsdt(block, zone, usdtUsd, fx)
            onRow?.invoke(priced.size, fresh.size)
        }
        // Zeilen, die das Speichern ablehnen würde, schon hier als übersprungen: Vorschau und Speichern stimmen überein
        val mapped = priced.map { it to toTx(it, withdrawalsAsTransfer) }
        val reasons = mapped.map { unsavableReason(it.second) }
        val kept = mapped.filterIndexed { i, _ -> reasons[i] == null }
        val unsavable = mapped.mapIndexedNotNull { i, m -> reasons[i]?.let { CsvImportSkip(m.second.line, it) } }
        val keptPriced = kept.map { it.first }
        val heldSkips = split.fresh.filter { it.key in held }
            .map { CsvImportSkip(it.line, CsvImportSkipReason.POSSIBLE_DUPLICATE) }
        return CsvImportPreview(
            format = result.format,
            transactions = kept.map { it.second },
            duplicates = split.duplicates.size,
            skipped = (result.skipped + heldSkips + unsavable).sortedBy { it.line },
            missingFx = keptPriced.filter { it.needsFx }.map { it.row.line },
            missingPrice = keptPriced.filter { it.missingPrice }.map { it.row.line },
            zone = result.zone,
            localTimes = result.localTimes,
            dateOrder = result.dateOrder,
            dateOrderAmbiguous = result.dateOrderAmbiguous,
            stableSwaps = keptPriced.any { it.row.stableSwap },
            decimalSample = result.decimalSample,
            decimalMark = result.decimalMark,
            columns = result.columns,
            usdtApproximate = keptPriced.filter { it.usdtApproximate }.map { it.row.line },
            tooLarge = result.tooLarge,
        )
    }

    /** Höchstlänge des erhaltenen Coins eines Tauschs — dieselbe Grenze wie beim Speichern (`PortfolioJson.MAX_FEE_COIN`). */
    const val MAX_TO_COIN = 15

    /**
     * Grund, warum das Speichern [tx] ablehnen würde (Bereinigung `cleanForSave` samt Prüfung in
     * `PortfolioRepository.insertImported`), sonst null. Die Vorschau führt solche Zeilen als übersprungen, damit eine
     * einzelne Zeile nie den ganzen Import kippt. Geprüft in dieser Reihenfolge:
     *  - Coin nach trim/Grossschreibung leer → [CsvImportSkipReason.MISSING_FIELD]
     *  - Menge auf 18 Nachkommastellen gerundet ([PortfolioDecimal.amount], halbe auf gerade) ≤ 0 →
     *    [CsvImportSkipReason.AMOUNT_TOO_SMALL]
     *  - nur Tausch: erhaltener Coin (trim/gross) fehlt, leer, länger als [MAX_TO_COIN] oder gleich dem Coin, oder
     *    erhaltene Menge fehlt bzw. gerundet ≤ 0 → [CsvImportSkipReason.INVALID_SWAP]
     */
    fun unsavableReason(tx: CsvImportTx): CsvImportSkipReason? {
        val coin = PortfolioCalculator.normalizeCoin(tx.coin)
        if (coin.isEmpty()) return CsvImportSkipReason.MISSING_FIELD
        if (PortfolioDecimal.amount(tx.amount).signum() <= 0) return CsvImportSkipReason.AMOUNT_TOO_SMALL
        if (tx.type != PortfolioTxType.SWAP) return null
        val toCoin = tx.toCoin?.let { PortfolioCalculator.normalizeCoin(it) }.orEmpty()
        val toCoinOk = toCoin.isNotEmpty() && toCoin.length <= MAX_TO_COIN && toCoin != coin
        val toAmountOk = tx.toAmount?.let { PortfolioDecimal.amount(it).signum() > 0 } == true
        return if (toCoinOk && toAmountOk) null else CsvImportSkipReason.INVALID_SWAP
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

    /** Toleranz des Vorschlags «Umbuchung»: ± 1 % der beobachteten Änderung … */
    private val MATCH_TOLERANCE = BigDecimal("0.01")

    /** … mindestens aber ein Staubrest (Rundung der Explorer bei sehr kleinen Mengen). */
    private val MATCH_DUST = BigDecimal("0.00000001")

    /** Die beobachtete Änderung liegt höchstens so lange nach der Auszahlung (Bestätigungen, seltene Abfragen). */
    const val MATCH_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

    /**
     * Vorschlag je Auszahlung der Vorschau ([PortfolioTxType.WITHDRAWAL], Reihenfolge wie [txs]): gibt es eine ZÄHLENDE
     * beobachtete Adresse dieses Coins, deren beobachtete Änderung (erste Beobachtung, Zunahme zwischen zwei
     * Beobachtungen, [AddressBalanceHistory.of]) der Menge entspricht (Abweichung höchstens max(1 % der Änderung,
     * [MATCH_DUST]); Menge oder Menge − Gebühr im Coin) und frühestens zur Zeit der Auszahlung, spätestens
     * [MATCH_WINDOW_MILLIS] danach beobachtet wurde → Umbuchung mit dieser Adresse als Ziel (erste passende in der
     * Reihenfolge der Adressen); sonst keine Vorauswahl. Jede beobachtete Änderung passt zu höchstens EINER Auszahlung —
     * zwei gleich grosse Auszahlungen werden sonst beide derselben Zunahme zugeordnet.
     */
    fun proposeWithdrawals(txs: List<CsvImportTx>, addresses: List<WatchedAddress>): List<CsvWithdrawalProposal> {
        // Zunahmen je Coin mit Zeit und Ziel (nur zählende Adressen: «Nur anzeigen» hat keinen Verlauf)
        class Change(val coin: String, val delta: BigDecimal, val time: Long, val name: String)
        val changes = ArrayList<Change>()
        for (a in addresses.filter { it.countInPortfolio }) {
            val name = a.label ?: WatchedAddressRules.shortAddress(a.address)
            for (h in AddressBalanceHistory.of(listOf(a))) {
                var before = BigDecimal.ZERO
                for (o in h.observations.sortedBy { it.time }) {
                    val delta = o.balance - before
                    if (delta.signum() > 0) changes += Change(PortfolioCalculator.normalizeCoin(h.coin), delta, o.time, name)
                    before = o.balance
                }
            }
        }
        val used = HashSet<Change>()
        return txs.filter { it.type == PortfolioTxType.WITHDRAWAL }.map { tx ->
            val coinFee = if (tx.feeUnit == PortfolioFeeUnit.COIN) tx.fee ?: BigDecimal.ZERO else BigDecimal.ZERO
            val amounts = listOf(tx.amount, tx.amount - coinFee).filter { it.signum() > 0 }
            val hit = changes.firstOrNull { c ->
                c !in used && c.coin == tx.coin && c.time >= tx.time && c.time - tx.time <= MATCH_WINDOW_MILLIS &&
                    amounts.any { (it - c.delta).abs() <= (c.delta * MATCH_TOLERANCE).max(MATCH_DUST) }
            }
            hit?.let { used += it }
            CsvWithdrawalProposal(tx.importKey, hit?.let { CsvWithdrawalChoice.TRANSFER }, hit?.name)
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
                // Gebühr in jeder Fiat-Währung mit Tageskurs, auch einer anderen als der Preiswährung ([CsvImport.priceInUsdt])
                row.feeUnit.takeIf { row.fee != null && (it == row.quote || CsvImport.isFiat(it)) },
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
