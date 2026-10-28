package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import java.math.BigDecimal

/**
 * Gespeicherte, importierte Auszahlung, Einzahlung oder Umbuchung ohne Gegenstück — mögliche zweite Seite einer neuen
 * Zeile ([CsvTransferMatch.candidates]). Nur importierte Transaktionen ([importKey]): von Hand erfasste Einzahlungen
 * tragen oft bewusst einen Kaufpreis oder ein Kaufdatum, das eine Umbuchung verwerfen würde. Die Felder ab [priceUsdt]
 * braucht nur der Zustand für «Import rückgängig» ([CsvTransferMatch.restoreState]). [linkedImportKey] gesetzt = schon
 * eine verknüpfte Umbuchung (kein Kandidat mehr). [stakingMove]: Staking-Umbuchung der Datei ([CsvTransferMatch.storedWithdrawal]).
 */
data class CsvStoredMove(
    val id: Long,
    val coin: String,
    val type: PortfolioTxType,
    val amount: BigDecimal,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val time: Long,
    val importKey: String,
    val transferFrom: String?,
    val transferTo: String?,
    val priceUsdt: BigDecimal? = null,
    val feeCoinPrice: BigDecimal? = null,
    val acquiredTime: Long? = null,
    val exchange: String? = null,
    val importBatch: String? = null,
    val linkedImportKey: String? = null,
    val stakingMove: Boolean = false,
)

/** Importierte Transaktion für «Import rückgängig» ([CsvTransferMatch.undoPlan]): Id, Importlauf, Zustand vor einer Verknüpfung. */
data class CsvImportedRef(val id: Long, val importBatch: String?, val restoreState: CsvRestoreState?)

/**
 * Was «Import rückgängig» tut ([CsvTransferMatch.undoPlan]): [delete] löschen, [revert] auf den Zustand vor der
 * Verknüpfung zurücksetzen, [detach] in die aufgegangene Zeile der späteren Datei zurückverwandeln (bleibt in ihrem Lauf).
 */
data class CsvUndoPlan(val delete: Set<Long>, val revert: Map<Long, CsvRestoreState>, val detach: Map<Long, CsvAbsorbedRow>)

/**
 * Vorgeschlagene Umbuchung: Auszahlung an Ort A und passende Einzahlung desselben Coins an Ort B ([CsvTransferMatch]).
 * [key] = Kennung in der Vorschau ([CsvTransferMatch.linkId], nie gespeichert). [withdrawalId] / [depositId]: gespeicherte
 * Seite (höchstens eine), sonst eine neue Zeile der Datei ([withdrawalKey] / [depositKey]).
 * [sent] = was A verlässt (Menge + Gebühr im Coin), [received] = was bei B ankommt; [fee]/[feeUnit] = Gebühr der
 * Umbuchung (Differenz im Coin, sonst eine USDT-Gebühr der Auszahlung), [feeCoin]/[feeCoinAmount] = Gebühr der
 * Auszahlung in einem dritten Coin. [unambiguous]: beide Seiten haben genau diesen einen Kandidaten (vorausgewählt).
 */
data class CsvTransferLink(
    val key: String,
    val coin: String,
    val withdrawalKey: String,
    val depositKey: String,
    val withdrawalId: Long?,
    val depositId: Long?,
    val sent: BigDecimal,
    val received: BigDecimal,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val withdrawalTime: Long,
    val depositTime: Long,
    val from: String?,
    val to: String?,
    val unambiguous: Boolean,
    /** Gespeicherte Seite (bei [withdrawalId] bzw. [depositId]); ihr Zustand wird der Zustand für «Rückgängig». */
    val stored: CsvStoredMove? = null,
    /** Neue Zeile, die gegen [stored] aufgeht; ihr Zustand kommt ebenfalls in den Zustand für «Rückgängig». */
    val absorbed: CsvImportTx? = null,
)

/**
 * Gespeicherte Transaktion [id] wird die verknüpfte Umbuchung (Felder wie die neue Umbuchung in [CsvTransferMatch.apply]):
 * Herkunft der Auszahlung in [importKey], der Einzahlung in [linkedImportKey], Zustand davor in [restoreState]; die App
 * setzt dazu den Importlauf des verknüpfenden Imports, damit «Import rückgängig» dieses Laufs die Verknüpfung löst.
 * [absorbed] = die darin aufgehende neue Zeile; ohne Verknüpfung beim Speichern eigene Zeile ([CsvTransferMatch.withFallbacks]).
 */
