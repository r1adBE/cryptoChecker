// Lesen für den CSV-Import ohne Ansicht: Datei (höchstens 16 MB), Zerlegen, Tageskurse und USDT/USD-Schlüsse laden und
// die Vorschau umrechnen — alles abseits des Main-Threads. Eigene Datei, weil `PortfolioCsvImportViewModel.kt` an der
// Längengrenze ist (wie `PortfolioCsvImporter.load` auf iOS).
package com.cryptochecker.app.ui.features.portfolio

import android.content.Context
import android.net.Uri
import com.cryptochecker.app.data.BackupLimits
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImport
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportProgress
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRateDays
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportResult
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportStep
import com.cryptochecker.app.domain.portfolio.csvimport.CsvProgressThrottle
import com.cryptochecker.app.domain.portfolio.csvimport.CsvReadChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Text, Ergebnis, Schlüssel, Tageskurse, USDT/USD-Schlüsse ([CsvImport.priceInUsdt]) — für eine neue Vorschau;
 * [choice] = Lesart, mit der gelesen wurde; [rateDays] = Tage, für die die Kurse geladen wurden ([CsvImportRateDays.of]).
 */
internal class CsvImportInput(
    val text: String, val result: CsvImportResult, val keys: Set<String>,
    val rates: Map<String, List<Pair<Long, Double>>>, val usdtUsd: Map<Long, BigDecimal>,
    val choice: CsvReadChoice, val rateDays: Map<String, LongRange> = emptyMap(),
    /** Tageskurse einer gebrauchten Währung liessen sich nicht laden ([CsvImportPreview.ratesFailed]). */
    val ratesFailed: Boolean = false,
)

/** Datei lesen, zerlegen und Kurse laden für [PortfolioCsvImportViewModel]. */
internal class PortfolioCsvImportReader(
    private val context: Context,
    private val repository: PortfolioRepository,
    private val historic: HistoricPriceSource,
    private val historySource: PortfolioHistorySource,
    /** Zeitzone der gewählten Steuer-Vorlage (`TaxTemplate.zoneId`; null = Neutral) für [CsvImportParser.fxZoneFor]. */
    private val templateZone: suspend () -> String?,
) {
    /** Nie ungebremst lesen (wie die Sicherung); UTF-8, sonst Windows-1252 (Excel), BOM weg. */
    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { BackupLimits.readLimited(it, max = BackupLimits.CSV_MAX_BYTES) }
            ?.let { CsvImportParser.decode(it) } ?: error("Datei kann nicht gelesen werden")
    }

    /** Text mit der Lesart [choice] lesen und Tageskurse für den Zeitraum laden (die Vorschau bildet [mapCsvPreview]). */
    suspend fun prepare(text: String, choice: CsvReadChoice): CsvImportInput = load(text, parse(text, choice), choice)

    /** Kurse für das gelesene [result] laden. */
    private suspend fun load(text: String, result: CsvImportResult, choice: CsvReadChoice): CsvImportInput {
        // Tageskurse je Währung: eine Abfrage über den ganzen Zeitraum (mit Vorlauf für Wochenenden/Feiertage)
        val rates = HashMap<String, List<Pair<Long, Double>>>()
        // Keine gültige Antwort (auch eine Ausnahme) ist etwas anderes als «für die Währung gibt es keinen Kurs»
        var failed = false
        for ((currency, days) in CsvImportMapping.fxNeeds(result.rows, result.fxZone)) {
            val from = LocalDate.ofEpochDay(days.first - PortfolioHistoryFx.LEAD_DAYS)
            val load = runCatchingCancellable { historic.usdToSeriesChecked(currency, from, LocalDate.ofEpochDay(days.last)) }.getOrNull()
            if (load == null || load.failed) failed = true
            val series = load?.rates
            if (series != null) rates[currency] = series.entries.map { it.key to it.value }.sortedBy { it.first }
        }
        val keys = repository.importKeys()
        val days = CsvImportRateDays.of(result.rows, result.fxZone)
        return CsvImportInput(text, result, keys, rates, usdtUsdCloses(result), choice, days, failed)
    }

    /**
     * Nur neu zerlegen (andere Zeitzone): Schlüssel, Tageskurse und USDT/USD-Schlüsse bleiben — ausser ein Tag liegt nun
     * ausserhalb der geladenen Kurse ([CsvImportRateDays.cover]; die Zeitzone schob eine Zeile auf den nächsten Tag): dann
     * neu laden, sonst gälte still der Kurs des Vortags.
     */
    suspend fun reparse(read: CsvImportInput, choice: CsvReadChoice): CsvImportInput {
        val result = parse(read.text, choice)
        if (!CsvImportRateDays.cover(read.rateDays, CsvImportRateDays.of(result.rows, result.fxZone))) {
            return load(read.text, result, choice)
        }
        return CsvImportInput(read.text, result, read.keys, read.rates, read.usdtUsd, choice, read.rateDays, read.ratesFailed)
    }

    /** Tageskurse in der Zeitzone der Steuer-Vorlage (Neutral: Gerät), wie der Steuer-Export ([CsvImportParser.fxZoneFor]). */
    private suspend fun parse(text: String, choice: CsvReadChoice): CsvImportResult {
        val device = ZoneId.systemDefault().id
        val fxZone = CsvImportParser.fxZoneFor(templateZone(), device)
        return withContext(Dispatchers.Default) {
            CsvImportParser.parse(text, device, choice.zone, choice.dateOrder, choice.decimalMark, fxZone)
        }
    }

    /**
     * USDT/USD-Tagesschlüsse (Kraken, [PortfolioHistorySource.usdtUsdDaily]) vom frühesten Tag der Datei bis heute — wie
     * der Steuer-Export (`usdtUsdCloses`), damit Import und Export denselben Schluss DES Tags nehmen. Nicht erreichbar →
     * leer (USD/Fiat dann mit USDT = 1, Zeilen als Näherung markiert, [CsvImportPreview.usdtApproximate]).
     */
    private suspend fun usdtUsdCloses(result: CsvImportResult): Map<Long, BigDecimal> {
        val first = result.rows.minOfOrNull { CsvImport.epochDay(it.timeMillis, result.fxZone) } ?: return emptyMap()
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
}

/**
 * Abgleich und Umrechnung können bei grossen Dateien dauern: nicht auf dem Main-Thread und nach jedem Block abbrechbar;
 * mit [report] gedrosselt als Fortschritt «Zeile X von Y» ([CsvProgressThrottle]). Auszahlungen bleiben hier
 * Auszahlungen; die Wahl je Zeile gilt beim Speichern ([CsvImportMapping.applyWithdrawalChoices]).
 */
internal suspend fun mapCsvPreview(
    read: CsvImportInput,
    report: ((CsvImportProgress) -> Unit)? = null,
): CsvImportPreview = withContext(Dispatchers.Default) {
    val job = coroutineContext.job
    var throttle: CsvProgressThrottle? = null
    val onRow = { done: Int, total: Int ->
        job.ensureActive()
        if (report != null) {
            (throttle ?: CsvProgressThrottle(CsvImportStep.PREVIEW, total, report = report).also { throttle = it; it.start() })
                .row(done)
        }
    }
    CsvImportMapping.preview(
        read.result, read.keys, usdtUsd = read.usdtUsd, keepDuplicates = read.choice.keepDuplicates, onRow = onRow,
    ) { currency, day ->
        read.rates[currency]?.let { PortfolioHistoryFx.rateOn(it, day) }?.let { PortfolioDecimal.of(it) }
    }.copy(ratesFailed = read.ratesFailed)
}
