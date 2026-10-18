@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.lazy.rememberLazyListState
import com.cryptochecker.app.ui.navigation.ScreenRoute
import com.cryptochecker.app.ui.navigation.OnTabReselect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioPriceState
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.notification.PortfolioAlarmTexts
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.AppFab
import com.cryptochecker.app.ui.components.AppMenuHead
import com.cryptochecker.app.ui.components.ListSegment
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.components.TabTitle
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.watchlist.SwipeActionsRow
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label

/**
 * Portfolio-Tab: Gesamtwert, Coins nach Wert, Erfassen per «+». Bedienung wie in der
 * Merkliste: nach unten ziehen oder ⋯ › «Aktualisieren» lädt die Kurse neu; einen Coin nach
 * links wischen löscht alle seine Transaktionen (Banner mit «Rückgängig»); ⋯ › «Portfolio
 * leeren» löscht nach Rückfrage alles.
 */
@Composable
fun PortfolioScreen(
    onOpenCoin: (String) -> Unit,
    onOpenAbout: () -> Unit = {},
    viewModel: PortfolioViewModel = hiltViewModel(),
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val watchedAddresses by viewModel.watchedAddresses.collectAsStateWithLifecycle()
    // Stablecoins mit gebrückten Varianten (USDC.e …): Zeile mit «≈» ([AddressBalance.bridgedStables])
    val bridgedStables = remember(watchedAddresses) {
        com.cryptochecker.app.domain.portfolio.AddressBalance.bridgedStables(watchedAddresses)
    }
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val currency by viewModel.currency.collectAsStateWithLifecycle()
    // Faktor USDT → Anzeigewährung: Devisenkurs × USDT/USD (USDT zum Marktkurs)
    val fxRate by viewModel.displayFxRate.collectAsStateWithLifecycle()
    val todayPercent by viewModel.todayPercent.collectAsStateWithLifecycle()
    val todayAmount by viewModel.todayAmount.collectAsStateWithLifecycle()
    val todaySkipped by viewModel.todaySkipped.collectAsStateWithLifecycle()
    val weekChange by viewModel.weekChange.collectAsStateWithLifecycle()
    val yearChange by viewModel.yearChange.collectAsStateWithLifecycle()
    val periodScaled by viewModel.periodScaled.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val historyRange by viewModel.historyRange.collectAsStateWithLifecycle()
    val historyExpanded by viewModel.historyExpanded.collectAsStateWithLifecycle()
    val historyView by viewModel.historyView.collectAsStateWithLifecycle()
    val historyCandles by viewModel.historyCandles.collectAsStateWithLifecycle()
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()
    val coinPnl by viewModel.coinPnl.collectAsStateWithLifecycle()
    val historyPnl by viewModel.historyPnl.collectAsStateWithLifecycle()
    val changeBasis by viewModel.changeBasis.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val staleAfter by viewModel.staleAfterMillis.collectAsStateWithLifecycle()
    val depegReading by viewModel.depegReading.collectAsStateWithLifecycle()
    val depegThreshold by viewModel.depegThresholdTenths.collectAsStateWithLifecycle()
    // Uhr für «vor 2 Min.» und den Wechsel nach «veraltet» (alle 30 s, wie der Markt-Tab)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    LaunchedEffect(Unit) { viewModel.start() }
    // Beim Öffnen des Tabs Kurse auffrischen (60 s Zwischenspeicher)
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(force = false)
        onPauseOrDispose { }
    }

    var sheet by remember { mutableStateOf<TxDraft?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var pickCurrency by remember { mutableStateOf(false) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    // Saveable: Der Dialog muss die Dateiauswahl (eigene Activity) überstehen
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    // Neuer Alarm «Portfolio-Wert»
    var alarmOpen by rememberSaveable { mutableStateOf(false) }
    // «Portfolio leeren» — Rückfrage
    var askClear by remember { mutableStateOf(false) }
    // ⋯ › «Adressen» (beobachtete öffentliche Adressen)
    var addressesOpen by rememberSaveable { mutableStateOf(false) }
    // ⋯ › «CSV importieren» (Relai, Swissquote)
    var csvImportOpen by rememberSaveable { mutableStateOf(false) }
    // ⋯ › «Importe»: Importläufe mit «Rückgängig»
    var importsOpen by rememberSaveable { mutableStateOf(false) }
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val banner = rememberWatchlistBanner(scope)
    val reduceMotion = rememberReduceMotion()
    val listState = rememberLazyListState()
    // Erneutes Tippen auf den Tab «Portfolio»: an den Anfang der Liste (wie iOS)
    OnTabReselect(ScreenRoute.Portfolio) {
        if (reduceMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0)
    }
    // Adresse mit später aufgetauchter Überschneidung: einmal automatisch «Nur anzeigen» — sichtbar, mit «Rückgängig»
    val overlapNotice by viewModel.addressOverlapNotice.collectAsStateWithLifecycle()
    // Bei offenem Adressen-Blatt erst danach (das Banner läge hinter dem Blatt)
    LaunchedEffect(overlapNotice, addressesOpen) {
        val notice = overlapNotice?.takeIf { !addressesOpen } ?: return@LaunchedEffect
        viewModel.consumeAddressOverlapNotice()
        val text = resources.getString(
            R.string.portfolio_address_overlap_auto, notice.names.joinToString(", "), notice.coins.joinToString(", "),
        )
        banner.show(text) { viewModel.undoAddressOverlap(notice) }
    }
    // Einmal: früher automatisch «Nur anzeigen» ohne passende Menge zählt wieder — sichtbar, mit «Rückgängig»
    val restoredNotice by viewModel.addressOverlapRestored.collectAsStateWithLifecycle()
    LaunchedEffect(restoredNotice, addressesOpen) {
        val notice = restoredNotice?.takeIf { !addressesOpen } ?: return@LaunchedEffect
        viewModel.consumeAddressOverlapRestored()
        val text = resources.getString(R.string.portfolio_address_overlap_restored, notice.names.joinToString(", "))
        banner.show(text) { viewModel.undoAddressOverlapRestored(notice) }
    }

    // Ungültige Einträge aus der Systemsicherung einmal nennen
    SystemRestoreNoticeDialog()
    sheet?.let { draft ->
        PortfolioTxSheet(initial = draft, viewModel = viewModel, onDismiss = { sheet = null })
    }
    if (addressesOpen) {
        PortfolioAddressSheet(
            viewModel = viewModel,
            // Erste Adresse mit ausgeschaltetem Portfolio schaltet es ein (wie die erste Transaktion)
            onPortfolioEnabled = { banner.show(resources.getString(R.string.portfolio_enabled_banner)) },
            onDismiss = { addressesOpen = false }
        )
    }
    if (csvImportOpen) {
        PortfolioCsvImportFlow(
            onDismiss = { csvImportOpen = false },
            onImported = { count ->
                banner.show(resources.getQuantityString(R.plurals.portfolio_csv_import_done, count, count))
            },
            // Erste Transaktion mit ausgeschaltetem Portfolio schaltet es ein (wie von Hand)
            onPortfolioEnabled = { banner.show(resources.getString(R.string.portfolio_enabled_banner)) },
        )
    }
    if (importsOpen) {
        PortfolioImportsDialog(
            batches = PortfolioImportBatch.summarize(transactions.orEmpty().map { it.importBatch }),
            onUndo = { batch ->
                viewModel.undoImport(batch.id) { removed ->
                    banner.show(resources.getQuantityString(R.plurals.portfolio_import_undone, removed.size, removed.size)) {
                        viewModel.restore(removed)
                    }
                }
            },
            onDismiss = { importsOpen = false },
        )
    }
    if (pickCurrency) {
        CurrencyDialog(
            selected = currency,
            onSelect = { viewModel.setCurrency(it); pickCurrency = false },
            onDismiss = { pickCurrency = false }
        )
    }

    if (exportOpen) {
        PortfolioExportDialog(currency = currency, onDismiss = { exportOpen = false })
    }
    if (alarmOpen) {
        PortfolioAlarmDialog(
            currency = currency,
            basis = changeBasis,
            onSave = { kind, threshold, repeating ->
                viewModel.addAlarm(kind, threshold, repeating) { alarm, allowed ->
                    // «Alarm gespeichert: Portfolio über 50’000.00 CHF · einmalig» (· Mitteilungen aus)
                    val off = !allowed
                    val condition = PortfolioAlarmTexts.sentence(context, alarm, changeBasis, hideAmounts)
                    val text = com.cryptochecker.app.ui.features.alarms.savedText(context, condition, alarm.repeating, off)
                    if (off) {
                        banner.showAction(text, resources.getString(R.string.settings_title), SAVED_ALARM_MILLIS) {
                            com.cryptochecker.app.ui.components.openAppNotificationSettings(context)
                        }
                    } else {
                        banner.show(text)
                    }
                }
                alarmOpen = false
            },
            onDismiss = { alarmOpen = false }
        )
    }

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text(stringResource(R.string.portfolio_clear)) },
            // Anzahl nennen; beobachtete Adressen bleiben (nur Transaktionen werden gelöscht)
            text = {
                val count = transactions?.size ?: 0
                Text(pluralStringResource(R.plurals.portfolio_clear_confirm_count, count, count))
            },
            confirmButton = {
                TextButton(onClick = { askClear = false; viewModel.clearAll() }) {
                    Text(stringResource(R.string.portfolio_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { askClear = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    val hasTransactions = !transactions.isNullOrEmpty()
    // Inhalt auch nur mit Adressen (Bestand ohne Kaufpreis)
    val hasData = hasTransactions || watchedAddresses.isNotEmpty()
    // Anzahl Transaktionen je Coin (kleine Zahl neben der Menge)
    // Tausch zählt bei beiden Coins
    val txCounts = remember(transactions) {
        transactions.orEmpty().flatMap { tx -> tx.coins().map { PortfolioCalculator.normalizeCoin(it) }.distinct() }
            .groupingBy { it }.eachCount()
    }
    // CSV-Importläufe für ⋯ › «Importe» (neueste zuerst)
    val importBatches = remember(transactions) { PortfolioImportBatch.summarize(transactions.orEmpty().map { it.importBatch }) }
    // FIFO je Wallet: Abgänge über den Bestand ihrer Wallet (Hinweis unter der Gesamtkarte)
    val costMethod by viewModel.costMethod.collectAsStateWithLifecycle()
    val calcTransactions by viewModel.calcTransactions.collectAsStateWithLifecycle()
    val walletShortfalls = remember(calcTransactions, costMethod) {
        PortfolioCalculator.walletShortfalls(calcTransactions.orEmpty().map { it.toTrade() }, costMethod)
    }

    // Wischen auf einem Coin: alle Transaktionen weg, «Rückgängig» im Banner
    fun deleteCoin(coin: String) {
        viewModel.deleteCoin(coin) { removed ->
            banner.show(resources.getString(R.string.watchlist_removed, coin)) { viewModel.restore(removed) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TabTitle(stringResource(R.string.portfolio_title), R.drawable.ic_portfolio) },
                actions = {
                    // «Beträge verbergen»: alle Beträge als «•••» (Prozente bleiben), auch im Widget
                    if (hasData) {
                        IconButton(onClick = { viewModel.setHideAmounts(!hideAmounts) }) {
                            Icon(
                                painterResource(if (hideAmounts) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                contentDescription = stringResource(
                                    if (hideAmounts) R.string.a11y_portfolio_show_amounts else R.string.portfolio_hide_amounts
                                )
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(UiTestTags.PORTFOLIO_MENU)) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.action_more)
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Gleicher Anfang wie Merkliste und Markt: App (→ «Über»), Aktualisieren
                            AppMenuHead(
                                refreshing = refreshing,
                                onClose = { menuOpen = false },
                                onOpenAbout = onOpenAbout,
                                onRefresh = { viewModel.refresh(force = true) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.portfolio_convert_to_value, currency)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_swap_horiz), null) },
                                onClick = { menuOpen = false; pickCurrency = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.portfolio_addresses_title)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_portfolio), null) },
                                onClick = { menuOpen = false; addressesOpen = true }
                            )
                            // Import = in die App hinein (Pfeil nach unten); darunter die unterstützten Quellen
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.portfolio_csv_import_action))
                                        Text(
                                            CSV_IMPORT_SOURCES,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_download), null) },
                                onClick = { menuOpen = false; csvImportOpen = true }
                            )
                            // Importläufe (nur mit importierten Transaktionen): «Rückgängig» je Lauf
                            if (importBatches.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.portfolio_imports_title)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_list), null) },
                                    onClick = { menuOpen = false; importsOpen = true },
                                    modifier = Modifier.testTag(UiTestTags.PORTFOLIO_IMPORTS)
                                )
                            }
                            if (hasTransactions) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.portfolio_export_action)) },
                                    // Export = aus der App hinaus (Pfeil nach oben)
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_upload), null) },
                                    onClick = { menuOpen = false; exportOpen = true }
                                )
                            }
                            if (hasData) {
                                // Alarm «Portfolio-Wert» (erscheint in der Alarm-Übersicht)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.portfolio_alarm_action)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_notifications_active), null) },
                                    onClick = { menuOpen = false; alarmOpen = true }
                                )
                                HorizontalDivider()
                                // Wie «Merkliste leeren»: zuunterst, rot, mit Rückfrage
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.portfolio_clear), color = MaterialTheme.colorScheme.error)
                                    },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = { menuOpen = false; askClear = true }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(banner.host) },
        floatingActionButton = {
            if (hasData) {
                AppFab(onClick = { sheet = TxDraft() }, contentDescription = stringResource(R.string.portfolio_add_tx))
            }
        }
    ) { padding ->
        val current = summary
        if (transactions == null || current == null) {
            SkeletonList(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        if (!hasData) {
            EmptyPortfolio(
                modifier = Modifier.fillMaxSize().padding(padding),
                currency = currency,
                onAdd = { sheet = TxDraft() },
                onAddAddress = { addressesOpen = true },
                onImportCsv = { csvImportOpen = true },
            )
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh(force = true) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
            ReadableInset { inset ->
                CompositionLocalProvider(LocalHidePortfolioAmounts provides hideAmounts) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Unten Platz für den «+»-Knopf
                        contentPadding = PaddingValues(start = 16.dp + inset, top = 4.dp, end = 16.dp + inset, bottom = 96.dp),
                        // Positionen ohne Fuge als eine Karte ([ListSegment]); die Karten darüber
                        // bringen den Abstand selbst mit
                        verticalArrangement = Arrangement.spacedBy(ListSegment.Gap)
                    ) {
                        // Zustand der Kurse wie in der Merkliste: aktuell / veraltet / offline mit «Stand …»
                        val priceState = PortfolioPriceFallback.state(
                            updatedAt = prices.updatedAt,
                            now = now,
                            staleAfterMillis = staleAfter,
                            online = online,
                            hasOpenCoins = current.open.isNotEmpty(),
                        )
                        val stale = priceState == PortfolioPriceState.STALE ||
                            (priceState == PortfolioPriceState.OFFLINE &&
                                PortfolioPriceFallback.isStale(prices.updatedAt, now, staleAfter))
                        if (priceState != PortfolioPriceState.NONE) {
                            item(key = "status") {
                                PortfolioStatusPill(
                                    state = priceState,
                                    updatedAt = prices.updatedAt,
                                    now = now,
                                    reduceMotion = reduceMotion,
                                    modifier = Modifier.padding(bottom = Spacing.sm)
                                )
                            }
                        }
                        item(key = "total") {
                            Box(Modifier.padding(bottom = ListSegment.Spacing)) { TotalCard(
                                summary = current,
                                updatedAt = prices.updatedAt,
                                currency = currency,
                                fxRate = fxRate,
                                todayPercent = todayPercent,
                                todayAmount = todayAmount,
                                todaySkipped = todaySkipped,
                                weekChange = weekChange,
                                yearChange = yearChange,
                                stale = stale,
                                priceSources = prices.sources,
                                exchangeFailed = prices.exchangeFailed,
                                mixedExchange = prices.mixedExchange,
                                periodScaled = periodScaled,
                                walletShortfalls = walletShortfalls,
                                onShortfall = { id -> transactions?.firstOrNull { it.id == id }?.let { sheet = it.toDraft() } },
                                // «USDT = 1 angenommen»: Messung 6 Std. gültig, Uhr alle 30 s ([DepegAlarm.cardNote] in der Karte)
                                depegReading = depegReading,
                                depegThresholdTenths = depegThreshold,
                                now = now,
                            ) }
                        }
                        // Wertverlauf über den Positionen
                        item(key = "history") {
                            Box(Modifier.padding(bottom = ListSegment.Spacing)) {
                                PortfolioHistoryCard(
                                    history = history,
                                    range = historyRange,
                                    onRange = viewModel::setHistoryRange,
                                    expanded = historyExpanded,
                                    onExpandedChange = viewModel::setHistoryExpanded,
                                    view = historyView,
                                    onView = viewModel::setHistoryView,
                                    candles = historyCandles,
                                    onCandlesChange = viewModel::setHistoryCandles,
                                    pnl = historyPnl,
                                    onPnlChange = viewModel::setHistoryPnl
                                )
                            }
                        }
                        // Regel der Adress-Guthaben für Heute/7 T/1 J und Verlauf offen nennen (ab der ersten Beobachtung)
                        if (watchedAddresses.any { !it.lastBalance.isNullOrEmpty() }) {
                            item(key = "address_hint") {
                                Box(Modifier.padding(start = 4.dp, end = 4.dp, bottom = ListSegment.Spacing)) {
                                    Text(
                                        stringResource(R.string.portfolio_address_period_observed),
                                        style = MaterialTheme.typography.label,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        // Aufteilung (vier grösste Coins + «Andere»), erst ab zwei Teilen
                        val slices = PortfolioInsights.allocation(current.open)
                        if (slices.size >= 2) {
                            item(key = "allocation") { Box(Modifier.padding(bottom = ListSegment.Spacing)) { AllocationCard(slices) } }
                        }
                        // Nach links wischen = Coin löschen (Rückgängig im Banner); kein Favorit
                        itemsIndexed(current.open, key = { _, it -> "open:${it.coin}" }) { index, position ->
                            val shape = ListSegment.shape(index, current.open.size)
                            // Coin nur aus einer Adresse: nichts zu löschen (Adresse über ⋯ › «Adressen» entfernen)
                            val ownTx = (txCounts[position.coin] ?: 0) > 0
                            SwipeActionsRow(
                                enabled = ownTx,
                                favorite = false,
                                onDelete = { deleteCoin(position.coin) },
                                onToggleFavorite = null,
                                reduceMotion = reduceMotion,
                                shape = shape,
                                modifier = Modifier.animateItem()
                            ) {
                                CoinRow(
                                    shape = shape,
                                    position = position,
                                    onClick = { onOpenCoin(position.coin) },
                                    pnl = coinPnl[position.coin] ?: coinPnl[position.coin.uppercase()],
                                    basis = changeBasis,
                                    sharePercent = position.value?.let { PortfolioWidgetMath.sharePercent(it, current.totalValue) },
                                    onDelete = { deleteCoin(position.coin) },
                                    txCount = txCounts[position.coin] ?: 0,
                                    stale = stale,
                                    unchecked = position.coin in prices.unchecked,
                                    unavailable = position.coin in prices.unavailable,
                                    // Wert und 24h wie im Widget in der Anzeigewährung (Detail bleibt USDT)
                                    unit = cardUnit(currency, fxRate),
                                    // Build 46: Börse des Kurses klein in der Zeile (nicht nur unter «Hinweise»)
                                    exchange = prices.sources[position.coin],
                                    viaBase = prices.viaBase[position.coin],
                                    bridgedStable = bridgedStables[position.coin]?.joinToString(", "),
                                )
                            }
                        }
                        if (current.closed.isNotEmpty()) {
                            item(key = "closed_header") {
                                // Echtes Chevron statt «▸»/«▾»; Zustand für den Screenreader (wie iOS)
                                val stateText = stringResource(if (showClosed) R.string.a11y_expanded else R.string.a11y_collapsed)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(role = Role.Button) { showClosed = !showClosed }
                                        .semantics(mergeDescendants = true) { stateDescription = stateText }
                                        .padding(horizontal = 4.dp, vertical = Spacing.md)
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_chevron_right),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .rotate(if (showClosed) 90f else 0f)
                                    )
                                    Text(
                                        text = pluralStringResource(R.plurals.portfolio_closed, current.closed.size, current.closed.size),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                            if (showClosed) {
                                items(current.closed, key = { "closed:${it.coin}" }) { position ->
                                    ClosedRow(position = position, onClick = { onOpenCoin(position.coin) })
                                }
                            }
                        }
                        item(key = "disclaimer") { PortfolioDisclaimer() }
                    }
                }
            }
        }
    }
}

/** Zielwährung der Umrechnungszeile wählen. */
@Composable
private fun CurrencyDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portfolio_convert_to)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(FxRateSource.CURRENCIES, key = { it }) { code ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(code) }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(selected = code == selected, onClick = { onSelect(code) })
                        Text(code, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/** So lange steht «Alarm gespeichert … · Mitteilungen aus» mit «Einstellungen». */
private const val SAVED_ALARM_MILLIS = 8_000L
