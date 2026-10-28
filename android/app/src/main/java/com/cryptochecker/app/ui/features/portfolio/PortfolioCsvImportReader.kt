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
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
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
)

/** Datei lesen, zerlegen und Kurse laden für [PortfolioCsvImportViewModel]. */
internal class PortfolioCsvImportReader(
    private val context: Context,
    private val repository: PortfolioRepository,
    private val historic: HistoricPriceSource,
    private val historySource: PortfolioHistorySource,
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
        for ((currency, days) in CsvImportMapping.fxNeeds(result.rows, result.fxZone)) {
            val from = LocalDate.ofEpochDay(days.first - PortfolioHistoryFx.LEAD_DAYS)
            val series = runCatchingCancellable { historic.usdToSeries(currency, from, LocalDate.ofEpochDay(days.last)) }.getOrNull()
            if (series != null) rates[currency] = series.entries.map { it.key to it.value }.sortedBy { it.first }
        }
        val keys = repository.importKeys()
        val days = CsvImportRateDays.of(result.rows, result.fxZone)
        return CsvImportInput(text, result, keys, rates, usdtUsdCloses(result), choice, days)
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
        return CsvImportInput(read.text, result, read.keys, read.rates, read.usdtUsd, choice, read.rateDays)
    }

    private suspend fun parse(text: String, choice: CsvReadChoice): CsvImportResult = withContext(Dispatchers.Default) {
        CsvImportParser.parse(text, ZoneId.systemDefault().id, choice.zone, choice.dateOrder, choice.decimalMark)
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
    CsvImportMapping.preview(read.result, read.keys, usdtUsd = read.usdtUsd, onRow = onRow) { currency, day ->
        read.rates[currency]?.let { PortfolioHistoryFx.rateOn(it, day) }?.let { PortfolioDecimal.of(it) }
    }
}