data class CsvStoredTransfer(
    val id: Long,
    val amount: BigDecimal,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val time: Long,
    val transferFrom: String?,
    val transferTo: String?,
    val importKey: String,
    val linkedImportKey: String,
    val restoreState: CsvRestoreState,
    val absorbed: CsvImportTx? = null,
)

/** Ergebnis der gewählten Umbuchungen: neue Transaktionen und Änderungen gespeicherter. */
data class CsvTransferPlan(val transactions: List<CsvImportTx>, val storedUpdates: List<CsvStoredTransfer>)

/**
 * Umbuchungen über Börsen und Dateien hinweg (reines Kotlin; iOS `CsvTransferMatch.swift`): Eine Auszahlung an A und eine
 * Einzahlung desselben Coins an B werden EINE Umbuchung — Kaufpreis und Haltedauer bleiben (die Umbuchung verändert die
 * Posten nicht), die Differenz ist die Netzwerkgebühr im Coin ([com.cryptochecker.app.domain.portfolio.PortfolioTxType.TRANSFER]).
 * Ohne Verknüpfung gingen die Coins bei A zum Einstand ab und kämen bei B ohne Kaufpreis wieder herein.
 *
 * Kandidat, wenn: gleicher Coin (nie über verschiedene Coins); Einzahlung höchstens [WINDOW_MILLIS] nach der Auszahlung
 * (bis [EARLY_MILLIS] davor: Uhren und Zeitzonen der Börsen weichen ab); angekommen ≤ abgeschickt und die Differenz
 * höchstens [MAX_FEE_SHARE] des Betrags (übliche Netzwerkgebühr); nicht dieselbe Börse auf beiden Seiten; höchstens eine
 * Seite gespeichert (gegen frühere Importe) — zwei neue Zeilen derselben Datei nur bei unbekannter Herkunft (eigene CSV).
 * Eine USDT-Gebühr der Auszahlung bleibt nur bei gleicher Menge (die Umbuchung trägt eine Gebühr); eine Einzahlung mit
 * Gebühr ausser im Coin wird nicht verknüpft.
 *
 * Mehrdeutig (eine Seite hat mehrere Kandidaten) → angezeigt, nicht vorausgewählt; die Wahl bleibt eine Zuordnung
 * (jede Seite höchstens einmal, [toggle]). Die verknüpfte Umbuchung trägt beide Herkunftsschlüssel in eigenen Feldern
 * (Auszahlung `importKey`, Einzahlung `linkedImportKey`), so erkennt der nächste Import beide Zeilen als importiert
 * ([CsvImport.splitDuplicates]).
 *
 * Gegen eine gespeicherte Seite eines früheren Imports wird diese die Umbuchung und wandert in den Importlauf des
 * verknüpfenden Imports; ihr Zustand davor und der der darin aufgegangenen neuen Zeile stehen im Feld `importRestoreState`
 * ([restoreState], [CsvRestoreState]). «Import rückgängig» ([undoPlan]) des verknüpfenden Laufs setzt die gespeicherte
 * Seite zurück — nichts aus ihm bleibt —; der des früheren Laufs verwandelt die Umbuchung in die neue Zeile der späteren
 * Datei zurück (Entscheid des Inhabers: sie gehört dem späteren Lauf, dessen «Rückgängig» sie danach entfernt).
 */
object CsvTransferMatch {

    /** Zeitfenster nach der Auszahlung. */
    const val WINDOW_MILLIS = 72L * 3_600_000L

    /** So lange darf die Einzahlung vor der Auszahlung stehen (Uhren, Zeitzonen ohne Versatz). */
    const val EARLY_MILLIS = 2L * 3_600_000L

    /** Höchster Anteil der Gebühr am abgeschickten Betrag. */
    val MAX_FEE_SHARE = BigDecimal("0.05")

    /** Kennung eines Kandidaten in der Vorschau (Auswahl, Liste); nie gespeichert. */
    fun linkId(withdrawalKey: String, depositKey: String): String = "$withdrawalKey → $depositKey"

    /**
     * Zustand der gespeicherten Seite von [link] vor der Verknüpfung samt der aufgegangenen neuen Zeile — für
     * «Import rückgängig» beider Läufe. null = keine gespeicherte Seite.
     */
    fun restoreState(link: CsvTransferLink): CsvRestoreState? {
        val s = link.stored ?: return null
        val n = link.absorbed ?: return null
        return CsvRestoreState(
            type = s.type,
            amount = s.amount,
            priceUsdt = s.priceUsdt,
            time = s.time,
            fee = s.fee,
            feeUnit = s.feeUnit,
            feeCoin = s.feeCoin,
            feeCoinAmount = s.feeCoinAmount,
            feeCoinPrice = s.feeCoinPrice,
            transferFrom = s.transferFrom,
            transferTo = s.transferTo,
            acquiredTime = s.acquiredTime,
            exchange = s.exchange,
            importKey = s.importKey,
            importBatch = s.importBatch,
            absorbed = CsvAbsorbedRow(
                type = n.type,
                amount = n.amount,
                priceUsdt = n.priceUsdt,
                time = n.time,
                fee = n.fee,
                feeUnit = n.feeUnit,
                feeCoin = n.feeCoin,
                feeCoinAmount = n.feeCoinAmount,
                importKey = n.importKey,
            ),
        )
    }

