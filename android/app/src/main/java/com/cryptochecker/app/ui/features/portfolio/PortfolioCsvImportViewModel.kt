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
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportFormat
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportResult
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
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
    data class Preview(val preview: CsvImportPreview) : CsvImportState
    /** Keine unterstützte Zeile (fremdes Format, leere Datei, nicht lesbar). */
    data object Unreadable : CsvImportState
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

    /** Zeitzone der Zeiten ohne Versatz (Gerät / Europe/Zurich / UTC); null = Regel ([CsvImportParser.zoneFor]). */
    private val _zone = MutableStateFlow<String?>(null)

    /** Reihenfolge Tag/Monat bei mehrdeutigen Daten, in der Vorschau gewählt; null = Standard (Tag zuerst). */
    private val _dateOrder = MutableStateFlow<CsvDateOrder?>(null)

    /** Dezimalzeichen bei mehrdeutigen Zahlen («42,000»), in der Vorschau gewählt; null = Standard (Zeichen = Dezimal). */
    private val _decimalMark = MutableStateFlow<Char?>(null)

    /** Text, Ergebnis, Schlüssel, Tageskurse, USDT/USD-Schlüsse ([CsvImport.priceInUsdt]) — für eine neue Vorschau. */
    private class Input(
        val text: String, val result: CsvImportResult, val keys: Set<String>,
        val rates: Map<String, List<Pair<Long, Double>>>, val usdtUsd: Map<Long, java.math.BigDecimal>,
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
            _state.value = runCatchingCancellable { buildPreview(uri) }
                .onFailure { Timber.w(it, "CSV-Import: Datei nicht lesbar") }
                .getOrNull()
                ?.takeIf { it.format != CsvImportFormat.UNKNOWN && (it.transactions.isNotEmpty() || it.duplicates > 0 || it.skipped.isNotEmpty()) }
                ?.let { propose(it); CsvImportState.Preview(it) }
                ?: CsvImportState.Unreadable
        }
    }

    /**
     * Vorschläge je Auszahlung: Umbuchung, wenn eine zählende beobachtete Adresse des Coins eine passende Änderung zeigt,
     * sonst keine Vorauswahl. Eine schon getroffene Wahl (gleiche Zeile) bleibt. Dazu mögliche Umbuchungen zwischen Börsen
     * in der Datei und gegen gespeicherte Importe; schon gezeigte behalten ihre Wahl ([CsvTransferMatch.carryOver]).
     */
    private suspend fun propose(preview: CsvImportPreview) {
        val addresses = runCatchingCancellable { repository.watchedAddresses.first() }.getOrDefault(emptyList())
        val stored = runCatchingCancellable { repository.getTransactions() }.getOrDefault(emptyList()).mapNotNull { it.toStoredMove() }
        val list = withContext(Dispatchers.Default) { CsvImportMapping.proposeWithdrawals(preview.transactions, addresses) }
        val links = withContext(Dispatchers.Default) {
            CsvTransferMatch.candidates(preview.transactions, preview.format.exchangeName, stored)
        }
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
        return prepare(text)
    }

    /** Text lesen, Tageskurse für den Zeitraum laden, Vorschau bilden. */
    private suspend fun prepare(text: String): CsvImportPreview {
        val result = withContext(Dispatchers.Default) {
            CsvImportParser.parse(text, ZoneId.systemDefault().id, _zone.value, _dateOrder.value, _decimalMark.value)
        }
        // Tageskurse je Währung: eine Abfrage über den ganzen Zeitraum (mit Vorlauf für Wochenenden/Feiertage)
        val rates = HashMap<String, List<Pair<Long, Double>>>()
        for ((currency, days) in CsvImportMapping.fxNeeds(result.rows, result.fxZone)) {
            val from = LocalDate.ofEpochDay(days.first - PortfolioHistoryFx.LEAD_DAYS)
            val series = runCatchingCancellable { historic.usdToSeries(currency, from, LocalDate.ofEpochDay(days.last)) }.getOrNull()
            if (series != null) rates[currency] = series.entries.map { it.key to it.value }.sortedBy { it.first }
        }
        val keys = repository.importKeys()
        val read = Input(text, result, keys, rates, usdtUsdCloses(result))
        input = read
        return mapPreview(read)
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
    fun setZone(zone: String?) {
        _zone.value = zone
        val read = input ?: return
        viewModelScope.launch {
            val preview = runCatchingCancellable {
                val result = withContext(Dispatchers.Default) {
                    CsvImportParser.parse(read.text, ZoneId.systemDefault().id, zone, _dateOrder.value, _decimalMark.value)
                }
                val updated = Input(read.text, result, read.keys, read.rates, read.usdtUsd)
                input = updated
                mapPreview(updated)
            }.getOrNull() ?: return@launch
            if (_state.value is CsvImportState.Preview && _zone.value == zone) {
                propose(preview)
                _state.value = CsvImportState.Preview(preview)
            }
        }
    }

    /**
     * Reihenfolge Tag/Monat bei mehrdeutigen Daten wählen: Text im Speicher neu lesen; die Tage können sich um Monate
     * verschieben, darum auch die Tageskurse neu laden.
     */
    fun setDateOrder(order: CsvDateOrder) {
        _dateOrder.value = order
        reprepare { _dateOrder.value == order }
    }

    /**
     * Dezimalzeichen bei mehrdeutigen Zahlen wählen («42,000 = 42000 oder 42?»): Text im Speicher neu lesen (Preise und
     * Mengen ändern sich; Tageskurse wie beim Umschalten der Reihenfolge neu laden).
     */
    fun setDecimalMark(mark: Char) {
        _decimalMark.value = mark
        reprepare { _decimalMark.value == mark }
    }

    /** Text im Speicher samt Tageskursen neu lesen; übernommen nur, solange die Wahl noch gilt ([stillChosen]). */
    private fun reprepare(stillChosen: () -> Boolean) {
        val read = input ?: return
        viewModelScope.launch {
            val preview = runCatchingCancellable { prepare(read.text) }.getOrNull() ?: return@launch
            if (_state.value is CsvImportState.Preview && stillChosen()) {
                propose(preview)
                _state.value = CsvImportState.Preview(preview)
            }
        }
    }

    /**
     * Neue Transaktionen anlegen und gewählte Umbuchungen mit gespeicherten Seiten verknüpfen; [onDone] mit der Anzahl
     * (im Hauptthread), [onPortfolioEnabled] wenn eingeschaltet.
     */
    fun confirm(onDone: (Int) -> Unit, onPortfolioEnabled: () -> Unit) {
        val preview = (_state.value as? CsvImportState.Preview)?.preview ?: return
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
        _zone.value = null
        _dateOrder.value = null
        _decimalMark.value = null
    }

    private fun clearChoices() {
        _proposals.value = emptyList()
        _choices.value = emptyMap()
        _links.value = emptyList()
        _linked.value = emptySet()
    }
}
