package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import com.cryptochecker.app.ui.components.AppSnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.domain.alarm.AlarmCardStatus
import com.cryptochecker.app.domain.alarm.DerivativesAlarm
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.ui.components.AppFab
import com.cryptochecker.app.ui.components.EmptyState
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BidiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics
import com.cryptochecker.app.ui.components.showAppSnackbar
import com.cryptochecker.app.ui.theme.SnackbarKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsScreen(
    onBack: () -> Unit,
    viewModel: AlarmsViewModel = hiltViewModel(),
) {
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val targetCurrency by viewModel.targetCurrency.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val unchecked by viewModel.unchecked.collectAsStateWithLifecycle()
    val lastChecks by viewModel.lastChecks.collectAsStateWithLifecycle()
    // DEX-Pool mit zu wenig Liquidität: kein neuer Alarm, kein Bearbeiten (Hinweis statt Editor)
    val lowLiquidityState by viewModel.lowLiquidity.collectAsStateWithLifecycle()
    val lowLiquidity = lowLiquidityState == true
    // Höchstzahl Alarme am Paar erreicht (WatchlistLimits): kein «Neuer Alarm», Hinweis über der Liste
    val alarmsFull = !WatchlistLimits.canAddAlarm(alarms.size)
    /** Rückfrage «Alarm löst sofort aus»: der Entwurf, der gespeichert würde. */
    var confirmFiresNow by rememberSaveable(stateSaver = AlarmDraftSaver.saver) { mutableStateOf<AlarmDraft?>(null) }

    // Offener Editor samt Eingaben übersteht Drehen und Prozess-Neustart (sonst wäre alles Getippte weg). Im ViewModel
    // (ARC5-R01): «Trotzdem» in der Rückfrage F-08 schliesst ihn auch nach dem Neuerstellen der Activity
    var draft by viewModel.editor
    // Neuer Alarm im einfachen Modus: Feld leer, aktueller Kurs nur als Platzhalter
    val newDraft: () -> AlarmDraft = { AlarmDraft.blankFor() }
    // Paar ohne Alarm: gleich den Editor öffnen statt der leeren Liste (einmal je Besuch)
    val noAlarms by viewModel.noAlarms.collectAsStateWithLifecycle()
    val watchMissing by viewModel.watchMissing.collectAsStateWithLifecycle()
    var autoOpenDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(noAlarms, lowLiquidityState, watchMissing) {
        val empty = noAlarms ?: return@LaunchedEffect
        // Erst wenn feststeht, ob der DEX-Pool genug Liquidität hat (sonst kein Editor) und ob es das Paar noch gibt
        val low = lowLiquidityState ?: return@LaunchedEffect
        val missing = watchMissing ?: return@LaunchedEffect
        // Merkliste gesperrt (nur im Arbeitsspeicher): kein Editor von selbst, ein Alarm liesse sich nicht speichern
        if (!autoOpenDone && empty && !low && !missing && !viewModel.alarmSaveBlocked()) draft = newDraft()
        autoOpenDone = true
    }
    // «Anpassen» aus der Alarm-Mitteilung: Editor dieses Alarms einmal öffnen, sobald er geladen ist (nicht bei zu
    // wenig DEX-Liquidität — dann bleibt die Liste mit dem Hinweis)
    var editOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(alarms, lowLiquidityState) {
        val alarm = alarms.firstOrNull { it.id == viewModel.editAlarmId }
        val low = lowLiquidityState
        if (editOpened || alarm == null || low == null) return@LaunchedEffect
        editOpened = true
        if (!low) draft = AlarmDraft.from(alarm)
    }
    LaunchedEffect(draft?.id) {
        // Schnell-Alarme: prüfen, ob das Paar Kerzen hat (nur beim Anlegen)
        if (draft?.id == 0L) viewModel.loadTemplateData()
    }
    val snackbar = remember { SnackbarHostState() }
    // Alarm nicht geändert bzw. gelöscht, weil die Merkliste gesperrt ist: kurzer Hinweis mit Warn-Haptik
    com.cryptochecker.app.ui.components.WatchlistLockedHint {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showAppSnackbar(it, SnackbarKind.ERROR)
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Paar gibt es nicht mehr (z. B. «Anpassen» aus der Mitteilung eines gelöschten Paars): kein Editor ohne Paar —
    // kurzer Hinweis (überdauert das Zurück, darum kein Snackbar) und zurück. Merkliste nur gesperrt: das sagen statt
    // «Nicht in der Merkliste»
    val missingText = stringResource(R.string.watch_preview_not_watched)
    val lockedText = stringResource(R.string.alarm_save_storage_locked)
    LaunchedEffect(watchMissing) {
        if (watchMissing != true) return@LaunchedEffect
        draft = null
        val locked = viewModel.alarmSaveBlocked()
        android.widget.Toast.makeText(
            context, if (locked) lockedText else missingText,
            if (locked) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
        ).show()
        onBack()
    }
    var askDelete by remember { mutableStateOf<AlarmEntity?>(null) }
    val firstAlarm by viewModel.firstAlarm.collectAsStateWithLifecycle()
    val timingHint by viewModel.timingHint.collectAsStateWithLifecycle()
    val exactOffer by viewModel.exactOffer.collectAsStateWithLifecycle()
    // Hinweis «Nachtruhe» über die Snackbar dieses Bildschirms (statt eines System-Toasts)
    val testAlarm = com.cryptochecker.app.ui.components.rememberAlarmTest(viewModel::testAlarm) { text ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text, duration = SnackbarDuration.Long)
        }
    }
    // ARC5-R01: Erlaubnis, Haptik und Bestätigung nach dem Speichern im aktuellen Bildschirm
    AlarmEditorEffects(viewModel, snackbar, watch, testAlarm)

    // Einmal nach dem Speichern: ehrlich, wann bei geschlossener App geprüft wird
    if (timingHint) {
        AlarmTimingDialog(onDismiss = viewModel::dismissTimingHint)
    }

    // Nach dem allerersten Alarm: EIN Dialog statt mehrerer nacheinander — Bestätigung mit «Alarm testen», dazu
    // optional «Pünktlich prüfen» und, nur wenn die Akku-Optimierung die App einschränkt, die
    // Akku-Einstellungen. Der eigene Akku-Dialog entfällt dann in dieser Sitzung. Hauptknopf: «Damit Alarme ankommen».
    if (firstAlarm) {
        FirstAlarmDialog(viewModel = viewModel, exactOffer = exactOffer, testAlarm = testAlarm)
    }

    askDelete?.let { alarm ->
        DeleteAlarmDialog(
            onDelete = {
                viewModel.delete(alarm.id)
                askDelete = null
            },
            onDismiss = { askDelete = null },
        )
    }

    val notificationsEnabled = com.cryptochecker.app.ui.components.rememberNotificationsEnabled()
    // Statuszeile der Karten (AlarmCardStatus): Mitteilungen samt Alarm-Kanal bei jeder Rückkehr neu prüfen,
    // die Uhr jede Minute — ein Kurs wird auch ohne neue Daten veraltet
    val staleAfter by viewModel.staleAfterMillis.collectAsStateWithLifecycle()
    val markFallback by viewModel.markFallback.collectAsStateWithLifecycle()
    var alarmsAllowed by remember { mutableStateOf(viewModel.alarmsAllowed()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(notificationsEnabled) {
        alarmsAllowed = viewModel.alarmsAllowed()
        onPauseOrDispose { }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STATUS_TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.alarms_title))
                        watch?.let {
                            Text(
                                "${BidiText.isolate(it.displayName)} · ${BidiText.isolate(it.marketName)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (!lowLiquidity && !alarmsFull) {
                AppFab(onClick = { draft = newDraft() }, contentDescription = stringResource(R.string.alarms_add))
            }
        },
        // «Alarm erstellt · Rückgängig» nach einem Schnell-Alarm (Live-Region über den SnackbarHost)
        snackbarHost = { AppSnackbarHost(snackbar) }
    ) { padding ->
        if (alarms.isEmpty() && lowLiquidity) {
            // DEX-Pool zu dünn: statt «Alarm hinzufügen» die Erklärung
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) { DexLowLiquidityNote() }
        } else if (alarms.isEmpty()) {
            // L-18: Beispiel im Stil der Alarm-Sätze, damit klar ist, was ein Alarm kann (wie iOS)
            EmptyState(
                iconRes = R.drawable.ic_notifications_active,
                title = stringResource(R.string.alarms_empty),
                example = alarmsEmptyExample(),
                actionLabel = stringResource(R.string.alarms_add),
                onAction = { draft = newDraft() },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            // Tablet/Querformat: Inhalt höchstens 640 dp breit, Liste bleibt voll breit scrollbar
            ReadableInset { inset ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 12.dp + inset, top = 12.dp, end = 12.dp + inset, bottom = Spacing.fabClearance),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    if (!notificationsEnabled) {
                        item(key = "notifications_off") { com.cryptochecker.app.ui.components.NotificationsOffBanner() }
                    }
                    if (lowLiquidity) {
                        item(key = "dex_low_liquidity") { DexLowLiquidityNote() }
                    }
                    if (alarmsFull) {
                        item(key = "alarm_limit") { AlarmLimitNote() }
                    }
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            baseAsset = watch?.baseAsset.orEmpty(),
                            quoteAsset = watch?.quoteAsset.orEmpty(),
                            unchecked = unchecked[alarm.id],
                            status = AlarmCardStatus.of(
                                alarmEnabled = alarm.enabled,
                                notificationsAllowed = alarmsAllowed,
                                dexBlocksAlarms = lowLiquidity,
                                exchangeTime = watch?.exchangeTime,
                                lastUpdate = watch?.lastUpdate ?: 0L,
                                now = now,
                                staleAfterMillis = staleAfter,
                                unchecked = unchecked[alarm.id] != null,
                                markFallback = markFallback,
                            ),
                            onToggle = { viewModel.setEnabled(alarm.id, it) },
                            onEdit = { if (!lowLiquidity) draft = AlarmDraft.from(alarm) },
                            onDelete = { askDelete = alarm }
                        )
                    }
                }
            }
        }
    }

    val haptics = rememberHaptics()
    // Merkliste nur leer im Arbeitsspeicher: Alarm nicht anlegen, Editor zu und sagen warum (sonst still verloren)
    fun showSaveLocked(): Boolean {
        if (!viewModel.alarmSaveBlocked()) return false
        draft = null
        snackbar.showNotice(scope, lockedText, haptics, HapticEvent.ERROR)
        return true
    }

    /**
     * Speichern; Editor zu, Erlaubnis und Bestätigung ([AlarmEditorEffects]). UX4-06, UX4-R3: Editor erst nach
     * bestätigter Rückfrage F-08 zu («Abbrechen»: offen mit Eingaben), Erlaubnis erst nach dem Speichern.
     */
    fun saveDraft(toSave: AlarmDraft) {
        if (showSaveLocked()) return
        viewModel.save(toSave)
    }

    PendingUndoSnackbar(viewModel.undo, viewModel.firstAlarm, viewModel.timingHint, snackbar, UNDO_MILLIS, viewModel::delete)

    draft?.let { raw ->
        // Aktueller Kurs in der Währung des Schwellwerts: entscheidet bei «60,000» (Tausender oder Komma?)
        val current = raw.copy(priceHint = AlarmDraft.priceHint(watch?.lastPrice, raw.currency, rates))
        AlarmEditorSheet(
            draft = current,
            baseAsset = watch?.baseAsset.orEmpty(),
            quoteAsset = watch?.quoteAsset.orEmpty(),
            targetCurrency = targetCurrency,
            rates = rates,
            templates = templates,
            // Funding/Open Interest nur für Perpetuals an Börsen mit eigenen Daten
            derivatives = watch?.let {
                DerivativesAlarm.supports(it.marketKey, it.contractType.isPerpetual)
            } == true,
            onLoadRate = viewModel::loadRate,
            onDraftChange = { draft = it },
            // Editor erst nach bestätigter Rückfrage F-08 zu, Erlaubnis erst nach dem Anlegen
            onTemplate = { template -> if (!showSaveLocked()) viewModel.createFromTemplate(template) },
            onDismiss = { draft = null },
            // Kopf mit Paar und aktuellem Kurs (wie iOS)
            watch = watch,
            lastCheck = lastChecks[current.id],
            onConfirm = {
                // Kursmarke schon erreicht: erst nachfragen (sonst meldet der Alarm sofort)
                if (viewModel.firesOnSave(current)) {
                    confirmFiresNow = current
                } else {
                    saveDraft(current)
                }
            }
        )
    }

    confirmFiresNow?.let { pending ->
        FiresNowDialog(
            pending = pending,
            quoteAsset = watch?.quoteAsset.orEmpty(),
            onSave = {
                confirmFiresNow = null
                saveDraft(pending)
            },
            onDismiss = { confirmFiresNow = null },
        )
    }
}

/** So lange steht «Alarm erstellt · Rückgängig». */
private const val UNDO_MILLIS = 6_000L

/** Takt, in dem die Statuszeilen der Karten die Uhr nachführen («Wartet auf aktuellen Kurs»). */
private const val STATUS_TICK_MILLIS = 60_000L