    /**
     * «Import rückgängig» des Laufs [batch] über alle importierten Transaktionen [rows]: eine Umbuchung, die dieser Lauf
     * mit einer gespeicherten Seite verknüpft hat, wird auf deren Zustand davor zurückgesetzt (samt früherem Importlauf);
     * eine verknüpfte Umbuchung, deren gespeicherte Seite aus diesem Lauf stammt, wird wieder die aufgegangene neue Zeile
     * der späteren Datei (bleibt in deren Lauf; ohne aufgegangene Zeile im Zustand: gelöscht); die übrigen
     * Transaktionen des Laufs werden gelöscht.
     */
    fun undoPlan(rows: List<CsvImportedRef>, batch: String): CsvUndoPlan {
        val delete = LinkedHashSet<Long>()
        val revert = LinkedHashMap<Long, CsvRestoreState>()
        val detach = LinkedHashMap<Long, CsvAbsorbedRow>()
        for (r in rows) {
            val state = r.restoreState
            val absorbed = state?.absorbed
            when {
                r.importBatch == batch && state != null && state.importBatch != batch -> revert[r.id] = state
                r.importBatch != batch && state?.importBatch == batch && absorbed != null -> detach[r.id] = absorbed
                r.importBatch == batch || state?.importBatch == batch -> delete += r.id
            }
        }
        return CsvUndoPlan(delete, revert, detach)
    }

    /** Herkunft (Börse) aus dem Format am Anfang eines Herkunftsschlüssels («BINANCE|…» → Binance); unbekannt = null. */
    fun placeOf(importKey: String): String? {
        val format = importKey.substringBefore('|')
        return CsvImportFormat.entries.firstOrNull { it.name == format }?.exchangeName
    }

    /**
     * Gespeicherte Seite als Auszahlung: Auszahlung oder Umbuchung ohne Ziel (Wahl «Auf eigene Wallet»), aber keine
     * Staking-Umbuchung der Datei ([CsvStoredMove.stakingMove], unabhängig von der Notiz) — die verlässt nie eine Börse;
     * verknüpft, ginge eine echte Einzahlung darin auf und die echte Auszahlung fände kein Gegenstück mehr.
     */
    fun storedWithdrawal(s: CsvStoredMove): Boolean =
        s.type == PortfolioTxType.WITHDRAWAL || (s.type == PortfolioTxType.TRANSFER && s.transferTo == null && !s.stakingMove)

    /** Eine Seite einer möglichen Umbuchung. */
    private class End(
        val key: String,
        val id: Long?,
        val coin: String,
        val time: Long,
        val place: String?,
        /** Auszahlung: Menge + Gebühr im Coin (verlässt A); Einzahlung: Menge − Gebühr im Coin (kommt an). */
        val total: BigDecimal,
        val quoteFee: BigDecimal?,
        val feeCoin: String?,
        val feeCoinAmount: BigDecimal?,
        /** Einzahlung mit Gebühr ausser im Coin (USDT, dritter Coin): nicht verknüpfbar. */
        val foreignFee: Boolean,
        /** Gespeicherte Seite (sonst eine neue Zeile der Datei). */
        val stored: CsvStoredMove? = null,
        /** Neue Zeile der Datei (sonst gespeichert). */
        val tx: CsvImportTx? = null,
    )

    private fun coinFee(fee: BigDecimal?, unit: PortfolioFeeUnit?): BigDecimal =
        if (unit == PortfolioFeeUnit.COIN) fee?.takeIf { it.signum() > 0 } ?: BigDecimal.ZERO else BigDecimal.ZERO

    private fun quoteFee(fee: BigDecimal?, unit: PortfolioFeeUnit?): BigDecimal? =
        fee?.takeIf { unit == PortfolioFeeUnit.QUOTE && it.signum() > 0 }

