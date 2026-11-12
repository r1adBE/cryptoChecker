// Seite eines Paars (ersetzt das frühere Aktionsblatt): Tipp auf eine Zeile der Merkliste, auf ein Ergebnis der Suche,
// auf einen Coin aus «Heute auffällig» bzw. «Gewinner und Verlierer», eine Mitteilung oder eine Widget-Zeile.
// Hier stehen Zustand, Rückmeldungen und die Blätter und Dialoge darüber; den Inhalt setzt [PairPageContent] zusammen.
// Eigene Seite im Navigationsgraphen (`ScreenRoute.PairPage`): Zurück, Drehen und Prozess-Neustart wie jede Seite.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.portfolio.PortfolioOff
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeView
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.lock.PortfolioAccess
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.AppSnackbarHost
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.NoteDialog
import com.cryptochecker.app.ui.components.WidgetManualDialog
import com.cryptochecker.app.ui.features.alarms.AlarmEditorHost
import com.cryptochecker.app.ui.features.alarms.AlarmsViewModel
import com.cryptochecker.app.ui.features.alarms.rememberAlarmEditorHost
import com.cryptochecker.app.ui.features.alarms.rememberPairAlarmLimits
import com.cryptochecker.app.ui.features.portfolio.PortfolioQuickAddSheet
import com.cryptochecker.app.ui.lock.PortfolioLockViewModel
import com.cryptochecker.app.ui.theme.SnackbarKind
import com.cryptochecker.app.widget.WidgetKind
import com.cryptochecker.app.widget.WidgetPinner

/**
 * Vorschau eines Coins, der nicht in der Merkliste steht (Binance «COIN/USDT», nur im Speicher): [watch] mit Kurs
 * (null = noch keiner), [reload] für «Erneut versuchen», [openStored] zeigt nach dem Hinzufügen die Seite des neuen
 * Eintrags (`askNotifications`: dort nach der Mitteilungs-Erlaubnis fragen).
 */
internal class PairPreview(
    val watch: WatchEntity?,
    val reload: () -> Unit,
    val openStored: (id: Long, askNotifications: Boolean) -> Unit,
)

/**
 * Paarseite des Eintrags [watchId] (bzw. der Vorschau [preview]). Aktionen über [viewModel] (dieselbe Instanz wie die
 * Merkliste), Alarme über [alarms] (ViewModel dieser Seite). «Löschen» meldet das Banner mit «Rückgängig» an
 * [originEvents] (Merkliste bzw. Markt, woher die Seite kam); fehlt das Paar danach, geht die Seite zurück.
 */
