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
import com.cryptochecker.app.data.portfolio.insertImportedLinked
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.domain.portfolio.csvimport.CsvDateOrder
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportProgress
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRateGate
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRefusal
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportStep
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvProgressThrottle
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRateConsent
import com.cryptochecker.app.domain.portfolio.csvimport.CsvReadChoice
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferLink
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalChoice
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalProposal
import com.cryptochecker.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.cryptochecker.app.domain.util.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * CSV-Import (Relai, Swissquote, Kraken, Binance, Coinbase, Bitpanda): Datei lesen ([CsvImportParser]), Preise mit den
 * Devisen-Tageskursen (Referenzkurse der EZB, letzter Geschäftstag ≤ Tag, [PortfolioHistoryFx.rateOn]) in USDT umrechnen,
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
    private val coinLogoSync: com.cryptochecker.app.logos.CoinLogoSync,
    private val snapshotUpdater: com.cryptochecker.app.widget.PortfolioSnapshotUpdater,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val reader = PortfolioCsvImportReader(context, repository, historic, historySource) {
        settingsRepository.current().taxTemplate.zoneId
    }

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
     * verspätete Meldung eines früheren Laufs fällt weg. Nicht direkt aus dem Hintergrund gesetzt: In UI-Tests
     * zeichnete Compose sonst auf dem Hintergrund-Thread («CalledFromWrongThread») und stürzte beim Beenden der
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
        val list = withContext(defaultDispatcher) { CsvImportMapping.proposeWithdrawals(preview.transactions, addresses) }
        val links = withContext(defaultDispatcher) {
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
        linkOwnWallets()
    }

    /** Umbuchung ein/aus (ein: Kandidaten derselben Seite weg; aus: die Auszahlung braucht eine neue Wahl). */
    fun setLink(key: String, on: Boolean) {
        _linked.value = CsvTransferMatch.toggle(_links.value, _linked.value, key, on)
        if (!on) _links.value.firstOrNull { it.key == key }?.let { _choices.value = _choices.value - it.withdrawalKey }
        linkOwnWallets()
    }

    /** Wahl einer Auszahlung. */
    fun setChoice(importKey: String, choice: CsvWithdrawalChoice) {
        _choices.value = _choices.value + (importKey to choice)
        linkOwnWallets()
    }

    /** Alle Auszahlungen gleich («Alle auf eigene Wallet» / «Alle verlassen das Portfolio»). */
    fun setAllChoices(choice: CsvWithdrawalChoice) {
        _choices.value = _proposals.value.associate { it.importKey to choice }
        linkOwnWallets()
    }

    /** «Auf eigene Wallet» mit freier passender Einzahlung wird deren Umbuchung ([CsvImportMapping.linkOwnWallets]). */
    private fun linkOwnWallets() {
        _linked.value = CsvImportMapping.linkOwnWallets(_links.value, _linked.value, _choices.value)
    }

    private suspend fun buildPreview(uri: Uri): CsvImportPreview {
        val read = reader.prepare(reader.readText(uri), chosen)
        input = read
        // Vorschau umrechnen mit Fortschritt «Zeile X von Y» im Ladedialog
        val run = progressRun
        return mapCsvPreview(read) { postProgress(it, run) }
    }

    /**
     * Zeitzone der Zeiten ohne Versatz wählen: den Text im Speicher neu zuordnen; Tageskurse nur neu laden, wenn ein Tag
     * nun ausserhalb der geladenen liegt ([PortfolioCsvImportReader.reparse]).
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
     * «Erneut versuchen» bei Zeilen ohne Tageskurs: Text im Speicher mit derselben Lesart neu lesen und die Tageskurse
     * neu laden — die Vorschau wird neu bewertet ([CsvImportRateGate]).
     */
    fun reloadRates() = reread(chosen) { read, choice -> reader.prepare(read.text, choice) }

    /** Schon importierte Zeilen trotzdem übernehmen bzw. wieder nicht ([CsvReadChoice.keepDuplicates]): nur neu zuordnen. */
    fun setKeepDuplicates(keep: Boolean) =
        reread(chosen.copy(keepDuplicates = keep)) { read, choice -> reader.reparse(read, choice) }

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
     * dann ist nichts gespeichert. Ablauf und Ergebnis: [runCsvImportSave]. [consent]: Bestätigung «Trotzdem
     * importieren» der Zeilen ohne Tageskurs samt den Vermerken in der Sprache der App ([CsvImportRateGate]).
     */
    fun confirm(consent: CsvRateConsent = CsvRateConsent()) {
        val shown = _state.value as? CsvImportState.Preview ?: return
        // Nicht während des Neu-Lesens und nur mit der Lesart der GEZEIGTEN Vorschau (nicht der zuletzt gelesenen)
        if (!CsvReadChoice.canConfirm(shown.rereading, shown.choice, chosen)) return
        // Zeilen ohne Tageskurs: nur mit ausdrücklicher Bestätigung (sonst fehlten Preis bzw. Gebühr still und dauerhaft)
        if (!CsvImportRateGate.canImport(shown.preview.missingFx.size, consent.accepted)) return
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
                    withContext(defaultDispatcher) {
                        prepareCsvImport(shown.preview, links, linked, choices, destinations, System.currentTimeMillis(), consent)
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
            saveCsvImport {
                repository.insertImportedLinked(
                    plan.entities, plan.batch, plan.storedUpdates, plan.fallbacks, throttle::row, saveCancelled::get,
                )
            }
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