    private fun withdrawal(key: String, coin: String, amount: BigDecimal, fee: BigDecimal?, unit: PortfolioFeeUnit?,
                           feeCoin: String?, feeCoinAmount: BigDecimal?, time: Long, place: String?,
                           stored: CsvStoredMove? = null, tx: CsvImportTx? = null) =
        End(key, stored?.id, coin, time, place, amount + coinFee(fee, unit), quoteFee(fee, unit), feeCoin, feeCoinAmount,
            foreignFee = false, stored = stored, tx = tx)

    private fun deposit(key: String, coin: String, amount: BigDecimal, fee: BigDecimal?, unit: PortfolioFeeUnit?,
                        feeCoin: String?, time: Long, place: String?, stored: CsvStoredMove? = null, tx: CsvImportTx? = null) =
        End(key, stored?.id, coin, time, place, amount - coinFee(fee, unit), null, null, null,
            foreignFee = quoteFee(fee, unit) != null || feeCoin != null, stored = stored, tx = tx)

    /**
     * Kandidaten zwischen den neuen Zeilen [txs] (aus einer Datei des Formats mit Herkunft [place]) und untereinander bzw.
     * gegen gespeicherte Seiten [stored]; sortiert nach Zeit der Auszahlung, dann der Einzahlung.
     */
    fun candidates(txs: List<CsvImportTx>, place: String?, stored: List<CsvStoredMove>): List<CsvTransferLink> {
        val outs = ArrayList<End>()
        val ins = ArrayList<End>()
        for (t in txs) when (t.type) {
            PortfolioTxType.WITHDRAWAL ->
                outs += withdrawal(t.importKey, t.coin, t.amount, t.fee, t.feeUnit, t.feeCoin, t.feeCoinAmount, t.time, place, tx = t)
            PortfolioTxType.DEPOSIT ->
                ins += deposit(t.importKey, t.coin, t.amount, t.fee, t.feeUnit, t.feeCoin, t.time, place, tx = t)
            else -> Unit
        }
        for (s in stored) {
            // Schon verknüpft (zweiter Herkunftsschlüssel): kein Kandidat mehr
            if (s.linkedImportKey != null) continue
            when {
                storedWithdrawal(s) ->
                    outs += withdrawal(s.importKey, s.coin, s.amount, s.fee, s.feeUnit, s.feeCoin, s.feeCoinAmount, s.time,
                        s.transferFrom ?: placeOf(s.importKey), s)
                s.type == PortfolioTxType.DEPOSIT ->
                    ins += deposit(s.importKey, s.coin, s.amount, s.fee, s.feeUnit, s.feeCoin, s.time, placeOf(s.importKey), s)
                else -> Unit
            }
        }
        val links = ArrayList<CsvTransferLink>()
        // Nur Einzahlungen desselben Coins im Zeitfenster prüfen ([fits] verlangt beides)
        val pairs = CsvTransferWindow.pairs(outs, ins, End::coin, End::time, EARLY_MILLIS, WINDOW_MILLIS, ::fits)
        for ((wi, di) in pairs) {
            val w = outs[wi]
            val d = ins[di]
            val diff = w.total - d.total
            val coinDiff = diff.signum() > 0
            links += CsvTransferLink(
                key = linkId(w.key, d.key),
                coin = w.coin,
                withdrawalKey = w.key,
                depositKey = d.key,
                withdrawalId = w.id,
                depositId = d.id,
                sent = w.total,
                received = d.total,
                fee = if (coinDiff) diff else w.quoteFee,
                feeUnit = if (coinDiff) PortfolioFeeUnit.COIN else w.quoteFee?.let { PortfolioFeeUnit.QUOTE },
                feeCoin = w.feeCoin,
                feeCoinAmount = w.feeCoinAmount,
                withdrawalTime = w.time,
                depositTime = d.time,
                from = w.place,
                to = d.place,
                unambiguous = false,
                stored = w.stored ?: d.stored,
                absorbed = if (w.stored != null) d.tx else if (d.stored != null) w.tx else null,
            )
        }
        val perOut = links.groupingBy { it.withdrawalKey }.eachCount()
        val perIn = links.groupingBy { it.depositKey }.eachCount()
        return links.map { it.copy(unambiguous = perOut[it.withdrawalKey] == 1 && perIn[it.depositKey] == 1) }
            .sortedWith(compareBy({ it.withdrawalTime }, { it.depositTime }, { it.key }))
    }

