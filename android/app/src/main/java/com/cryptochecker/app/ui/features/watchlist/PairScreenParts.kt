// Teile der Paarseite ([PairScreen]): Menü und Inhalt. Eigene Datei, weil PairScreen.kt sonst zu lang würde.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.live.LiveQuote
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.features.alarms.AlarmEditorHostState
import com.cryptochecker.app.ui.features.alarms.AlarmsViewModel
import com.cryptochecker.app.ui.features.alarms.PairAlarmLimits
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.widget.WidgetKind
import com.cryptochecker.app.widget.WidgetPinner
import com.cryptochecker.resources.R

/** Menü ⋮ der Paarseite ([PairScreen]); alles braucht einen gespeicherten Eintrag. */
@Composable
internal fun PairTopBarActions(
    stored: WatchEntity?,
    off: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onDialogsChange: ((PairDialogs) -> PairDialogs) -> Unit,
    onPortfolioFor: (WatchEntity) -> Unit,
    viewModel: WatchlistViewModel,
    lockViewModel: PortfolioLockViewModel,
    pageEvents: WatchUiEventsHolder,
    originEvents: WatchUiEventsHolder,
    banner: WatchlistBanner,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val unlockReason = stringResource(R.string.portfolio_lock_reason)
    // Seltenes im Menü ⋮ (alles braucht einen gespeicherten Eintrag)
    if (stored != null) {
        IconButton(onClick = { onMenuOpenChange(true) }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        PairOverflowMenu(
            expanded = menuOpen,
            onDismiss = { onMenuOpenChange(false) },
            watch = stored,
            off = off,
            actions = PairMenuActions(
                onEditGroup = { onDialogsChange { it.copy(group = true) } },
                onEditNote = { onDialogsChange { it.copy(note = true) } },
                // Auch mit ausgeschaltetem Portfolio: das erste Speichern schaltet es ein
                onAddToPortfolio = if (PortfolioOff.offerAddToPortfolio(preview = false)) {
                    {
                        lockViewModel.requireUnlock(context.findFragmentActivity(), unlockReason) {
                            onPortfolioFor(stored)
                        }
                    }
                } else {
                    null
                },
                onRefresh = { viewModel.refreshOneByUser(stored) },
                onAddWidget = {
                    if (!WidgetPinner.request(context, WidgetKind.SINGLE, stored.id)) {
                        onDialogsChange { it.copy(widget = true) }
                    }
                },
                onTtsChange = { on ->
                    // Sprachausgabe war aus bzw. «Nur Alarme»: jetzt an — kurz sagen, wo man es ändert
                    if (viewModel.setTtsEnabled(
                            stored,
                            on
                        )
                    ) {
                        banner.show(resources.getString(R.string.watchlist_tts_speech_on))
                    }
                },
                onToggleDisabled = { pageEvents.toggleDisabled(viewModel, stored) },
                // Wie nach links wischen: sofort, «Rückgängig» im Banner dort, woher die Seite kam
                onDelete = { originEvents.deleteWithUndo(viewModel, stored) },
            ),
        )
    }
}

/** Inhalt der Paarseite ([PairScreen]) mit Live-Kurs und dem Blatt «Was bewegt sich mit?». */
@Composable
internal fun PairScaffoldContent(
    padding: PaddingValues,
    shown: WatchEntity?,
    isPreview: Boolean,
    off: Boolean,
    state: WatchlistUiState,
    now: Long,
    livePrices: State<Map<Long, LiveQuote>>,
    viewModel: WatchlistViewModel,
    alarms: AlarmsViewModel,
    alarmHost: AlarmEditorHostState,
    limits: PairAlarmLimits,
    addState: PairAddState,
    preview: PairPreview?,
    pageEvents: WatchUiEventsHolder,
    whyOpen: Boolean,
    onDialogsChange: ((PairDialogs) -> PairDialogs) -> Unit,
) {
    if (shown == null) return
    val signals = if (isPreview) {
        emptyList()
    } else {
        ActivityAnalyzer.applySensitivity(
            state.content.activity[shown.id]?.active(now).orEmpty(),
            state.display.activitySensitivity,
        )
    }
    WithLiveQuote(
        shown,
        livePrices,
        rollingBasis = !isPreview && !state.display.changeBasis.isDay,
        // Am Live-Strom: Frische am letzten Takt (wie Zeile und Status-Pille)
        onLiveSocket = !isPreview && shown.id in state.live.liveWatchIds,
        now = now,
    ) { live ->
        PairPageContent(
            // Börse stillgelegt (RetiredWatch: Marker oder Ende erreicht): «—» mit Grund statt des letzten Kurses
            watch = com.cryptochecker.app.domain.watch.RetiredWatch.shown(if (isPreview) shown else live, now),
            isPreview = isPreview,
            off = off,
            now = now,
            // Am Live-Strom: 2 Min. wie Zeile und Status-Pille
            outdatedAfter = if (isPreview) {
                0L
            } else {
                com.cryptochecker.app.domain.watch.LiveStatus.staleAfterMillis(
                    shown.id in state.live.liveWatchIds,
                    state.display.staleAfterMillis,
                )
            },
            viewModel = viewModel,
            chartLine = state.display.sheetChartLine,
            alarms = alarms,
            alarmHost = alarmHost,
            limits = limits,
            hasActivity = !shown.isNotTraded && !shown.disabled && signals.isNotEmpty() &&
                com.cryptochecker.app.domain.watch.RetiredWatch.exchangeOf(shown, now) == null,
            adding = addState.addedId != null,
            callbacks = PairPageCallbacks(
                onRetry = { if (isPreview) preview?.reload?.invoke() else viewModel.refreshOneByUser(shown) },
                onToggleDisabled = { pageEvents.toggleDisabled(viewModel, shown) },
                onAdd = { addState.add(viewModel, pageEvents, preview?.watch) },
                onToggleFavorite = {
                    viewModel.toggleFavorite(shown) { if (it) pageEvents.send(WatchUiEvent.Haptic(HapticEvent.LIGHT_IMPACT)) }
                },
                onWhy = { onDialogsChange { it.copy(why = true) } },
                onChangeExchange = { onDialogsChange { it.copy(editPair = true) } },
                onNotificationChange = { on ->
                    // Erlaubnis erst nach dem Schreiben (nicht bei «Abbrechen» bzw. gesperrt)
                    viewModel.setNotificationEnabled(shown, on) { written ->
                        if (written && on) pageEvents.send(WatchUiEvent.RequestNotifications)
                    }
                },
                onEditNote = { onDialogsChange { it.copy(note = true) } },
            ),
            padding = padding,
        )
    }
    if (whyOpen && !shown.isNotTraded && !off &&
        com.cryptochecker.app.domain.watch.RetiredWatch.exchangeOf(shown, now) == null
    ) {
        WhySheet(
            watch = shown,
            signals = signals,
            load = viewModel::explain,
            onDismiss = { onDialogsChange { it.copy(why = false) } },
            // Bei rollender Basis die Änderung selbst; bei «seit 00:00» rechnet es eigene Kerzen
            candleSource = if (isPreview) {
                null
            } else {
                viewModel.foreignCandleSource(shown).takeIf {
                    !state.display.changeBasis.isDay
                }
            },
        )
    }
}
