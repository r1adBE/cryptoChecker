@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.ui.components.rememberFabScroll
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.navigation.OnTabReselect
import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.theme.rememberReduceMotion

/**
 * Portfolio-Tab: Gesamtwert, Coins nach Wert, Erfassen per «+». Bedienung wie in der
 * Merkliste: nach unten ziehen oder ⋯ › «Aktualisieren» lädt die Kurse neu; einen Coin nach
 * links wischen löscht nach Rückfrage alle seine Transaktionen (Banner mit «Rückgängig»); ⋯ › «Portfolio
 * leeren» löscht nach Rückfrage alles.
 */
@Composable
fun PortfolioScreen(
    onOpenCoin: (String) -> Unit,
    onOpenAbout: () -> Unit = {},
    /** ⋯ › «Steuer-Arbeitsunterlage…»: eigene Seite. */
    onOpenTaxWorksheet: () -> Unit = {},
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val watchedAddresses by viewModel.watchedAddresses.collectAsStateWithLifecycle()
    val currency by viewModel.currency.collectAsStateWithLifecycle()
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()
    val changeBasis by viewModel.changeBasis.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    // Beim Öffnen des Tabs Kurse auffrischen (60 s Zwischenspeicher)
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(force = false)
        onPauseOrDispose { }
    }

    // Saveable: Drehen, Dunkelmodus und Sprachwechsel schliessen das offene Blatt nicht ([TxDraftSaver])
    var sheet by rememberSaveable(stateSaver = TxDraftSaver) { mutableStateOf<TxDraft?>(null) }
    var pickCurrency by rememberSaveable { mutableStateOf(false) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    // Saveable: Der Dialog muss die Dateiauswahl (eigene Activity) überstehen
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    // Neuer Alarm «Portfolio-Wert»
    var alarmOpen by rememberSaveable { mutableStateOf(false) }
    // «Portfolio leeren» — Rückfrage (saveable: übersteht Drehen und Sprachwechsel)
    var askClear by rememberSaveable { mutableStateOf(false) }
    // ⋯ › «Adressen» (beobachtete öffentliche Adressen)
    var addressesOpen by rememberSaveable { mutableStateOf(false) }
    // ⋯ › «CSV importieren» (Relai, Swissquote)
    var csvImportOpen by rememberSaveable { mutableStateOf(false) }
    // ⋯ › «Importe»: Importläufe mit «Rückgängig»
    var importsOpen by rememberSaveable { mutableStateOf(false) }
    // Einträge ohne Einstand (Karte, Hinweise unter der Gesamtkarte)
    var costGapsOpen by rememberSaveable { mutableStateOf(false) }
    val costGaps = rememberCostGaps(viewModel, transactions)
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val banner = rememberWatchlistBanner(scope)
    val reduceMotion = rememberReduceMotion()
    val listState = rememberLazyListState()
    // «+»-Knopf: beim Scrollen nach unten weg (verdeckt keine Beträge), nach oben wieder da
    val fabScroll = rememberFabScroll(listState)
    // Erneutes Tippen auf den Tab «Portfolio»: an den Anfang der Liste (wie iOS)
    OnTabReselect(ScreenRoute.Portfolio) {
        if (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
    }
    PortfolioBannerEffects(viewModel, banner, addressesOpen)

    PortfolioScreenDialogs(
        viewModel = viewModel,
        banner = banner,
        transactions = transactions,
        currency = currency,
        changeBasis = changeBasis,
        hideAmounts = hideAmounts,
        sheet = sheet,
        onSheetChange = { sheet = it },
        addressesOpen = addressesOpen,
        onAddressesOpenChange = { addressesOpen = it },
        csvImportOpen = csvImportOpen,
        onCsvImportOpenChange = { csvImportOpen = it },
        importsOpen = importsOpen,
        onImportsOpenChange = { importsOpen = it },
        pickCurrency = pickCurrency,
        onPickCurrencyChange = { pickCurrency = it },
        exportOpen = exportOpen,
        onExportOpenChange = { exportOpen = it },
        alarmOpen = alarmOpen,
        onAlarmOpenChange = { alarmOpen = it },
        askClear = askClear,
        onAskClearChange = { askClear = it },
    )

    if (costGapsOpen) {
        PortfolioCostGapsSheet(
            viewModel,
            transactions.orEmpty(),
            onEdit = { tx ->
                costGapsOpen = false
                sheet = tx.toDraft()
            },
            onDismiss = { costGapsOpen = false },
        )
    }

    // Anzahl Transaktionen je Coin (kleine Zahl neben der Menge)
    // Tausch zählt bei beiden Coins
    val txCounts = remember(transactions) {
        transactions.orEmpty().flatMap { tx -> tx.coins().map { PortfolioCalculator.normalizeCoin(it) }.distinct() }
            .groupingBy { it }.eachCount()
    }

    // Wischen auf einem Coin: erst Rückfrage mit der Zahl der Transaktionen, dann alle weg, «Rückgängig» im Banner
    var askDeleteCoin by rememberSaveable { mutableStateOf<String?>(null) }
    askDeleteCoin?.let { coin ->
        DeleteCoinFlow(viewModel, banner, transactions.orEmpty(), coin, txCounts) { askDeleteCoin = null }
    }

    PortfolioScaffold(
        viewModel = viewModel,
        banner = banner,
        listState = listState,
        fabScroll = fabScroll,
        reduceMotion = reduceMotion,
        transactions = transactions,
        watchedAddresses = watchedAddresses,
        currency = currency,
        hideAmounts = hideAmounts,
        changeBasis = changeBasis,
        txCounts = txCounts,
        showClosed = showClosed,
        onShowClosedChange = { showClosed = it },
        onDeleteCoin = { askDeleteCoin = it },
        onSheetChange = { sheet = it },
        onOpenCoin = onOpenCoin,
        onOpenAbout = onOpenAbout,
        onOpenTaxWorksheet = onOpenTaxWorksheet,
        onPickCurrency = { pickCurrency = true },
        onOpenAddresses = { addressesOpen = true },
        onOpenCsvImport = { csvImportOpen = true },
        onOpenImports = { importsOpen = true },
        onOpenExport = { exportOpen = true },
        onOpenAlarm = { alarmOpen = true },
        onAskClear = { askClear = true },
        costGapsPending = costGaps.pending.size,
        onOpenCostGaps = { costGapsOpen = true },
    )
}
