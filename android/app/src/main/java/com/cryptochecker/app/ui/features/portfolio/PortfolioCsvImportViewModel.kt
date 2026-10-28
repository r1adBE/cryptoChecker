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
import com.cryptochecker.app.data.portfolio.linkStoredTransfers
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.domain.portfolio.csvimport.CsvDateOrder
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportProgress
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRefusal
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportStep
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvProgressThrottle
import com.cryptochecker.app.domain.portfolio.csvimport.CsvReadChoice
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferLink
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalChoice
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalProposal
import com.cryptochecker.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
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
    /**
     * Gespeichert: [count] Zeilen, [enabled] = Portfolio dabei eingeschaltet. Als Zustand (nicht als Rückruf), damit
     * das Ergebnis ein Drehen übersteht; die Ansicht meldet es und setzt zurück ([PortfolioCsvImportViewModel.reset]).
     */
    data class Done(val count: Int, val enabled: Boolean) : CsvImportState
    /** «Abbrechen» beim Speichern: nichts gespeichert; die Ansicht schliesst und setzt zurück. */
    data object Cancelled : CsvImportState
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
    @ApplicationContext context: Context,
    private val repository: PortfolioRepository,
    historic: HistoricPriceSource,
    historySource: PortfolioHistorySource,
    private val settingsRepository: SettingsRepository,
    private val coinLogoSync: com.cryptochecker.app.data.CoinLogoSync,
    private val snapshotUpdater: com.cryptochecker.app.widget.PortfolioSnapshotUpdater,
) : ViewModel() {

    private val reader = PortfolioCsvImportReader(context, repository, historic, historySource)

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

    /** Fortschritt des langen Schritts (Vorschau umrechnen, Speichern), gedrosselt; null = ohne Zeilenzahl (Kreis). */
    private val _progress = MutableStateFlow<CsvImportProgress?>(null)
    val progress: StateFlow<CsvImportProgress?> = _progress.asStateFlow()

    /**
     * Lauf des Fortschritts: steigt bei jedem Zurücksetzen ([clearProgress]). Meldungen kommen vom Abgleich
     * (Dispatchers.Default) bzw. vom Datenbank-Thread und werden auf dem Main-Thread gesetzt ([postProgress]); eine
     * verspätete Meldung eines früheren Laufs fällt weg. Vorher setzte der Hintergrund den Wert direkt — in UI-Tests
     * zeichnete Compose dann auf dem Hintergrund-Thread («CalledFromWrongThread») und stürzte beim Beenden der
     * Activity ab (UiFlowsTest.importCsvAndUndo).
     */
    private var progressRun = 0

    private fun clearProgress() {
        progressRun++
        _progress.value = null
    }

    /** Fortschritt [p] des Laufs [run] auf dem Main-Thread setzen (nur, solange dieser Lauf gilt). */
    private fun postProgress(p: CsvImportProgress, run: Int) {
        viewModelScope.launch(Dispatchers.Main.immediate) { if (run == progressRun) _progress.value = p }
    }

    /** «Abbrechen» beim Speichern: vor jeder Zeile geprüft ([PortfolioRepository.insertImported]), dann nichts gespeichert. */
    private val saveCancelled = AtomicBoolean(false)

    /**
     * Gewählte Lesart (Zeitzone der Zeiten ohne Versatz, Tag/Monat, Dezimalzeichen; null = Regel). Eine
     * Wahl samt der Lesart der gezeigten Vorschau ([CsvImportInput.choice]): «Importieren» nur, wenn beide gleich sind.
     */
    private var chosen = CsvReadChoice()

    /** Nummer der letzten Neu-Lese-Anfrage; nur deren Ergebnis zählt ([reread]). */
    private var rereadGeneration = 0

    /** Laufendes Neu-Lesen ([reread]); ein neues Neu-Lesen und [reset] brechen es ab. */
    private var rereadJob: kotlinx.coroutines.Job? = null

    /** Zuletzt gelesen (Text, Ergebnis, Kurse) — für eine andere Lesart ohne die Datei neu zu lesen. */
    private var input: CsvImportInput? = null

    /** Lesen und Kurse laden ([load]); [cancelLoad] bricht ab — bis dahin ist nichts gespeichert. */
    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(uri: Uri) {
        _state.value = CsvImportState.Loading
        clearProgress()
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
        val read = reader.prepare(reader.readText(uri), chosen)
        input = read
        // Vorschau umrechnen mit Fortschritt «Zeile X von Y» im Ladedialog
        val run = progressRun
        return mapCsvPreview(read) { postProgress(it, run) }
    }

    /**
     * Zeitzone der Zeiten ohne Versatz wählen: den Text im Speicher neu zuordnen (Datei und Tageskurse werden nicht neu
     * geladen; ein um einen Tag verschobenes Datum nimmt den Kurs des letzten Geschäftstags davor).
     */
    fun setZone(zone: String?) = reread(chosen.copy(zone = zone)) { read, choice ->
        // Tag/Monat und Dezimalzeichen wie beim letzten Lesen: nur neu zuordnen; sonst ganz neu (Tageskurse)
        if (read.choice.dateOrder != choice.dateOrder || read.choice.decimalMark != choice.decimalMark) {
            reader.prepare(read.text, choice)
        } else {
            reader.reparse(read, choice)
        }
    }

    /**
     * Reihenfolge Tag/Monat bei mehrdeutigen Daten wählen: Text im Speicher neu lesen; die Tage können sich um Monate
     * verschieben, darum auch die Tageskurse neu laden.
     */
    fun setDateOrder(order: CsvDateOrder) = reread(chosen.copy(dateOrder = order)) { read, choice -> reader.prepare(read.text, choice) }

    /**
     * Dezimalzeichen bei mehrdeutigen Zahlen wählen («42,000 = 42000 oder 42?»): Text im Speicher neu lesen (Preise und
     * Mengen ändern sich; Tageskurse wie beim Umschalten der Reihenfolge neu laden).
     */
    fun setDecimalMark(mark: Char) = reread(chosen.copy(decimalMark = mark)) { read, choice -> reader.prepare(read.text, choice) }

    /**
     * Text im Speicher mit der Lesart [choice] neu lesen ([build]). Die Vorschau steht solange auf
     * «wird neu gelesen» (Knopf aus, gedimmt); übernommen wird nur das Ergebnis der letzten Wahl. Scheitert das Lesen,
     * gilt wieder die Lesart der gezeigten Vorschau. [input] und die Vorschläge erst nach der
     * Generationsprüfung, die Vorschau trägt ihre Lesart ([CsvImportState.Preview.choice]) — ein überholtes Ergebnis
     * kann so nie als Lesart der gezeigten Vorschau gelten.
     */
    private fun reread(choice: CsvReadChoice, build: suspend (CsvImportInput, CsvReadChoice) -> CsvImportInput) {
        chosen = choice
        val read = input ?: return
        val generation = ++rereadGeneration
        (_state.value as? CsvImportState.Preview)?.let { _state.value = it.copy(rereading = true) }
        rereadJob?.cancel()
        rereadJob = viewModelScope.launch {
            val built = runCatchingCancellable { build(read, choice).let { it to mapCsvPreview(it) } }.getOrNull()
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
     * Neue Transaktionen anlegen und gewählte Umbuchungen mit gespeicherten Seiten verknüpfen ([saveCsvImport]). Erst
     * [CsvImportState.Saving], dann im Hintergrund vorbereiten ([prepareCsvImport]) und speichern. Ergebnis als Zustand:
     * [CsvImportState.Done] (Anzahl, eingeschaltet) bzw. [CsvImportState.Cancelled] nach «Abbrechen» ([cancelLoad]) —
     * dann ist nichts gespeichert. Ablauf und Ergebnis: [runCsvImportSave].
     */
    fun confirm() {
        val shown = _state.value as? CsvImportState.Preview ?: return
        // Nicht während des Neu-Lesens und nur mit der Lesart der GEZEIGTEN Vorschau (nicht der zuletzt gelesenen)
        if (!CsvReadChoice.canConfirm(shown.rereading, shown.choice, chosen)) return
        val links = _links.value
        val linked = _linked.value
        val choices = _choices.value
        val destinations = _proposals.value.associate { it.importKey to it.transferTo }
        _state.value = CsvImportState.Saving
        clearProgress()
        saveCancelled.set(false)
        viewModelScope.launch {
            val result = runCsvImportSave(
                prepare = {
                    withContext(Dispatchers.Default) {
                        prepareCsvImport(shown.preview, links, linked, choices, destinations, System.currentTimeMillis())
                    }
                },
                cancelled = saveCancelled::get,
                save = { plan -> save(plan) },
                enable = { enablePortfolio() },
            )
            // Nicht jede Auszahlung gewählt (der Knopf ist dann aus): Vorschau bleibt
            if (result == null) {
                _state.value = shown
                return@launch
            }
            if (result == CsvImportState.Locked || result == CsvImportState.SaveFailed) reset()
            if (result is CsvImportState.Done) clearProgress()
            _state.value = result
        }
    }

    /**
     * Anlegen und Verknüpfen ([saveCsvImport]) mit gedrosseltem Fortschritt. Abgebrochen wird nur über `saveCancelled`
     * (vor jeder Zeile, Transaktion rollt zurück): so steht nie ein Teil-Import und das Ergebnis passt immer zum
     * Gespeicherten.
     */
    private suspend fun save(plan: CsvImportSavePlan): CsvImportSaveOutcome {
        val run = progressRun
        val throttle = CsvProgressThrottle(CsvImportStep.SAVE, plan.entities.size) { postProgress(it, run) }
        throttle.start()
        return withContext(NonCancellable) {
            saveCsvImport(
                insert = { repository.insertImported(plan.entities, plan.batch, throttle::row, saveCancelled::get) },
                link = { repository.linkStoredTransfers(plan.storedUpdates, plan.batch) },
            )
        }
    }

    /** Erste importierte Transaktion: ausgeschaltetes Portfolio einschalten wie von Hand; true = eingeschaltet. */
    private suspend fun enablePortfolio(): Boolean {
        val portfolioEnabled = settingsRepository.current().portfolioEnabled
        if (!PortfolioOff.enableOnSave(isNew = true, portfolioEnabled = portfolioEnabled)) return false
        val enabled = runCatchingCancellable { settingsRepository.setPortfolioEnabled(true) }.isSuccess
        if (enabled) {
            runCatchingCancellable { coinLogoSync.startIfEnabled() }
            runCatchingCancellable { snapshotUpdater.redrawWidgets() }
        }
        return enabled
    }

    /**
     * «Abbrechen» bzw. Zurück im Ladedialog: Lesen und Kursabfragen stoppen. Geschrieben wird erst mit «Importieren»
     * ([confirm]), darum bleibt nichts halb importiert. Während des Speicherns ([CsvImportState.Saving]): vor der nächsten
     * Zeile abbrechen, die Transaktion rollt zurück ([confirm] meldet [CsvImportState.Cancelled]); schon fertig angelegt,
     * gilt der Import. Auch mit schon fertiger Vorschau zurücksetzen: das ViewModel lebt länger als der Ablauf, eine
     * stehengebliebene Vorschau erschiene sonst beim nächsten Öffnen wieder.
     */
    fun cancelLoad() {
        if (_state.value == CsvImportState.Saving) {
            saveCancelled.set(true)
            return
        }
        loadJob?.cancel()
        loadJob = null
        reset()
    }

    /** ViewModel endet während des Speicherns: wie «Abbrechen» (nichts halb gespeichert). */
    override fun onCleared() {
        saveCancelled.set(true)
    }

    fun reset() {
        _state.value = CsvImportState.Idle
        clearProgress()
        input = null
        clearChoices()
        chosen = CsvReadChoice()
        rereadGeneration++
        rereadJob?.cancel()
        rereadJob = null
    }

    private fun clearChoices() {
        _proposals.value = emptyList()
        _choices.value = emptyMap()
        _links.value = emptyList()
        _linked.value = emptySet()
    }
}