@Composable
internal fun PairScreen(
    watchId: Long,
    viewModel: WatchlistViewModel,
    alarms: AlarmsViewModel,
    lockViewModel: PortfolioLockViewModel,
    originEvents: WatchUiEventsHolder,
    onBack: () -> Unit,
    preview: PairPreview? = null,
    /** Eben aus der Vorschau hinzugefügt: einmal nach der Mitteilungs-Erlaubnis fragen. */
    askNotifications: Boolean = false,
    onAskNotificationsHandled: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val markFallbackIds by viewModel.markFallbackIds.collectAsStateWithLifecycle()
    val hourlyCloseIds by viewModel.hourlyCloseIds.collectAsStateWithLifecycle()
    val now = rememberWatchlistClock()
    val stored = state.watches.firstOrNull { it.id == watchId }
    // Paar gelöscht (hier, in einem anderen Bildschirm oder per Widget): zurück, einmal
    var left by remember { mutableStateOf(false) }
    LaunchedEffect(state.loaded, stored == null) {
        if (preview == null && state.loaded && stored == null && !left) {
            left = true
            onBack()
        }
    }
    val changeView = remember(state.display.changeBasis, state.live.changeStamp, now) {
        ChangeView.of(state.live.changeStamp, state.display.changeBasis, now)
    }
    // Vorschau: rollende 24 h aus dem Ticker — so heisst es auch neben der Änderung
    val previewView = remember { ChangeView(basis = ChangeBasis.ROLLING_24H, current = true) }
    val scope = rememberCoroutineScope()
    val banner = rememberWatchlistBanner(scope)
    val pageEvents = rememberWatchUiEvents(PAIR_UI_EVENTS)
    val addState = rememberPairAddState()
    PairUiEffects(pageEvents, viewModel, banner, addState, preview, askNotifications, onAskNotificationsHandled)
    // Paar bzw. Alarm nicht geändert, weil die Merkliste gesperrt ist: kurzer Hinweis mit Warn-Haptik
    com.cryptochecker.app.ui.components.WatchlistLockedHint { banner.show(it, SnackbarKind.ERROR) }
    // Eben hinzugefügt: Vorschau bleibt stehen, bis der neue Eintrag einen Kurs hat (höchstens kurz), dann seine Seite
    val added = addState.addedId?.let { id -> state.watches.firstOrNull { it.id == id } }
    LaunchedEffect(addState.addedId, added?.lastUpdate, added?.lastError) {
        val id = addState.addedId ?: return@LaunchedEffect
        if (added == null || (added.lastUpdate <= 0 && added.lastError == null)) kotlinx.coroutines.delay(ADD_HOLD_MILLIS)
        preview?.openStored(id, true)
    }

    val shown = stored ?: preview?.watch
    val isPreview = stored == null
    // Live-Kurs (WebSocket) wie die Zeile der Merkliste und iOS; gelesen nur im Inhalt ([WithLiveQuote])
    PairLiveEffect(viewModel, stored)
    val livePrices = viewModel.livePrices.collectAsStateWithLifecycle()
    val alarmHost = rememberAlarmEditorHost(alarms)
    val limits = rememberPairAlarmLimits(alarms, stored)
    var menuOpen by remember { mutableStateOf(false) }
    var dialogs by rememberSaveable(stateSaver = PairDialogs.saver) { mutableStateOf(PairDialogs()) }
    var portfolioFor by remember { mutableStateOf<WatchEntity?>(null) }
    val portfolioAccess by lockViewModel.access.collectAsStateWithLifecycle()
    LaunchedEffect(portfolioAccess) { if (portfolioAccess != PortfolioAccess.OPEN) portfolioFor = null }
    val context = LocalContext.current
    val resources = LocalResources.current
    val unlockReason = stringResource(R.string.portfolio_lock_reason)
    val off = shown != null && shown.disabled && !isPreview
    CompositionLocalProvider(
        LocalChangeView provides if (isPreview) previewView else changeView,
        LocalMarkFallback provides markFallbackIds,
        LocalHourlyClose provides hourlyCloseIds,
        com.cryptochecker.app.ui.components.LocalPriceNotificationsOn provides state.display.priceNotificationsOn,
    ) {
        Scaffold(
            modifier = Modifier.testTag(UiTestTags.PAIR_SCREEN),
            topBar = {
                TopAppBar(
                    title = { Text(shown?.displayName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        // Seltenes im Menü ⋮ (alles braucht einen gespeicherten Eintrag)
                        if (stored != null) {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                            }
                            PairOverflowMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                watch = stored,
                                off = off,
                                actions = PairMenuActions(
                                    onEditGroup = { dialogs = dialogs.copy(group = true) },
                                    onEditNote = { dialogs = dialogs.copy(note = true) },
                                    // Auch mit ausgeschaltetem Portfolio: das erste Speichern schaltet es ein
                                    onAddToPortfolio = if (PortfolioOff.offerAddToPortfolio(preview = false)) {
                                        {
                                            lockViewModel.requireUnlock(context.findFragmentActivity(), unlockReason) {
                                                portfolioFor = stored
                                            }
                                        }
                                    } else null,
                                    onRefresh = { viewModel.refreshOneByUser(stored) },
                                    onAddWidget = {
                                        if (!WidgetPinner.request(context, WidgetKind.SINGLE, stored.id)) dialogs = dialogs.copy(widget = true)
                                    },
                                    onTtsChange = { on ->
                                        // Sprachausgabe war aus bzw. «Nur Alarme»: jetzt an — kurz sagen, wo man es ändert
                                        if (viewModel.setTtsEnabled(stored, on)) banner.show(resources.getString(R.string.watchlist_tts_speech_on))
                                    },
                                    onToggleDisabled = { pageEvents.toggleDisabled(viewModel, stored) },
                                    // Wie nach links wischen: sofort, «Rückgängig» im Banner dort, woher die Seite kam
                                    onDelete = { originEvents.deleteWithUndo(viewModel, stored) },
                                ),
                            )
                        }
                    },
                )
            },
            snackbarHost = { AppSnackbarHost(banner.host) },
        ) { padding ->
            if (shown == null) return@Scaffold
            val signals = if (isPreview) emptyList() else ActivityAnalyzer.applySensitivity(
                state.content.activity[shown.id]?.active(now).orEmpty(), state.display.activitySensitivity,
            )
            WithLiveQuote(
                shown, livePrices, rollingBasis = !isPreview && !state.display.changeBasis.isDay,
                // Am Live-Strom: Frische am letzten Takt (wie Zeile und Status-Pille)
                onLiveSocket = !isPreview && shown.id in state.live.liveWatchIds, now = now,
            ) { live ->
                PairPageContent(
                    // Börse stillgelegt (RetiredWatch: Marker oder Ende erreicht): «—» mit Grund statt des letzten Kurses
                    watch = com.cryptochecker.app.domain.watch.RetiredWatch.shown(if (isPreview) shown else live, now),
                    isPreview = isPreview,
                    off = off,
                    now = now,
                    // Am Live-Strom: 2 Min. wie Zeile und Status-Pille
                    outdatedAfter = if (isPreview) 0L else com.cryptochecker.app.domain.watch.LiveStatus.staleAfterMillis(
                        shown.id in state.live.liveWatchIds, state.display.staleAfterMillis,
                    ),
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
                        onWhy = { dialogs = dialogs.copy(why = true) },
                        onChangeExchange = { dialogs = dialogs.copy(editPair = true) },
                        onNotificationChange = { on ->
                            // Erlaubnis erst nach dem Schreiben (nicht bei «Abbrechen» bzw. gesperrt)
                            viewModel.setNotificationEnabled(shown, on) { written ->
                                if (written && on) pageEvents.send(WatchUiEvent.RequestNotifications)
                            }
                        },
                        onEditNote = { dialogs = dialogs.copy(note = true) },
                    ),
                    padding = padding,
                )
            }
            if (dialogs.why && !shown.isNotTraded && !off && com.cryptochecker.app.domain.watch.RetiredWatch.exchangeOf(shown, now) == null) {
                WhySheet(
                    watch = shown,
                    signals = signals,
                    load = viewModel::explain,
                    onDismiss = { dialogs = dialogs.copy(why = false) },
                    // Bei rollender Basis die Änderung selbst; bei «seit 00:00» rechnet es eigene Kerzen
                    candleSource = if (isPreview) null else viewModel.foreignCandleSource(shown).takeIf { !state.display.changeBasis.isDay },
                )
            }
        }
    }
    if (stored != null) {
        PairDialogsHost(
            watch = stored,
            dialogs = dialogs,
            onDialogsChange = { change -> dialogs = change(dialogs) },
            groups = state.groups,
            alarmCount = state.content.alarmCounts[stored.id] ?: 0,
            viewModel = viewModel,
        )
        AlarmEditorHost(alarmHost, alarms, banner.host)
    }
    if (dialogs.widget) WidgetManualDialog(onDismiss = { dialogs = dialogs.copy(widget = false) })
    // Kurs nur vorbelegen, wenn die Quote praktisch USDT ist
    portfolioFor?.let { watch ->
        PortfolioQuickAddSheet(
            coin = watch.baseAsset,
            priceUsdt = watch.lastPrice?.takeIf { it > 0.0 && watch.quoteAsset.uppercase() in USD_LIKE_QUOTES },
            onDismiss = { portfolioFor = null },
            onPortfolioEnabled = { banner.show(resources.getString(R.string.portfolio_enabled_banner)) },
            // «Später»: Transaktion gespeichert, Tab bleibt aus — einmal der Weg zum Schalter
            onPortfolioLater = { banner.show(resources.getString(R.string.portfolio_enable_later_hint)) },
        )
    }
}