    private fun fits(w: End, d: End): Boolean {
        if (w.coin != d.coin || (w.id != null && d.id != null) || d.foreignFee) return false
        // Zwei neue Zeilen einer Börsen-Datei: dieselbe Börse kann nicht an sich selbst überweisen
        if (w.place != null && w.place == d.place) return false
        if (d.time < w.time - EARLY_MILLIS || d.time > w.time + WINDOW_MILLIS) return false
        if (d.total.signum() <= 0 || d.total > w.total) return false
        val diff = w.total - d.total
        if (diff > w.total.multiply(MAX_FEE_SHARE)) return false
        // USDT-Gebühr der Auszahlung und Differenz im Coin: die Umbuchung trägt nur eine Gebühr
        return !(diff.signum() > 0 && w.quoteFee != null)
    }

    /** Vorauswahl: alle eindeutigen Kandidaten. */
    fun defaultChoice(links: List<CsvTransferLink>): Set<String> = links.filter { it.unambiguous }.mapTo(LinkedHashSet()) { it.key }

    /**
     * Wahl nach einer neuen Vorschau (andere Zeitzone, Reihenfolge, Dezimalzeichen): schon gezeigte Kandidaten ([seen])
     * behalten ihre Wahl ([chosen]), neue bekommen die Vorauswahl.
     */
    fun carryOver(links: List<CsvTransferLink>, seen: Set<String>, chosen: Set<String>): Set<String> =
        links.filter { if (it.key in seen) it.key in chosen else it.unambiguous }.mapTo(LinkedHashSet()) { it.key }

    /** Kandidat [key] ein- oder ausschalten; eingeschaltet fallen Kandidaten mit derselben Aus- oder Einzahlung weg. */
    fun toggle(links: List<CsvTransferLink>, chosen: Set<String>, key: String, on: Boolean): Set<String> {
        if (!on) return chosen - key
        val link = links.firstOrNull { it.key == key } ?: return chosen
        val conflicts = links.filter { it.key != key && (it.withdrawalKey == link.withdrawalKey || it.depositKey == link.depositKey) }
            .mapTo(HashSet()) { it.key }
        return (chosen - conflicts) + key
    }

    /**
     * Gewählte Umbuchungen ([chosen]) anwenden: zwei neue Zeilen → eine neue Umbuchung an der Stelle der Auszahlung (die
     * Einzahlung entfällt; Herkunft beider in `importKey`/`linkedImportKey`); neue Zeile + gespeicherte Seite → die neue
     * Zeile entfällt, die gespeicherte wird die Umbuchung ([CsvTransferPlan.storedUpdates]). Andere Zeilen unverändert.
     * Menge der Umbuchung = was ankommt ([CsvTransferLink.received]); die Gebühr im Coin geht zusätzlich von der Quelle ab
     * (Konvention von [PortfolioTxType.TRANSFER] in FIFO und Wallet-Aufteilung). Ohne Differenz im Coin gleich [CsvTransferLink.sent].
     */
    fun apply(txs: List<CsvImportTx>, links: List<CsvTransferLink>, chosen: Set<String>): CsvTransferPlan {
        val active = links.filter { it.key in chosen }
        val byWithdrawal = active.filter { it.withdrawalId == null }.associateBy { it.withdrawalKey }
        val absorbed = active.filter { it.depositId == null }.mapTo(HashSet()) { it.depositKey }
        val out = txs.mapNotNull { tx ->
            val link = byWithdrawal[tx.importKey]
            when {
                link != null && link.depositId == null -> CsvImportTx(
                    line = tx.line,
                    coin = tx.coin,
                    type = PortfolioTxType.TRANSFER,
                    amount = link.received,
                    priceUsdt = null,
                    time = link.withdrawalTime,
                    fee = link.fee,
                    feeUnit = link.feeUnit,
                    feeCoin = link.feeCoin,
                    feeCoinAmount = link.feeCoinAmount,
                    note = null,
                    importKey = link.withdrawalKey,
                    transferFrom = link.from,
                    transferTo = link.to,
                    linkedImportKey = link.depositKey,
                )
                link != null || tx.importKey in absorbed -> null
                else -> tx
            }
        }
        val updates = active.mapNotNull { link ->
            val id = link.withdrawalId ?: link.depositId ?: return@mapNotNull null
            val state = restoreState(link) ?: return@mapNotNull null
            CsvStoredTransfer(
                id = id,
                amount = link.received,
                fee = link.fee,
                feeUnit = link.feeUnit,
                feeCoin = link.feeCoin,
                feeCoinAmount = link.feeCoinAmount,
                time = link.withdrawalTime,
                transferFrom = link.from,
                transferTo = link.to,
                importKey = link.withdrawalKey,
                linkedImportKey = link.depositKey,
                restoreState = state,
                absorbed = link.absorbed,
            )
        }
        return CsvTransferPlan(out, updates)
    }
}
