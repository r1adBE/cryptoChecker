// Ablauf des CSV-Imports ohne Ansicht: Datei lesen, Vorschau (Zuordnung, Umbuchungen, Devisenkurse),
// Wahl der Auszahlungen und Speichern als Transaktionen; Zustand `CsvImportState`.
// Eigene Datei, weil die Logik von der Vorschau getrennt lesbar sein soll (wie `PortfolioCsvImporter.swift`).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.BackupLimits
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.PortfolioStorageLockedException
import com.cryptochecker.app.data.portfolio.linkStoredTransfers
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.domain.portfolio.csvimport.CsvDateOrder
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRefusal
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportResult
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvReadChoice
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferLink
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalChoice
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalProposal
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Ablauf des CSV-Imports. */
sealed interface CsvImportState {
    data object Idle : CsvImportState
    data object Loading : CsvImportState
    /**
     * [rereading]: Lesart geändert, Datei wird neu gelesen — «Importieren» aus, Vorschau gedimmt.
     * [choice]: Lesart, mit der GENAU diese Vorschau gebaut wurde ([CsvReadChoice.canConfirm]).
     */
    data class Preview(
        val preview: CsvImportPreview,
        val rereading: Boolean = false,
        val choice: CsvReadChoice = CsvReadChoice(),
    ) : CsvImportState
    /** Keine unterstützte Zeile (fremdes Format, leere Datei, nicht lesbar). */
    data object Unreadable : CsvImportState
    /** Datei zu gross ([CsvImportRefusal.TOO_LARGE]): über 16 MB oder zu viele Zeilen bzw. Spalten. */
    data object TooLarge : CsvImportState
    data object Saving : CsvImportState
    /** Portfolio gesperrt ([com.cryptochecker.app.data.portfolio.PortfolioStorageLock]): nichts gelesen bzw. geschrieben. */
    data object Locked : CsvImportState
    /** Schreiben fehlgeschlagen: nichts importiert — gemeldet statt «0 importiert». */
    data object SaveFailed : CsvImportState
}

/**
 * CSV-Import (Relai, Swissquote, Kraken, Binance, Coinbase, Bitpanda): Datei lesen ([CsvImportParser]), Preise mit den
 * Devisen-Tageskursen (EZB über Frankfurter, letzter Geschäftstag ≤ Tag, [PortfolioHistoryFx.rateOn]) in USDT umrechnen,
 * Vorschau ([CsvImportMapping.preview]: neu / doppelt / übersprungen / ohne Kurs bzw. Preis), Umbuchungen zwischen
 * Börsen vorschlagen ([CsvTransferMatch]: in der Datei und gegen frühere Importe), dann anlegen (mit Herkunftsschlüssel)
 * und verknüpfte gespeicherte Seiten in Umbuchungen ändern. Die erste Transaktion schaltet ein ausgeschaltetes Portfolio
 * ein wie von Hand.
 */