/** Notiz, Gruppe und «Börse wechseln» des gespeicherten Paars. */
@Composable
private fun PairDialogsHost(
    watch: WatchEntity,
    dialogs: PairDialogs,
    onDialogsChange: ((PairDialogs) -> PairDialogs) -> Unit,
    groups: List<String>,
    alarmCount: Int,
    viewModel: WatchlistViewModel,
) {
    // Notiz bzw. Gruppe wird gespeichert (Rückfrage «nicht gespeichert» offen): weitere Tipps zählen nicht (wie iOS)
    var saving by remember { mutableStateOf(false) }
    if (dialogs.editPair) {
        WatchEditSheet(watch = watch, alarmCount = alarmCount, onDismiss = { onDialogsChange { it.copy(editPair = false) } })
    }
    if (dialogs.note) {
        NoteDialog(
            title = stringResource(R.string.note_title),
            initial = watch.note,
            // «Abbrechen» in der Rückfrage «nicht gespeichert»: Dialog bleibt mit dem Text (wie der Alarm-Editor)
            onSave = { note ->
                if (!saving) {
                    saving = true
                    viewModel.setNote(watch, note, { onDialogsChange { it.copy(note = false) }; saving = false }, { saving = false })
                }
            },
            onDismiss = { onDialogsChange { it.copy(note = false) }; saving = false }
        )
    }
    if (dialogs.group) {
        GroupDialog(
            current = watch.groupName,
            groups = groups,
            onSelect = { group ->
                if (!saving) {
                    saving = true
                    viewModel.setGroup(watch, group, { onDialogsChange { it.copy(group = false) }; saving = false }, { saving = false })
                }
            },
            onDismiss = { onDialogsChange { it.copy(group = false) }; saving = false }
        )
    }
}

/** So lange bleibt die Vorschau nach «Hinzufügen» höchstens stehen, bis der erste Kurs da ist. */
private const val ADD_HOLD_MILLIS = 6_000L
