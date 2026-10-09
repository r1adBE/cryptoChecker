@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.ui.features.watchlist.SwipeActionsRow
import com.cryptochecker.app.ui.features.watchlist.rememberWatchlistBanner
import com.cryptochecker.app.ui.components.AppMenuHead
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.components.SkeletonList
import com.cryptochecker.app.ui.theme.Spacing

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
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val prices by viewModel.prices.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val currency by viewModel.currency.collectAsStateWithLifecycle()
    val fxRate by viewModel.fxRate.collectAsStateWithLifecycle()
    val todayPercent by viewModel.todayPercent.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val historyRange by viewModel.historyRange.collectAsStateWithLifecycle()
    val historyExpanded by viewModel.historyExpanded.collectAsStateWithLifecycle()
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()
    val coinChanges by viewModel.coinChanges.collectAsStateWithLifecycle()
    val changeBasis by viewModel.changeBasis.collectAsStateWithLifecycle()

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val banner = rememberWatchlistBanner(scope)
    val reduceMotion = rememberReduceMotion()

    sheet?.let { draft ->
        PortfolioTxSheet(initial = draft, viewModel = viewModel, onDismiss = { sheet = null })
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
                viewModel.addAlarm(kind, threshold, repeating)
                alarmOpen = false
            },
            onDismiss = { alarmOpen = false }
        )
    }

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text(stringResource(R.string.portfolio_clear)) },
            text = { Text(stringResource(R.string.portfolio_clear_confirm)) },
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
    // Anzahl Transaktionen je Coin (kleine Zahl neben der Menge)
    val txCounts = remember(transactions) {
        transactions.orEmpty().groupingBy { PortfolioCalculator.normalizeCoin(it.coin) }.eachCount()
    }

    // Wischen auf einem Coin: alle Transaktionen weg, «Rückgängig» im Banner
    fun deleteCoin(coin: String) {
        viewModel.deleteCoin(coin) { removed ->
            banner.show(context.getString(R.string.watchlist_removed, coin)) { viewModel.restore(removed) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.portfolio_title), fontWeight = FontWeight.SemiBold) },
                actions = {
                    // «Beträge verbergen»: alle Beträge als «•••» (Prozente bleiben), auch im Widget
                    if (hasTransactions) {
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
                        IconButton(onClick = { menuOpen = true }) {
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
                            if (hasTransactions) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.portfolio_export_action)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_download), null) },
                                    onClick = { menuOpen = false; exportOpen = true }
                                )
                                // Alarm «Portfolio-Wert» (erscheint in der Alarm-Übersicht)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.portfolio_alarm_action)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_notifications), null) },
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
            if (hasTransactions) {
                FloatingActionButton(onClick = { sheet = TxDraft() }) {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.portfolio_add_tx)
                    )
                }
            }
        }
    ) { padding ->
        val current = summary
        if (transactions == null || current == null) {
            SkeletonList(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        if (!hasTransactions) {
            EmptyPortfolio(
                modifier = Modifier.fillMaxSize().padding(padding),
                currency = currency,
                onAdd = { sheet = TxDraft() }
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
                        modifier = Modifier.fillMaxSize(),
                        // Unten Platz für den «+»-Knopf
                        contentPadding = PaddingValues(start = 16.dp + inset, top = 4.dp, end = 16.dp + inset, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "total") {
                            TotalCard(
                                summary = current,
                                updatedAt = prices.updatedAt,
                                currency = currency,
                                fxRate = fxRate,
                                todayPercent = todayPercent
                            )
                        }
                        // Wertverlauf über den Positionen
                        item(key = "history") {
                            PortfolioHistoryCard(
                                history = history,
                                range = historyRange,
                                onRange = viewModel::setHistoryRange,
                                expanded = historyExpanded,
                                onExpandedChange = viewModel::setHistoryExpanded
                            )
                        }
                        // Aufteilung (vier grösste Coins + «Andere»), erst ab zwei Teilen
                        val slices = PortfolioInsights.allocation(current.open)
                        if (slices.size >= 2) {
                            item(key = "allocation") { AllocationCard(slices) }
                        }
                        // Nach links wischen = Coin löschen (Rückgängig im Banner); kein Favorit
                        items(current.open, key = { "open:${it.coin}" }) { position ->
                            SwipeActionsRow(
                                enabled = true,
                                favorite = false,
                                onDelete = { deleteCoin(position.coin) },
                                onToggleFavorite = null,
                                reduceMotion = reduceMotion,
                                modifier = Modifier.animateItem()
                            ) {
                                CoinRow(
                                    position = position,
                                    onClick = { onOpenCoin(position.coin) },
                                    dayChange = coinChanges[position.coin] ?: coinChanges[position.coin.uppercase()],
                                    basis = changeBasis,
                                    onDelete = { deleteCoin(position.coin) },
                                    txCount = txCounts[position.coin] ?: 0
                                )
                            }
                        }
                        if (current.closed.isNotEmpty()) {
                            item(key = "closed_header") {
                                Text(
                                    text = (if (showClosed) "▾ " else "▸ ") +
                                        pluralStringResource(R.plurals.portfolio_closed, current.closed.size, current.closed.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showClosed = !showClosed }
                                        .padding(horizontal = 4.dp, vertical = Spacing.md)
                                )
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

@Composable
internal fun PortfolioHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
internal fun PortfolioDisclaimer() {
    Text(
        stringResource(R.string.portfolio_disclaimer),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
    )
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