@HiltViewModel
class PortfolioCsvImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: PortfolioRepository,
    private val historic: HistoricPriceSource,
    private val historySource: PortfolioHistorySource,
    private val settingsRepository: SettingsRepository,
    private val coinLogoSync: com.cryptochecker.app.data.CoinLogoSync,
    private val snapshotUpdater: com.cryptochecker.app.widget.PortfolioSnapshotUpdater,
) : ViewModel() {

    private val _state = MutableStateFlow<CsvImportState>(CsvImportState.Idle)
    val state: StateFlow<CsvImportState> = _state.asStateFlow()

    /** Vorschläge je Auszahlung der Vorschau ([CsvImportMapping.proposeWithdrawals]): Vorauswahl und Ziel der Umbuchung. */
    private val _proposals = MutableStateFlow<List<CsvWithdrawalProposal>>(emptyList())
    val proposals: StateFlow<List<CsvWithdrawalProposal>> = _proposals.asStateFlow()

    /** Wahl je Auszahlung ([CsvImportTx.importKey]); «Importieren» erst, wenn jede gewählt ist. */
    private val _choices = MutableStateFlow<Map<String, CsvWithdrawalChoice>>(emptyMap())
    val choices: StateFlow<Map<String, CsvWithdrawalChoice>> = _choices.asStateFlow()

    /** Mögliche Umbuchungen (Auszahlung + Einzahlung desselben Coins, [CsvTransferMatch.candidates]). */
    private val _links = MutableStateFlow<List<CsvTransferLink>>(emptyList())
    val links: StateFlow<List<CsvTransferLink>> = _links.asStateFlow()

    /** Gewählte Umbuchungen ([CsvTransferLink.key]); eindeutige sind vorgewählt. */
    private val _linked = MutableStateFlow<Set<String>>(emptySet())
    val linked: StateFlow<Set<String>> = _linked.asStateFlow()

    /**
     * Gewählte Lesart (Zeitzone der Zeiten ohne Versatz, Tag/Monat, Dezimalzeichen; null = Regel). Eine
     * Wahl samt der Lesart der gezeigten Vorschau ([Input.choice]): «Importieren» nur, wenn beide gleich sind.
     */
    private var chosen = CsvReadChoice()

    /** Nummer der letzten Neu-Lese-Anfrage; nur deren Ergebnis zählt ([reread]). */
    private var rereadGeneration = 0

    /**
     * Text, Ergebnis, Schlüssel, Tageskurse, USDT/USD-Schlüsse ([CsvImport.priceInUsdt]) — für eine neue Vorschau;
     * [choice] = Lesart, mit der gelesen wurde.
     */
    private class Input(
        val text: String, val result: CsvImportResult, val keys: Set<String>,
        val rates: Map<String, List<Pair<Long, Double>>>, val usdtUsd: Map<Long, java.math.BigDecimal>,
        val choice: CsvReadChoice,
    )
    private var input: Input? = null

    /** Lesen und Kurse laden ([load]); [cancelLoad] bricht ab — bis dahin ist nichts gespeichert. */
    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(uri: Uri) {
        _state.value = CsvImportState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Gesperrt: gar nicht erst lesen — die Vorschau endete sonst ohne Grund in «0 importiert»
            if (runCatchingCancellable { repository.writesBlocked() }.getOrDefault(false)) {
                _state.value = CsvImportState.Locked
                return@launch
            }
            val built = runCatchingCancellable { buildPreview(uri) }.onFailure { Timber.w(it, "CSV-Import: Datei nicht lesbar") }
            val preview = built.getOrNull()
            // Zu gross (über 16 MB oder über den Grenzen des Zerlegens) eigens melden, nicht als fremdes Format
            _state.value = when (CsvImportRefusal.of(built.exceptionOrNull() is BackupLimits.TooLargeException, preview)) {
                CsvImportRefusal.TOO_LARGE -> CsvImportState.TooLarge
                CsvImportRefusal.UNREADABLE -> CsvImportState.Unreadable
                null -> preview?.let { propose(it); CsvImportState.Preview(it, choice = input?.choice ?: CsvReadChoice()) }
                    ?: CsvImportState.Unreadable
            }
        }
    }

    /**
     * Vorschläge je Auszahlung: Umbuchung, wenn eine zählende beobachtete Adresse des Coins eine passende Änderung zeigt,
     * sonst keine Vorauswahl. Eine schon getroffene Wahl (gleiche Zeile) bleibt. Dazu mögliche Umbuchungen zwischen Börsen
     * in der Datei und gegen gespeicherte Importe; schon gezeigte behalten ihre Wahl ([CsvTransferMatch.carryOver]).
     */
    private suspend fun propose(preview: CsvImportPreview) = applyProposals(proposalsFor(preview))

    /** Getrennt: erst rechnen ([proposalsFor]), übernehmen ([applyProposals]) erst nach der Generationsprüfung. */
    private class Proposals(val list: List<CsvWithdrawalProposal>, val links: List<CsvTransferLink>)

    private suspend fun proposalsFor(preview: CsvImportPreview): Proposals {
        val addresses = runCatchingCancellable { repository.watchedAddresses.first() }.getOrDefault(emptyList())
        val stored = runCatchingCancellable { repository.getTransactions() }.getOrDefault(emptyList()).mapNotNull { it.toStoredMove() }
        val list = withContext(Dispatchers.Default) { CsvImportMapping.proposeWithdrawals(preview.transactions, addresses) }
        val links = withContext(Dispatchers.Default) {
            CsvTransferMatch.candidates(preview.transactions, preview.format.exchangeName, stored)
        }
        return Proposals(list, links)
    }

    private fun applyProposals(p: Proposals) {
        val list = p.list
        val links = p.links
        val previous = _choices.value
        _proposals.value = list
        _choices.value = list.mapNotNull { p -> (previous[p.importKey] ?: p.choice)?.let { p.importKey to it } }.toMap()
        _linked.value = CsvTransferMatch.carryOver(links, _links.value.mapTo(HashSet()) { it.key }, _linked.value)
        _links.value = links
    }

    /** Umbuchung ein- oder ausschalten (eingeschaltet fallen Kandidaten mit derselben Seite weg). */
    fun setLink(key: String, on: Boolean) {
        _linked.value = CsvTransferMatch.toggle(_links.value, _linked.value, key, on)
    }

    /** Wahl einer Auszahlung. */
    fun setChoice(importKey: String, choice: CsvWithdrawalChoice) {
        _choices.value = _choices.value + (importKey to choice)
    }

    /** Alle Auszahlungen gleich («Alle auf eigene Wallet» / «Alle verlassen das Portfolio»). */
    fun setAllChoices(choice: CsvWithdrawalChoice) {
        _choices.value = _proposals.value.associate { it.importKey to choice }
    }

    private suspend fun buildPreview(uri: Uri): CsvImportPreview {
        val text = withContext(Dispatchers.IO) {
            // Nie ungebremst lesen (wie die Sicherung); UTF-8, sonst Windows-1252 (Excel), BOM weg
            context.contentResolver.openInputStream(uri)?.use { BackupLimits.readLimited(it, max = BackupLimits.CSV_MAX_BYTES) }
                ?.let { CsvImportParser.decode(it) } ?: error("Datei kann nicht gelesen werden")
        }
        val read = prepare(text, chosen)
        input = read
        return mapPreview(read)
    }

    /** Text mit der Lesart [choice] lesen und Tageskurse für den Zeitraum laden (die Vorschau bildet [mapPreview]). */
    private suspend fun prepare(text: String, choice: CsvReadChoice): Input {
        val result = withContext(Dispatchers.Default) {
            CsvImportParser.parse(text, ZoneId.systemDefault().id, choice.zone, choice.dateOrder, choice.decimalMark)
        }
        // Tageskurse je Währung: eine Abfrage über den ganzen Zeitraum (mit Vorlauf für Wochenenden/Feiertage)
        val rates = HashMap<String, List<Pair<Long, Double>>>()
        for ((currency, days) in CsvImportMapping.fxNeeds(result.rows, result.fxZone)) {
            val from = LocalDate.ofEpochDay(days.first - PortfolioHistoryFx.LEAD_DAYS)
            val series = runCatchingCancellable { historic.usdToSeries(currency, from, LocalDate.ofEpochDay(days.last)) }.getOrNull()
            if (series != null) rates[currency] = series.entries.map { it.key to it.value }.sortedBy { it.first }
        }
        val keys = repository.importKeys()
        return Input(text, result, keys, rates, usdtUsdCloses(result), choice)
    }

    /**
     * USDT/USD-Tagesschlüsse (Kraken, [PortfolioHistorySource.usdtUsdDaily]) vom frühesten Tag der Datei bis heute — wie
     * der Steuer-Export (`usdtUsdCloses`), damit Import und Export denselben Schluss DES Tags nehmen. Nicht erreichbar →
     * leer (USD/Fiat dann mit USDT = 1, Zeilen als Näherung markiert, [CsvImportPreview.usdtApproximate]).
     */
    private suspend fun usdtUsdCloses(result: CsvImportResult): Map<Long, java.math.BigDecimal> {
        val first = result.rows.minOfOrNull { com.cryptochecker.app.domain.portfolio.csvimport.CsvImport.epochDay(it.timeMillis, result.fxZone) }
            ?: return emptyMap()
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        val days = (today - first + 1 + PortfolioHistoryFx.LEAD_DAYS).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val closes = try {
            historySource.usdtUsdDaily(days)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "CSV-Import: USDT/USD-Tagesschlüsse fehlen")
            emptyMap()
        }
        return closes.mapNotNull { (day, close) -> PortfolioDecimal.of(close)?.let { day to it } }.toMap()
    }

    /**
     * Abgleich und Umrechnung können bei grossen Dateien dauern: nicht auf dem Main-Thread. Auszahlungen bleiben hier
     * Auszahlungen; die Wahl je Zeile ([choices]) gilt beim Speichern ([CsvImportMapping.applyWithdrawalChoices]).
     */
    private suspend fun mapPreview(read: Input): CsvImportPreview = withContext(Dispatchers.Default) {
        CsvImportMapping.preview(read.result, read.keys, usdtUsd = read.usdtUsd) { currency, day ->
            read.rates[currency]?.let { PortfolioHistoryFx.rateOn(it, day) }?.let { PortfolioDecimal.of(it) }
        }
    }

    /**
     * Zeitzone der Zeiten ohne Versatz wählen: den Text im Speicher neu zuordnen (Datei und Tageskurse werden nicht neu
     * geladen; ein um einen Tag verschobenes Datum nimmt den Kurs des letzten Geschäftstags davor).
     */
    fun setZone(zone: String?) = reread(chosen.copy(zone = zone)) { read, choice ->
        // Tag/Monat und Dezimalzeichen wie beim letzten Lesen: nur neu zuordnen; sonst ganz neu (Tageskurse)
        if (read.choice.dateOrder != choice.dateOrder || read.choice.decimalMark != choice.decimalMark) {
            prepare(read.text, choice)
        } else {
            val result = withContext(Dispatchers.Default) {
                CsvImportParser.parse(read.text, ZoneId.systemDefault().id, choice.zone, choice.dateOrder, choice.decimalMark)
            }
            Input(read.text, result, read.keys, read.rates, read.usdtUsd, choice)
        }
    }

    /**
     * Reihenfolge Tag/Monat bei mehrdeutigen Daten wählen: Text im Speicher neu lesen; die Tage können sich um Monate
     * verschieben, darum auch die Tageskurse neu laden.
     */
    fun setDateOrder(order: CsvDateOrder) = reread(chosen.copy(dateOrder = order)) { read, choice -> prepare(read.text, choice) }

    /**
     * Dezimalzeichen bei mehrdeutigen Zahlen wählen («42,000 = 42000 oder 42?»): Text im Speicher neu lesen (Preise und
     * Mengen ändern sich; Tageskurse wie beim Umschalten der Reihenfolge neu laden).
     */
    fun setDecimalMark(mark: Char) = reread(chosen.copy(decimalMark = mark)) { read, choice -> prepare(read.text, choice) }

    /**
     * Text im Speicher mit der Lesart [choice] neu lesen ([build]). Die Vorschau steht solange auf
     * «wird neu gelesen» (Knopf aus, gedimmt); übernommen wird nur das Ergebnis der letzten Wahl. Scheitert das Lesen,
     * gilt wieder die Lesart der gezeigten Vorschau. [input] und die Vorschläge erst nach der
     * Generationsprüfung, die Vorschau trägt ihre Lesart ([CsvImportState.Preview.choice]) — ein überholtes Ergebnis
     * kann so nie als Lesart der gezeigten Vorschau gelten.
     */
    private fun reread(choice: CsvReadChoice, build: suspend (Input, CsvReadChoice) -> Input) {
        chosen = choice
        val read = input ?: return
        val generation = ++rereadGeneration
        (_state.value as? CsvImportState.Preview)?.let { _state.value = it.copy(rereading = true) }
        viewModelScope.launch {
            val built = runCatchingCancellable { build(read, choice).let { it to mapPreview(it) } }.getOrNull()
            if (generation != rereadGeneration) return@launch
            val shown = _state.value as? CsvImportState.Preview ?: return@launch
            if (built == null) {
                chosen = shown.choice
                _state.value = shown.copy(rereading = false)
                return@launch
            }
            val proposals = proposalsFor(built.second)
            if (generation != rereadGeneration || _state.value !is CsvImportState.Preview) return@launch
            input = built.first
            applyProposals(proposals)
            _state.value = CsvImportState.Preview(built.second, choice = built.first.choice)
        }
    }

    /**
     * Neue Transaktionen anlegen und gewählte Umbuchungen mit gespeicherten Seiten verknüpfen; [onDone] mit der Anzahl
     * (im Hauptthread), [onPortfolioEnabled] wenn eingeschaltet.
     */
    fun confirm(onDone: (Int) -> Unit, onPortfolioEnabled: () -> Unit) {
        val shown = _state.value as? CsvImportState.Preview ?: return
        // Nicht während des Neu-Lesens und nur mit der Vorschau der aktuellen Lesart
        // Mit der Lesart der GEZEIGTEN Vorschau (nicht der zuletzt gelesenen)
        if (!CsvReadChoice.canConfirm(shown.rereading, shown.choice, chosen)) return
        val preview = shown.preview
        val choices = _choices.value
        val plan = CsvTransferMatch.apply(preview.transactions, _links.value, _linked.value)
        // Jede Auszahlung (ohne verknüpfte Umbuchung) braucht eine Wahl (der Knopf ist sonst aus)
        if (!CsvImportMapping.allWithdrawalsDecided(plan.transactions, choices)) return
        // Datei «neueste zuerst»: älteste zuerst speichern (Id folgt der Zeit)
        val txs = CsvImportMapping.insertOrder(
            CsvImportMapping.applyWithdrawalChoices(
                plan.transactions, choices, _proposals.value.associate { it.importKey to it.transferTo },
            ),
        )
        _state.value = CsvImportState.Saving
        viewModelScope.launch {
            // Kennung dieses Importlaufs («Import rückgängig»): Format, Zeit, Hash der Zeilenschlüssel
            val batch = PortfolioImportBatch.newId(preview.format.name, System.currentTimeMillis(), txs.map { it.importKey })
            val insert = runCatchingCancellable { repository.insertImported(txs.map { it.toEntity() }, batch) }
                .onFailure { Timber.w(it, "CSV-Import: Speichern fehlgeschlagen") }
            val link = if (insert.isFailure) null else runCatchingCancellable { repository.linkStoredTransfers(plan.storedUpdates, batch) }
                .onFailure { Timber.w(it, "CSV-Import: Umbuchung nicht verknüpft") }
            val inserted = insert.getOrDefault(0)
            val count = inserted + (link?.getOrNull() ?: 0)
            // Nichts geschrieben, weil Schreiben scheiterte (gesperrt oder Fehler): Grund nennen statt «0 importiert»
            val failure = insert.exceptionOrNull() ?: link?.exceptionOrNull()?.takeIf { count == 0 }
            if (failure != null) {
                reset()
                _state.value = if (failure is PortfolioStorageLockedException) CsvImportState.Locked else CsvImportState.SaveFailed
                return@launch
            }
            var enabled = false
            // Nur neu angelegte Transaktionen schalten ein (verknüpfte gespeicherte gab es schon)
            if (inserted > 0 && PortfolioOff.enableOnSave(isNew = true, portfolioEnabled = settingsRepository.current().portfolioEnabled)) {
                enabled = runCatchingCancellable { settingsRepository.setPortfolioEnabled(true) }.isSuccess
                if (enabled) {
                    runCatchingCancellable { coinLogoSync.startIfEnabled() }
                    runCatchingCancellable { snapshotUpdater.redrawWidgets() }
                }
            }
            reset()
            onDone(count)
            // Wie iOS: eingeschaltet → diese Bestätigung (ersetzt die Anzahl)
            if (enabled) onPortfolioEnabled()
        }
    }

    /**
     * «Abbrechen» bzw. Zurück im Ladedialog: Lesen und Kursabfragen stoppen. Geschrieben wird erst mit «Importieren»
     * ([confirm]), darum bleibt nichts halb importiert. Während des Speicherns ([CsvImportState.Saving]) nicht möglich.
     * Auch mit schon fertiger Vorschau zurücksetzen: das ViewModel lebt länger als der Ablauf, eine stehengebliebene
     * Vorschau erschiene sonst beim nächsten Öffnen wieder.
     */
    fun cancelLoad() {
        if (_state.value == CsvImportState.Saving) return
        loadJob?.cancel()
        loadJob = null
        reset()
    }

    fun reset() {
        _state.value = CsvImportState.Idle
        input = null
        clearChoices()
        chosen = CsvReadChoice()
        rereadGeneration++
    }

    private fun clearChoices() {
        _proposals.value = emptyList()
        _choices.value = emptyMap()
        _links.value = emptyList()
        _linked.value = emptySet()
    }
}
