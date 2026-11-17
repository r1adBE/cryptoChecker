// CSV-Import speichern (Plan in die Datenbank) — Teil von PortfolioCsvImportViewModel.
package com.cryptochecker.app.ui.features.portfolio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.backup.BackupLimits
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource
import com.cryptochecker.app.data.portfolio.PortfolioRepository
import com.cryptochecker.app.data.portfolio.insertImportedLinked
import com.cryptochecker.app.data.settings.SettingsRepository
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
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.util.DefaultDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
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

/**
 * Anlegen und Verknüpfen ([saveCsvImport]) mit gedrosseltem Fortschritt. Abgebrochen wird nur über `saveCancelled`
 * (vor jeder Zeile, Transaktion rollt zurück): so steht nie ein Teil-Import und das Ergebnis passt immer zum
 * Gespeicherten.
 */
internal suspend fun PortfolioCsvImportViewModel.save(plan: CsvImportSavePlan): CsvImportSaveOutcome {
    val run = progressRun
    val throttle = CsvProgressThrottle(CsvImportStep.SAVE, plan.entities.size) { postProgress(it, run) }
    throttle.start()
    return withContext(NonCancellable) {
        saveCsvImport {
            repository.insertImportedLinked(
                plan.entities,
                plan.batch,
                plan.storedUpdates,
                plan.fallbacks,
                throttle::row,
                saveCancelled::get,
            )
        }
    }
}
