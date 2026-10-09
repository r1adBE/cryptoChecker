package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivitySignal
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.GroupNameDialog
import com.cryptochecker.app.ui.components.canonicalGroupName
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import com.cryptochecker.app.ui.features.portfolio.PortfolioQuickAddSheet
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel

/**
 * Alles, was sich über die Merkliste legt: Bericht, Rückfragen, das Aktionsblatt eines Paars,
 * «Ins Portfolio», Gruppen anlegen und bearbeiten, «Warum bewegt sich das?». Was offen ist,
 * steht in [ui].
 */
@Composable
internal fun WatchlistSheets(
    ui: WatchlistUiState,
    viewModel: WatchlistViewModel,
    lockViewModel: PortfolioLockViewModel,
    watches: List<WatchEntity>,
    groups: List<String>,
    notTradedCount: Int,
    alarmCounts: Map<Long, Int>,
    livePrices: State<Map<Long, LiveQuote>>,
    liveExchanges: List<String>,
    rollingBasis: Boolean,
    activeSignals: Map<Long, List<ActivitySignal>>,
    now: Long,
    banner: WatchlistBanner,
    onOpenAlarms: (Long) -> Unit,
    /** Löschen mit «Rückgängig» im Banner (wie nach links wischen). */
    onDelete: (WatchEntity) -> Unit,
) {
    val context = LocalContext.current
    val lastReport by viewModel.lastRefreshReport.collectAsStateWithLifecycle()
    val appStartMillis by viewModel.appStartMillis.collectAsStateWithLifecycle()
    val portfolioEnabled by viewModel.portfolioEnabled.collectAsStateWithLifecycle()
    val sheetChartLine by viewModel.sheetChartLine.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()

    // Portfolio-Sperre: Das Erfassen-Blatt zeigt Bestände — erst nach dem Entsperren,
    // und wird es unterwegs wieder gesperrt (Hintergrund-Limit), schliesst es sich.
    val portfolioAccess by lockViewModel.access.collectAsStateWithLifecycle()
    val unlockReason = stringResource(R.string.portfolio_lock_reason)
    LaunchedEffect(portfolioAccess) {
        if (portfolioAccess != PortfolioAccess.OPEN) ui.portfolioFor = null
    }

    if (ui.showReport) {
        RefreshReportSheet(
            report = lastReport,
            now = now,
            appStartMillis = appStartMillis,
            liveExchanges = liveExchanges,
            onDismiss = { ui.showReport = false },
        )
    }

    // Nicht mehr gehandelte Paare (ganze Merkliste, nicht nur die Gruppe) samt Alarmen
    // entfernen; danach Banner mit «Rückgängig» (holt alle zurück)
    if (ui.askRemoveNotTraded && notTradedCount > 0) {
        ConfirmDialog(
            title = stringResource(R.string.watchlist_remove_not_traded_title),
            text = pluralStringResource(R.plurals.watchlist_remove_not_traded_confirm, notTradedCount, notTradedCount),
            confirm = stringResource(R.string.watchlist_remove_not_traded_action),
            onConfirm = {
                ui.askRemoveNotTraded = false
                viewModel.deleteNotTradedWithUndo { count ->
                    if (count > 0) {
                        banner.show(
                            context.resources.getQuantityString(R.plurals.watchlist_removed_not_traded, count, count)
                        ) { viewModel.undoDelete(NOT_TRADED_UNDO_KEY) }
                    }
                }
            },
            onDismiss = { ui.askRemoveNotTraded = false }
        )
    }

    if (ui.askClearAll) {
        ConfirmDialog(
            title = stringResource(R.string.watchlist_clear),
            text = stringResource(R.string.watchlist_clear_confirm),
            confirm = stringResource(R.string.watchlist_clear),
            onConfirm = { viewModel.deleteAll(); ui.askClearAll = false },
            onDismiss = { ui.askClearAll = false }
        )
    }

    // Aktionen eines Paars als Blatt von unten
    ui.actionsFor?.let { id -> watches.firstOrNull { it.id == id } }?.let { stored ->
        WithLiveQuote(stored, livePrices, rollingBasis = rollingBasis) { watch ->
            WatchActionsSheet(
                watch = watch,
                alarmCount = alarmCounts[watch.id] ?: 0,
                onDismiss = { ui.actionsFor = null },
                onNotificationChange = {
                    if (it) requestNotifications()
                    viewModel.setNotificationEnabled(watch, it)
                },
                onTtsChange = { viewModel.setTtsEnabled(watch, it) },
                onToggleFavorite = { viewModel.toggleFavorite(watch) },
                // Zurück aus «Alarme» öffnet dieses Blatt wieder
                onOpenAlarms = { ui.returnToActions = watch.id; ui.actionsFor = null; onOpenAlarms(watch.id) },
                onRefresh = { viewModel.refreshOne(watch.id) },
                // Wie nach links wischen: sofort löschen, «Rückgängig» im Banner (keine Rückfrage)
                onDelete = { ui.actionsFor = null; onDelete(watch) },
                groups = groups,
                onAddToPortfolio = if (portfolioEnabled) {
                    {
                        ui.actionsFor = null
                        lockViewModel.requireUnlock(context.findFragmentActivity(), unlockReason) {
                            ui.portfolioFor = watch
                        }
                    }
                } else null,
                onSetGroup = { viewModel.setGroup(watch, it) },
                onSetNote = { viewModel.setNote(watch, it) },
                loadFutures = viewModel::fetchFutures,
                onWhy = { ui.returnToActions = watch.id; ui.actionsFor = null; ui.whyFor = watch.id },
                chartLine = sheetChartLine,
                onChartLineChange = viewModel::setSheetChartLine,
                cachedChart = viewModel::cachedSheetChart,
                loadChart = viewModel::loadSheetChart,
                satsRate = viewModel::satsRate,
            )
        }
    }

    // Kurs nur vorbelegen, wenn die Quote praktisch USDT ist
    ui.portfolioFor?.let { watch ->
        PortfolioQuickAddSheet(
            coin = watch.baseAsset,
            priceUsdt = watch.lastPrice?.takeIf {
                it > 0.0 && watch.quoteAsset.uppercase() in USD_LIKE_QUOTES
            },
            onDismiss = { ui.portfolioFor = null }
        )
    }

    if (ui.askNewGroup) {
        GroupNameDialog(
            title = stringResource(R.string.group_add),
            initial = "",
            confirmText = stringResource(R.string.group_next),
            onConfirm = { entered -> ui.newGroupNamed(canonicalGroupName(entered, groups), groups) },
            onDismiss = { ui.askNewGroup = false }
        )
    }

    ui.editGroup?.let { name ->
        GroupEditSheet(
            groupName = name,
            isNew = ui.editGroupIsNew,
            watches = watches,
            groups = groups,
            onDone = { newName, ids ->
                viewModel.saveGroup(if (ui.editGroupIsNew) null else name, newName, ids)
                ui.editGroup = null
            },
            onDelete = {
                viewModel.deleteGroup(name)
                ui.editGroup = null
            },
            onDismiss = { ui.editGroup = null }
        )
    }

    // Nicht mehr gehandelt: kein «Warum?» (auch nicht aus einem gemerkten Zustand)
    ui.whyFor?.let { id -> watches.firstOrNull { it.id == id && !it.isNotTraded } }?.let { watch ->
        WhySheet(
            watch = watch,
            signals = activeSignals[watch.id].orEmpty(),
            load = viewModel::explain,
            onDismiss = { ui.whyFor = null; ui.reopenActions() }
        )
    }
}

/** Rückfrage mit roter Bestätigung (Löschen). */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
